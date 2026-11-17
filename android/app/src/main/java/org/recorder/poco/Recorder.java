package org.recorder.poco;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera, codec, muxer, lifecycle and files belong to q. Sensor ingress is bounded. */
final class Recorder {
    interface UI {
        void status(Lifecycle.State state, String text);
        void modes(List<Mode> modes, String camera);
        void dataset(File folder);
        void report(File report);
    }
    static final class Mode {
        final int width,height,fps; final boolean highSpeed; final Range<Integer> range;
        Mode(int w,int h,int f,boolean hs,Range<Integer> r) { width=w;height=h;fps=f;highSpeed=hs;range=r; }
        public String toString() { return width+"×"+height+" · "+fps+" fps"+(highSpeed?" · high-speed":""); }
    }
    private final Context context; private final UI ui;
    private final HandlerThread thread=new HandlerThread("capture-and-disk"), sensorThread=new HandlerThread("sensor-ingress");
    private final Handler q, sensorHandler;
    private final Lifecycle life=new Lifecycle();
    private final CameraManager cameras; private final SensorManager sensors;
    private CameraCharacteristics characteristics; private String cameraId;
    private CameraDevice camera; private CameraCaptureSession session;
    private MediaCodec codec; private MediaMuxer muxer; private Surface encoderSurface,previewSurface;
    private SensorEventListener sensorListener;
    private Mode mode; private JSONObject manifest;
    private final Map<String,BufferedWriter> logs=new LinkedHashMap<>();
    private File folder; private int track=-1; private boolean muxStarted, firstKey, eosSeen, terminalFailure;
    private boolean foreground=true; private long firstPts=-1, startNs, stopNs, limitNs, lastVideoArrival,lastAccelArrival,lastGyroArrival;
    private String stopReason="", codecName="", mime=""; private long captureFailures;
    private TimingStats video=new TimingStats(), accel=new TimingStats(), gyro=new TimingStats();
    private final List<String> errors=new ArrayList<>();
    private Sensor accelSensor,gyroSensor;
    private Float observedFocus;
    private static long now() { return SystemClock.elapsedRealtimeNanos(); }
    Recorder(Context c, UI u) {
        context=c.getApplicationContext();ui=u;cameras=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        sensors=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        thread.start();sensorThread.start();q=new Handler(thread.getLooper());sensorHandler=new Handler(sensorThread.getLooper());
    }
    private void show(String text) { ui.status(life.state,text); }
    private File root() { File r=context.getExternalFilesDir(null);return r==null?context.getFilesDir():r; }
    void discover() { q.post(()->{try { discoverImpl(); } catch(Exception e){fail(e);} }); }
    private void discoverImpl() throws Exception {
        List<Mode> choices=new ArrayList<>(); JSONArray all=new JSONArray(); long largest=-1;
        for(String id:cameras.getCameraIdList()) {
            CameraCharacteristics c=cameras.getCameraCharacteristics(id);
            JSONObject info=new JSONObject();info.put("id",id); info.put("facing",c.get(CameraCharacteristics.LENS_FACING));
            info.put("hardware_level",c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            info.put("timestamp_source",c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
            int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            info.put("capabilities",new JSONArray(caps==null?new int[0]:caps));
            boolean logical=has(caps,CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
            info.put("logical_multi_camera",logical);
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            JSONArray high=new JSONArray();
            if(map!=null && has(caps,CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                for(Size size:map.getHighSpeedVideoSizes()) for(Range<Integer> range:map.getHighSpeedVideoFpsRangesFor(size))
                    high.put(new JSONObject().put("width",size.getWidth()).put("height",size.getHeight()).put("min_fps",range.getLower()).put("max_fps",range.getUpper()));
            }
            info.put("high_speed_modes",high);all.put(info);
            Size pixel=c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            long area=pixel==null?0:(long)pixel.getWidth()*pixel.getHeight();
            if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK) && !logical && area>largest) {
                largest=area;cameraId=id;characteristics=c;
            }
        }
        if(characteristics==null) throw new IOException("No fixed, non-logical rear camera is exposed by Android");
        StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if(map==null) throw new IOException("No camera stream configuration map");
        Range<Integer>[] normal=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int[][] candidates={{1920,1080,120},{1280,720,240},{1280,720,120},{1920,1080,60},{1920,1080,30},{3840,2160,30}};
        boolean hs=has(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
        for(int[] c:candidates) {
            Size sz=new Size(c[0],c[1]); Range<Integer> chosen=null;
            if(c[2]>=120 && hs) {
                if(Arrays.asList(map.getHighSpeedVideoSizes()).contains(sz))
                    for(Range<Integer> r:map.getHighSpeedVideoFpsRangesFor(sz)) if(r.getLower()==c[2] && r.getUpper()==c[2]) chosen=r;
            } else if(c[2]<120 && Arrays.asList(map.getOutputSizes(MediaCodec.class)).contains(sz)) {
                long duration=map.getOutputMinFrameDuration(MediaCodec.class,sz);
                if(duration>0 && duration<=1_000_000_000L/c[2]+1000 && normal!=null)
                    for(Range<Integer> r:normal) if(r.getLower()==c[2] && r.getUpper()==c[2]) chosen=r;
            }
            if(chosen!=null) choices.add(new Mode(c[0],c[1],c[2],c[2]>=120,chosen));
        }
        JSONObject report=new JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("cameras",all).put("selected_camera",cameraId);
        JSONArray available=new JSONArray(); for(Mode m:choices)available.put(m.toString());report.put("offered_modes",available);
        JSONArray sensorInfo=new JSONArray();for(Sensor s:sensors.getSensorList(Sensor.TYPE_ALL))
            sensorInfo.put(new JSONObject().put("name",s.getName()).put("type",s.getType()).put("min_delay_us",s.getMinDelay()).put("vendor",s.getVendor()));
        report.put("sensors",sensorInfo);
        File file=new File(root(),"capabilities.json");atomicJson(file,report);ui.report(file);ui.modes(choices,"Rear camera "+cameraId+" · "+Build.MODEL);
        show(choices.isEmpty()?"No supported target modes exposed. Export capability report.":"Choose a mode, then Prepare preview. 120+ fps requires an exposed high-speed mode.");
    }
    private static boolean has(int[] values,int key) { if(values!=null)for(int v:values)if(v==key)return true;return false; }
    void resume() { q.post(()->foreground=true); }
    void destroy() { pause();q.postDelayed(()->{closeCameraCodec();sensorThread.quitSafely();thread.quitSafely();},8000); }
    void pause() { q.post(()->{
        foreground=false;
        if(life.state==Lifecycle.State.RECORDING) stopImpl("app backgrounded");
        else if(life.state!=Lifecycle.State.FINALIZING){life.cancel();closeCameraCodec();show("Camera closed while app is backgrounded");}
    }); }
    void prepare(Mode chosen,SurfaceTexture texture) { q.post(()->{
        try {
            if(!foreground)return;
            long token=life.prepare();closeCameraCodec();mode=chosen;show("Preparing "+mode+"…");
            texture.setDefaultBufferSize(mode.width,mode.height);previewSurface=new Surface(texture);
            configureCodec(token);encoderSurface=codec.createInputSurface();codec.start();
            if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) throw new IOException("Camera permission required");
            cameras.openCamera(cameraId,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice device){
                    if(token!=life.token||!foreground){device.close();return;}camera=device;
                    try { createSession(token); } catch(Exception e){fail(e);}
                }
                public void onDisconnected(CameraDevice device){device.close();if(token==life.token)fail(new IOException("Camera disconnected"));}
                public void onError(CameraDevice device,int error){device.close();if(token==life.token)fail(new IOException("Camera error "+error));}
            },q);
        }catch(Exception e){fail(e);}
    }); }
    private void configureCodec(long token) throws IOException {
        MediaCodecInfo selected=null;
        for(String candidate:new String[]{MediaFormat.MIMETYPE_VIDEO_HEVC,MediaFormat.MIMETYPE_VIDEO_AVC}){
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if(!info.isEncoder()||!info.isHardwareAccelerated())continue;
                try {
                    if(!Arrays.asList(info.getSupportedTypes()).contains(candidate))continue;
                    MediaCodecInfo.VideoCapabilities vc=info.getCapabilitiesForType(candidate).getVideoCapabilities();
                    if(vc!=null && vc.areSizeAndRateSupported(mode.width,mode.height,mode.fps)){selected=info;mime=candidate;break;}
                }catch(IllegalArgumentException ignored){}
            }if(selected!=null)break;
        }
        if(selected==null)throw new IOException("No hardware encoder reports support for "+mode+". No resolution/fps fallback.");
        codecName=selected.getName();codec=MediaCodec.createByCodecName(codecName);
        codec.setCallback(new MediaCodec.Callback(){
            public void onInputBufferAvailable(MediaCodec c,int index){}
            public void onOutputFormatChanged(MediaCodec c,MediaFormat f){
                if(token!=life.token)return;
                // Format is also obtained at Start; preview can be drained before recording.
            }
            public void onError(MediaCodec c,MediaCodec.CodecException e){if(token==life.token)fail(e);}
            public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info){
                if(token!=life.token)return;
                boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                try {
                    if(life.state==Lifecycle.State.RECORDING||life.state==Lifecycle.State.FINALIZING) consume(c,index,info);
                }catch(Exception e){terminalFailure=true;errors.add(e.toString());stopImpl("encoder/disk error: "+e);}
                finally {try{c.releaseOutputBuffer(index,false);}catch(Exception ignored){}}
                if(eos && life.state==Lifecycle.State.FINALIZING){eosSeen=true;q.post(()->finish(token));}
            }
        },q);
        MediaFormat format=MediaFormat.createVideoFormat(mime,mode.width,mode.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,mode.width>=3840?45_000_000:mode.fps>=120?35_000_000:18_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE,mode.fps);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    private void createSession(long token) throws CameraAccessException {
        List<Surface> surfaces=Arrays.asList(previewSurface,encoderSurface);
        CameraCaptureSession.StateCallback callback=new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession s){
                if(token!=life.token||!foreground){s.close();return;} session=s;
                try { setRequest(false,token);if(life.ready(token))show("Ready · "+mode+" · "+mime+". Let focus/exposure settle before Start."); }
                catch(Exception e){fail(e);}
            }
            public void onConfigureFailed(CameraCaptureSession s){if(token==life.token)fail(new IOException("Camera rejected the exact requested session; no fallback"));}
        };
        if(mode.highSpeed)camera.createConstrainedHighSpeedCaptureSession(surfaces,callback,q);
        else camera.createCaptureSession(surfaces,callback,q);
    }
    private void setRequest(boolean lock,long token) throws CameraAccessException {
        CaptureRequest.Builder r=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        r.addTarget(previewSurface);r.addTarget(encoderSurface);
        r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,mode.range);
        r.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if(lock){
            // AE/AWB locks are independent; high-speed sessions may override some controls.
            if(Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))r.set(CaptureRequest.CONTROL_AE_LOCK,true);
            if(Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)))r.set(CaptureRequest.CONTROL_AWB_LOCK,true);
            if(observedFocus!=null && has(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_OFF)){
                r.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);r.set(CaptureRequest.LENS_FOCUS_DISTANCE,observedFocus);
            }
        }
        r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        int[] ois=characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if(has(ois,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF))r.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        r.set(CaptureRequest.SCALER_CROP_REGION,characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
        if(mode.highSpeed){CameraConstrainedHighSpeedCaptureSession hs=(CameraConstrainedHighSpeedCaptureSession)session;
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(r.build()),captureCallback(token),q);
        }else session.setRepeatingRequest(r.build(),captureCallback(token),q);
    }
    private CameraCaptureSession.CaptureCallback captureCallback(long token){return new CameraCaptureSession.CaptureCallback(){
        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest request,TotalCaptureResult result){
            if(token!=life.token)return;
            observedFocus=result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if(life.state!=Lifecycle.State.RECORDING)return;
            try{
                Long ts=result.get(CaptureResult.SENSOR_TIMESTAMP);
                row("capture_results",result.getFrameNumber()+","+value(ts)+","+now()+","+value(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))+","+
                    value(result.get(CaptureResult.SENSOR_SENSITIVITY))+","+value(result.get(CaptureResult.LENS_FOCUS_DISTANCE))+","+
                    value(result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))+","+value(result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))+","+
                    value(result.get(CaptureResult.CONTROL_AF_STATE))+","+value(result.get(CaptureResult.CONTROL_AE_LOCK))+","+value(result.get(CaptureResult.CONTROL_AWB_LOCK)));
                float[] k=result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
                if(k!=null)row("intrinsics",result.getFrameNumber()+","+value(ts)+","+Arrays.toString(k).replace(',',';'));
            }catch(Exception e){fail(e);}
        }
        public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure failure){
            if(token!=life.token||life.state!=Lifecycle.State.RECORDING)return;
            captureFailures++;event("capture_failure frame="+failure.getFrameNumber()+" reason="+failure.getReason());
        }
    };}
    private static String value(Object o){return o==null?"":String.valueOf(o);}
    void start(boolean lock,int seconds,int displayRotationDegrees){q.post(()->{
        if(!foreground || life.state!=Lifecycle.State.READY)return;
        try{
            Integer sensorOrientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotationDegrees=((sensorOrientation==null?90:sensorOrientation)-displayRotationDegrees+360)%360;
            if(root().getUsableSpace()<1_000_000_000L)throw new IOException("Keep at least 1 GB free to start");
            folder=new File(root(),"Recording-"+UUID.randomUUID());if(!folder.mkdirs())throw new IOException("Cannot create take folder");
            errors.clear();terminalFailure=false;eosSeen=false;firstKey=false;muxStarted=false;firstPts=-1;track=-1;captureFailures=0;
            video=new TimingStats();accel=new TimingStats();gyro=new TimingStats();stopReason="";stopNs=0;
            startNs=now();lastVideoArrival=lastAccelArrival=lastGyroArrival=startNs;limitNs=seconds>0?seconds*1_000_000_000L:0;
            open("frames","index,encoded_pts_us,mux_pts_us,callback_elapsed_ns,bytes,flags");
            open("accelerometer","timestamp_ns,x_m_s2,y_m_s2,z_m_s2,callback_elapsed_ns,bias_x,bias_y,bias_z");
            open("gyroscope","timestamp_ns,x_rad_s,y_rad_s,z_rad_s,callback_elapsed_ns,bias_x,bias_y,bias_z");
            open("capture_results","camera_frame_number,sensor_timestamp_ns,callback_elapsed_ns,exposure_ns,iso,focus_diopters,video_stabilization,ois,af_state,ae_lock,awb_lock");
            open("intrinsics","camera_frame_number,sensor_timestamp_ns,fx_fy_cx_cy_skew");open("events","elapsed_ns,event");
            manifest=new JSONObject().put("schema","poco-recorder-1").put("status","recording").put("completion_clean",false)
                .put("created_utc",java.time.Instant.now().toString()).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
                .put("camera_id",cameraId).put("width",mode.width).put("height",mode.height).put("fps_requested",mode.fps)
                .put("high_speed_session",mode.highSpeed).put("codec",mime).put("encoder",codecName).put("hardware_encoder",true)
                .put("start_elapsed_ns",startNs).put("duration_limit_s",seconds>0?seconds:JSONObject.NULL)
                .put("sensor_request","SENSOR_DELAY_FASTEST; maxReportLatencyUs=0; delivered rate measured independently")
                .put("sensor_units","accelerometer=m/s^2 including gravity; gyroscope=rad/s; no app filtering, resampling or gravity subtraction")
                .put("sensor_axes","Android device axes: x right, y top, z out of screen in natural portrait; right-handed; not camera-rotated")
                .put("sensor_data_kind","Android sensor API measurements, not hardware ADC samples; uncalibrated variants preferred; exposed bias retained separately")
                .put("sensor_time_basis","SensorEvent.timestamp ns since boot including sleep; elapsedRealtimeNanos for callback arrival")
                .put("camera_timestamp_source",characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                .put("clock_mapping","Original Camera2 SENSOR_TIMESTAMP ns and MediaCodec presentationTimeUs retained separately. REALTIME camera timestamps share elapsedRealtimeNanos basis; UNKNOWN source requires temporal calibration. Encoder PTS origin/basis must be checked against capture_results; do not infer alignment from callback arrival.")
                .put("video_time_origin","MP4 mux_pts_us = encoded_pts_us - first_encoded_pts_us; original PTS never replaced")
                .put("capture_metadata_warning","High-speed Camera2 metadata can be batched/sparse; camera frame numbers and codec frame indices are separate. Do not pair by row index.")
                .put("camera_lock_requested",lock).put("video_stabilization_requested","off").put("optical_stabilization_requested","off if supported; internal processing not certified disabled")
                .put("mp4_rotation_degrees",rotationDegrees).put("sensor_orientation_degrees",characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION))
                .put("physical_synchronization_accuracy","unvalidated").put("pending_sensor_capacity",512);
            writeManifest();muxer=new MediaMuxer(new File(folder,"video.mp4").getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotationDegrees);track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxStarted=true;
            if(!life.start())throw new IOException("Start state changed");
            setRequest(lock,life.token);registerSensors(life.token);
            Bundle params=new Bundle();params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);codec.setParameters(params);
            show("Recording · "+mode+" · waiting for keyframe");long token=life.token;q.postDelayed(()->tick(token),1000);
        }catch(Exception e){fail(e);}
    });}
    private void registerSensors(long token) throws Exception {
        accelSensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED);
        if(accelSensor==null)accelSensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if(gyroSensor==null)gyroSensor=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if(accelSensor==null||gyroSensor==null)throw new IOException("Independent accelerometer/gyro unavailable");
        manifest.put("accelerometer_sensor",sensorJson(accelSensor)).put("gyroscope_sensor",sensorJson(gyroSensor));writeManifest();
        Semaphore pending=new Semaphore(512);AtomicBoolean overflow=new AtomicBoolean();
        sensorListener=new SensorEventListener(){
            public void onAccuracyChanged(Sensor s,int accuracy){}
            public void onSensorChanged(SensorEvent e){
                long arrival=now(), timestamp=e.timestamp;boolean isAccel=e.sensor==accelSensor;
                if(!pending.tryAcquire()){
                    if(overflow.compareAndSet(false,true))q.post(()->{if(token==life.token)fail(new IOException("Sensor queue overflow: 512 pending samples"));});
                    return;
                }
                float[] values=e.values.clone();
                q.post(()->{try{
                    if(token!=life.token||life.state!=Lifecycle.State.RECORDING||timestamp<startNs)return;
                    TimingStats stats=isAccel?accel:gyro;
                    for(float v:values)if(!Float.isFinite(v))throw new IOException("Nonfinite sensor sample");
                    if(!stats.add(timestamp))throw new IOException("Invalid/nonmonotonic sensor timestamp");
                    String line=timestamp+","+values[0]+","+values[1]+","+values[2]+","+arrival+","+
                        (values.length>=6?values[3]+","+values[4]+","+values[5]:",,");
                    row(isAccel?"accelerometer":"gyroscope",line);
                    if(isAccel)lastAccelArrival=arrival;else lastGyroArrival=arrival;
                }catch(Exception ex){fail(ex);}finally{pending.release();}});
            }
        };
        if(!sensors.registerListener(sensorListener,accelSensor,SensorManager.SENSOR_DELAY_FASTEST,0,sensorHandler)
           || !sensors.registerListener(sensorListener,gyroSensor,SensorManager.SENSOR_DELAY_FASTEST,0,sensorHandler)) throw new IOException("Sensor registration failed");
    }
    private static JSONObject sensorJson(Sensor s) throws JSONException{return new JSONObject().put("name",s.getName()).put("type",s.getType()).put("vendor",s.getVendor()).put("min_delay_us",s.getMinDelay()).put("resolution",s.getResolution()).put("maximum_range",s.getMaximumRange());}
    private void consume(MediaCodec c,int index,MediaCodec.BufferInfo info) throws Exception {
        if(info.size<=0 || (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)return;
        if(Objects.equals(characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
           && info.presentationTimeUs>=0 && info.presentationTimeUs<Long.MAX_VALUE/1000){
            long candidate=info.presentationTimeUs*1000;
            if(candidate>startNs-15_000_000_000L && candidate<startNs){event("pre_start_encoded_frame pts_us="+info.presentationTimeUs);return;}
        }
        if(!firstKey && (info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)==0){event("pre_keyframe_skip pts_us="+info.presentationTimeUs);return;}
        if(!muxStarted)throw new IOException("Muxer not started");
        if(!firstKey){firstKey=true;firstPts=info.presentationTimeUs;manifest.put("first_encoded_pts_us",firstPts);writeManifest();}
        long sourcePts=info.presentationTimeUs;
        if(sourcePts<0 || sourcePts>Long.MAX_VALUE/1000 || !video.add(sourcePts*1000))throw new IOException("Invalid/nonmonotonic encoder PTS");
        ByteBuffer buffer=c.getOutputBuffer(index);if(buffer==null)throw new IOException("No codec output buffer");
        MediaCodec.BufferInfo adjusted=new MediaCodec.BufferInfo();adjusted.set(info.offset,info.size,sourcePts-firstPts,info.flags);
        muxer.writeSampleData(track,buffer,adjusted);lastVideoArrival=now();
        row("frames",(video.count-1)+","+sourcePts+","+(sourcePts-firstPts)+","+lastVideoArrival+","+info.size+","+info.flags);
    }
    private void tick(long token){
        if(token!=life.token||life.state!=Lifecycle.State.RECORDING)return;
        try{
            for(BufferedWriter w:logs.values())w.flush();long n=now();
            if(folder.getUsableSpace()<500_000_000L){stopImpl("low storage");return;}
            PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);int thermal=power.getCurrentThermalStatus();
            if(thermal>=PowerManager.THERMAL_STATUS_CRITICAL){stopImpl("critical thermal pressure");return;}
            if(n-startNs>5_000_000_000L && (n-lastVideoArrival>3_000_000_000L||n-lastAccelArrival>3_000_000_000L||n-lastGyroArrival>3_000_000_000L)){
                stopImpl("camera or IMU stream stalled");return;
            }
            if(limitNs>0 && n-startNs>=limitNs){stopImpl("duration reached");return;}
            show(String.format(Locale.US,"Recording %ds · requested %d fps\nMeasured: video %.1f fps · accel %.1f Hz · gyro %.1f Hz\nVideo max gap %.2f ms · capture failures %d%s",
                (n-startNs)/1_000_000_000L,mode.fps,video.hz(),accel.hz(),gyro.hz(),video.maxGap/1e6,captureFailures,thermal>=PowerManager.THERMAL_STATUS_SEVERE?" · HOT":""));
            q.postDelayed(()->tick(token),1000);
        }catch(Exception e){fail(e);}
    }
    void stop(){q.post(()->stopImpl("user stopped"));}
    private void stopImpl(String reason){
        if(!life.stop())return;stopReason=reason;stopNs=now();show("Finalizing… "+reason);
        if(sensorListener!=null){sensors.unregisterListener(sensorListener);sensorListener=null;}
        event("stop: "+reason);
        try{if(session!=null){session.stopRepeating();session.abortCaptures();}if(codec!=null)codec.signalEndOfInputStream();}
        catch(Exception e){terminalFailure=true;errors.add(e.toString());long t=life.token;q.post(()->finish(t));return;}
        long t=life.token;q.postDelayed(()->{if(t==life.token&&life.state==Lifecycle.State.FINALIZING){errors.add("Encoder EOS timeout");terminalFailure=true;finish(t);}},6000);
    }
    private void fail(Exception e){
        if(life.state==Lifecycle.State.RECORDING){terminalFailure=true;errors.add(e.toString());event("error: "+e);stopImpl("failure: "+e);}
        else if(life.state==Lifecycle.State.FINALIZING){terminalFailure=true;errors.add(e.toString());}
        else {
            if(folder!=null&&manifest!=null&&"recording".equals(manifest.optString("status"))){
                try{manifest.put("status","failed_start").put("error",e.toString());writeManifest();}catch(Exception ignored){}
            }
            if(muxer!=null){try{if(muxStarted)muxer.stop();}catch(Exception ignored){}try{muxer.release();}catch(Exception ignored){}muxer=null;muxStarted=false;}
            closeLogs();closeCameraCodec();life.token++;life.state=Lifecycle.State.FAILED;show("Failed: "+e.getMessage()+". Choose mode / Prepare again.");
        }
    }
    private void finish(long token){
        if(token!=life.token||life.state!=Lifecycle.State.FINALIZING)return;
        boolean containerOk=false;
        if(muxer!=null){try{if(muxStarted){muxer.stop();containerOk=true;}}catch(Exception e){errors.add("MP4 finalize: "+e);}finally{try{muxer.release();}catch(Exception e){errors.add("MP4 release: "+e);}muxer=null;muxStarted=false;}}
        closeLogs();life.token++;closeCameraCodec();
        boolean normal=stopReason.equals("user stopped")||stopReason.equals("duration reached");
        boolean clean=normal&&containerOk&&eosSeen&&!terminalFailure&&errors.isEmpty()&&video.count>=2&&accel.count>=2&&gyro.count>=2;
        boolean interrupted=stopReason.equals("app backgrounded");
        try{
            manifest.put("status",clean?"finalized":interrupted?"interrupted":"incomplete").put("completion_clean",clean)
                .put("stop_reason",stopReason).put("stop_elapsed_ns",stopNs).put("container_finalized",containerOk).put("encoder_eos",eosSeen)
                .put("errors",new JSONArray(errors)).put("capture_failures",captureFailures)
                .put("video",statsJson(video)).put("accelerometer",statsJson(accel)).put("gyroscope",statsJson(gyro));
            writeManifest();
        }catch(Exception e){clean=false;errors.add("Manifest finalize: "+e);}
        life.state=clean?Lifecycle.State.SAVED:interrupted?Lifecycle.State.INTERRUPTED:Lifecycle.State.FAILED;
        ui.dataset(folder);show((clean?"Saved":"Incomplete / interrupted")+" · "+folder.getName()+String.format(Locale.US,"\nVideo %.1f fps · accel %.1f Hz · gyro %.1f Hz\n",video.hz(),accel.hz(),gyro.hz())+stopReason+(errors.isEmpty()?"":"\n"+errors.get(0))+"\nPrepare preview for another take. Export the whole ZIP.");
    }
    private static JSONObject statsJson(TimingStats s) throws JSONException{return new JSONObject().put("count",s.count).put("first_ns",s.first).put("last_ns",s.last).put("measured_hz",s.hz()).put("max_gap_ns",s.maxGap).put("invalid_timestamps",s.invalid);}
    private void open(String name,String header)throws IOException{BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(folder,name+".csv")),StandardCharsets.UTF_8),32768);logs.put(name,w);w.write(header);w.newLine();}
    private void row(String name,String text)throws IOException{BufferedWriter w=logs.get(name);if(w==null)throw new IOException("Missing "+name+" writer");w.write(text);w.newLine();}
    private void event(String message){try{if(logs.containsKey("events"))row("events",now()+","+message.replace(',',';').replace('\n',' '));}catch(Exception e){errors.add("Event log: "+e);terminalFailure=true;}}
    private void closeLogs(){for(Map.Entry<String,BufferedWriter> e:logs.entrySet())try{e.getValue().close();}catch(Exception ex){errors.add(e.getKey()+": "+ex);}logs.clear();}
    private void writeManifest()throws Exception{atomicJson(new File(folder,"manifest.json"),manifest);}
    private static void atomicJson(File target,JSONObject data)throws Exception{
        File temp=new File(target.getParentFile(),target.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Cannot replace "+target.getName());
    }
    private void closeCameraCodec(){
        if(sensorListener!=null){sensors.unregisterListener(sensorListener);sensorListener=null;}
        if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}
        if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}
        if(encoderSurface!=null){encoderSurface.release();encoderSurface=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}
    }
}
