package org.recorder.poco;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.*;

public final class MainActivity extends Activity implements Recorder.UI {
    private Recorder recorder;
    private TextureView preview;
    private TextView status;
    private Spinner modes;
    private CheckBox lock;
    private Button prepare,start,stop,check,export,capabilities;
    private List<Recorder.Mode> options=new ArrayList<>();
    private File latest,report,pendingExport;
    private boolean exportZip,dead;
    private final java.util.concurrent.ExecutorService exportThread=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);root.setPadding(16,12,16,12);root.setBackgroundColor(0xff101820);
        preview=new TextureView(this);root.addView(preview,new LinearLayout.LayoutParams(0,-1,1.3f));
        ScrollView scroll=new ScrollView(this);LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(20,0,0,0);
        scroll.addView(controls);root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        TextView title=new TextView(this);title.setText("POCO Research Recorder");title.setTextSize(20);title.setTextColor(0xff75ded1);controls.addView(title);
        status=new TextView(this);status.setTextColor(0xffeeeeee);status.setTextSize(13);controls.addView(status);
        modes=new Spinner(this);controls.addView(modes);
        lock=new CheckBox(this);lock.setText("Request focus / exposure / WB lock");lock.setTextColor(0xffeeeeee);lock.setChecked(true);controls.addView(lock);
        TextView note=new TextView(this);note.setText("Let preview settle first. High-speed modes may override locks; actual camera metadata is saved. IMU request: fastest available; measured Hz is not guaranteed. Keep app foregrounded.");note.setTextColor(0xffaabbcc);note.setTextSize(12);controls.addView(note);
        prepare=button(controls,"Prepare preview",v->prepare());
        start=button(controls,"Start — manual stop",v->start(0));
        check=button(controls,"10-second recording check",v->start(10));
        stop=button(controls,"Stop and save",v->recorder.stop());
        export=button(controls,"Export latest take ZIP",v->export(false));
        capabilities=button(controls,"Export camera / sensor report",v->export(true));
        TextView footer=new TextView(this);footer.setText("Export the entire ZIP to Downloads or your cloud drive, then copy to Windows. No microphone, account, network connection or paid service required.");footer.setTextColor(0xffaabbcc);footer.setTextSize(12);controls.addView(footer);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(16+insets.getSystemWindowInsetLeft(),12+insets.getSystemWindowInsetTop(),16+insets.getSystemWindowInsetRight(),12+insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);recorder=new Recorder(this,this);status(Lifecycle.State.IDLE,"Allow camera access, then choose a mode.");
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){transform();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){transform();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){recorder.pause();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
        modes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){transform();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)recorder.discover();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    }
    private Button button(LinearLayout parent,String text,View.OnClickListener listener){Button b=new Button(this);b.setText(text);b.setOnClickListener(listener);parent.addView(b);return b;}
    private void prepare(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},1);return;}
        if(!preview.isAvailable()||options.isEmpty()){Toast.makeText(this,"Preview not ready / no exposed mode. Export capability report.",Toast.LENGTH_LONG).show();return;}
        transform();recorder.prepare(options.get(modes.getSelectedItemPosition()),preview.getSurfaceTexture());
    }
    private void start(int seconds){
        int r=getWindowManager().getDefaultDisplay().getRotation();
        recorder.start(lock.isChecked(),seconds,r*90);
    }
    private void transform(){
        if(!preview.isAvailable()||options.isEmpty())return;
        int index=modes.getSelectedItemPosition();if(index<0||index>=options.size())return;
        Recorder.Mode mode=options.get(index);int w=preview.getWidth(),h=preview.getHeight();if(w==0||h==0)return;
        Matrix matrix=new Matrix();RectF view=new RectF(0,0,w,h),buffer=new RectF(0,0,mode.height,mode.width);
        float cx=view.centerX(),cy=view.centerY();int rotation=getWindowManager().getDefaultDisplay().getRotation();
        if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270){
            buffer.offset(cx-buffer.centerX(),cy-buffer.centerY());matrix.setRectToRect(view,buffer,Matrix.ScaleToFit.FILL);
            float scale=Math.max((float)h/mode.height,(float)w/mode.width);matrix.postScale(scale,scale,cx,cy);matrix.postRotate(90*(rotation-2),cx,cy);
        }else if(rotation==Surface.ROTATION_180)matrix.postRotate(180,cx,cy);
        preview.setTransform(matrix);
    }
    @Override public void status(Lifecycle.State state,String text){runOnUiThread(()->{
        if(dead)return;status.setText(state+"\n"+text);
        boolean busy=state==Lifecycle.State.RECORDING||state==Lifecycle.State.FINALIZING||state==Lifecycle.State.PREPARING;
        modes.setEnabled(!busy);lock.setEnabled(!busy);prepare.setEnabled(!busy&&!options.isEmpty());
        start.setEnabled(state==Lifecycle.State.READY);check.setEnabled(state==Lifecycle.State.READY);stop.setEnabled(state==Lifecycle.State.RECORDING);
        export.setEnabled(!busy&&latest!=null);capabilities.setEnabled(!busy&&report!=null);
        if(busy||state==Lifecycle.State.READY)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    });}
    @Override public void modes(List<Recorder.Mode> found,String camera){runOnUiThread(()->{
        if(dead)return;options=new ArrayList<>(found);modes.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
        prepare.setEnabled(!options.isEmpty());status.setText(camera+"\n"+(options.isEmpty()?"No target mode exposed. Export report.":"Choose a mode and Prepare preview."));transform();
    });}
    @Override public void dataset(File folder){runOnUiThread(()->{latest=folder;export.setEnabled(true);});}
    @Override public void report(File file){runOnUiThread(()->{report=file;capabilities.setEnabled(true);});}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);
        if(code==1&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)recorder.discover();
        else status(Lifecycle.State.FAILED,"Camera denied. Allow Camera in Android Settings → Apps → POCO Research Recorder → Permissions.");
    }
    private void export(boolean caps){
        pendingExport=caps?report:latest;exportZip=!caps;if(pendingExport==null)return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(caps?"application/json":"application/zip")
            .putExtra(Intent.EXTRA_TITLE,caps?"POCO-capabilities.json":pendingExport.getName()+".zip");startActivityForResult(intent,2);
    }
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=2||result!=RESULT_OK||data==null||data.getData()==null)return;
        File source=pendingExport;boolean zip=exportZip;android.net.Uri destination=data.getData();
        exportThread.execute(()->{
            try(OutputStream stream=getContentResolver().openOutputStream(destination,"wt")){
                if(stream==null)throw new IOException("Cannot open destination");
                if(zip){try(ZipOutputStream archive=new ZipOutputStream(stream)){
                    File[] files=source.listFiles();if(files==null)throw new IOException("Missing dataset");
                    for(File file:files){if(!file.isFile())continue;archive.putNextEntry(new ZipEntry(source.getName()+"/"+file.getName()));copy(file,archive);archive.closeEntry();}
                }}else copy(source,stream);
                runOnUiThread(()->Toast.makeText(this,"Export saved. Check the destination file.",Toast.LENGTH_LONG).show());
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Export failed: "+e.getMessage()+". Delete any partial export and retry.",Toast.LENGTH_LONG).show());}
        });
    }
    private static void copy(File file,OutputStream out)throws IOException{try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}
    @Override protected void onResume(){super.onResume();if(recorder!=null)recorder.resume();}
    @Override protected void onPause(){if(recorder!=null)recorder.pause();super.onPause();}
    @Override protected void onDestroy(){dead=true;exportThread.shutdown();if(recorder!=null)recorder.destroy();super.onDestroy();}
}
