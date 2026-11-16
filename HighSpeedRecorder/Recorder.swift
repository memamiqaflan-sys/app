import SwiftUI
import AVFoundation
import CoreMotion

// Target: iOS 16+, Swift 5 language mode, default actor isolation Nonisolated.
// All acquisition state and file I/O belong to q. UI state belongs to main.
final class Recorder: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    @Published private(set) var status = "Preparing camera…"
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var rateSummary = "Rates appear after acquisition starts"
    @Published private(set) var lastFolderName = ""
    @Published private(set) var supportedModes: [CaptureMode] = []
    let session = AVCaptureSession()
    private var mode: CaptureMode = .fullHD240
    private var videoStats = SampleStats()
    private var accelStats = SampleStats()
    private var gyroStats = SampleStats()
    private var duration: Double? = nil
    private var requestedIMUHz = 1000.0
    private var generation = UUID()
    // Main-thread-only lifecycle state.
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let q = DispatchQueue(label: "dataset.capture", qos: .userInitiated)
    private let motion = CMMotionManager()
    private let motionQueue = OperationQueue()
    private let output = AVCaptureVideoDataOutput()
    private var camera: AVCaptureDevice?
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var logs: [String: CSV] = [:]
    private var folder: URL?
    private var active = false
    private var finishing = false
    private var origin: CMTime?
    private var lastPTS: CMTime?
    private var startHost = 0.0
    private var firstHost = 0.0
    private var lastFrameHost = 0.0
    private var lastAccelHost = 0.0
    private var lastGyroHost = 0.0
    private var frames = 0
    private var accelCount = 0
    private var gyroCount = 0
    private var drops = 0
    private var timer: DispatchSourceTimer?
    private var observers: [NSObjectProtocol] = []
    private var metadata: [String: Any] = [:]
    private var configured = false
    private var lastTimestampFailure: String?
    private static var host: Double { CMClockGetTime(CMClockGetHostTimeClock()).seconds }

    override init() {
        super.init()
        motionQueue.maxConcurrentOperationCount = 1
        for name in [AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] n in
                self?.stop(reason: n.name.rawValue)
            })
        }
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    func prepare(mode requestedMode: CaptureMode = .fullHD240) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            self.q.async {
                guard !self.active, !self.finishing else { return }
                guard granted else { self.publish("Camera permission denied"); return }
                do {
                    self.publish("Configuring camera…")
                    if !self.configured { try self.configure(); self.configured = true }
                    if self.session.isRunning { self.session.stopRunning() }
                    try self.selectFormat(requestedMode)
                    self.session.startRunning()
                    self.publish("Ready — \(self.mode.label)", ready: true)
                } catch { self.publish(error.localizedDescription) }
            }
        }
    }
    private func configure() throws {
        guard motion.isAccelerometerAvailable, motion.isGyroAvailable else { throw Failure("Motion sensors unavailable") }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw Failure("Rear wide camera unavailable") }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .inputPriority
        let captureInput = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(captureInput) else { throw Failure("Cannot add camera") }
        session.addInput(captureInput)
        camera = device
        let modes = CaptureMode.allCases.filter { requested in
            device.formats.contains { format in
                let d = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                return d.width == requested.width && d.height == requested.height &&
                    format.videoSupportedFrameRateRanges.contains {
                        $0.minFrameRate <= Double(requested.fps) && $0.maxFrameRate >= Double(requested.fps)
                    }
            }
        }
        DispatchQueue.main.async { self.supportedModes = modes }
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        output.setSampleBufferDelegate(self, queue: q)
        guard session.canAddOutput(output) else { throw Failure("Cannot add video output") }
        session.addOutput(output)
        if let c = output.connection(with: .video) {
            // Keep native sensor buffers; avoid physically rotating 240 frames/s.
            if c.isCameraIntrinsicMatrixDeliverySupported { c.isCameraIntrinsicMatrixDeliveryEnabled = true }
            if c.isVideoMirroringSupported { c.automaticallyAdjustsVideoMirroring = false; c.isVideoMirrored = false }
            if c.isVideoStabilizationSupported { c.preferredVideoStabilizationMode = .off }
        }
    }
    private func selectFormat(_ requested: CaptureMode) throws {
        guard let device = camera else { throw Failure("Camera unavailable") }
        let candidates = device.formats.filter {
            let d = CMVideoFormatDescriptionGetDimensions($0.formatDescription)
            return d.width == requested.width && d.height == requested.height &&
                $0.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= Double(requested.fps) && $0.maxFrameRate >= Double(requested.fps) }
        }
        guard let format = candidates.first(where: {
            CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        }) ?? candidates.first else {
            throw Failure("\(requested.label) is unavailable on this camera. Choose another mode; no silent fallback.")
        }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        device.activeFormat = format
        device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: requested.fps)
        device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: requested.fps)
        device.automaticallyAdjustsVideoHDREnabled = false
        if format.isVideoHDRSupported { device.isVideoHDREnabled = false }
        device.videoZoomFactor = 1
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
        if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
        if let c = output.connection(with: .video) {
            if c.isVideoStabilizationSupported { c.preferredVideoStabilizationMode = .off }
            if c.isCameraIntrinsicMatrixDeliverySupported { c.isCameraIntrinsicMatrixDeliveryEnabled = true }
        }
        mode = requested
    }
    func start(lockCamera: Bool, imuHz: Double, durationSeconds: Double? = nil) {
        guard UIApplication.shared.applicationState == .active else { return }
        q.async {
            guard self.configured, self.session.isRunning, !self.active, !self.finishing else { return }
            self.requestedIMUHz = imuHz
            self.duration = durationSeconds
            guard imuHz.isFinite, imuHz > 0,
                  durationSeconds == nil || (durationSeconds!.isFinite && durationSeconds! > 0) else {
                self.publish("Invalid recording settings", ready: true); return
            }
            do { try self.begin(lockCamera: lockCamera) }
            catch { self.cleanupFailedStart(error) }
        }
    }
    private func begin(lockCamera: Bool) throws {
        folder = nil
        metadata = [:]
        lastTimestampFailure = nil
        guard let camera = camera else { throw Failure("Camera unavailable") }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let free = try availableSpace(at: docs)
        guard free > 1_000_000_000 else { throw Failure("Keep at least 1 GB free before starting") }
        let dir = docs.appendingPathComponent("Recording-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        folder = dir
        generation = UUID()
        let take = generation
        logs = [:]
        for (name, header) in [
            ("frames", "index,pts_value,pts_timescale,host_s,video_s,callback_host_s,pts_epoch,pts_flags"),
            ("accelerometer", "timestamp_s,ax_g,ay_g,az_g,callback_host_s"),
            ("gyroscope", "timestamp_s,gx_rad_s,gy_rad_s,gz_rad_s,callback_host_s"),
            ("drops", "pts_value,pts_timescale,callback_host_s,reason"),
            ("errors", "callback_host_s,reason"),
            ("intrinsics", "frame_index,matrix_float32_column_major_base64"),
            ("telemetry", "host_s,thermal_raw,exposure_s_observed,iso_observed,lens_position_observed")
        ] { logs[name] = try CSV(dir.appendingPathComponent(name + ".csv"), header) }
        try camera.lockForConfiguration()
        if lockCamera {
            if camera.isFocusModeSupported(.locked) { camera.focusMode = .locked }
            if camera.isExposureModeSupported(.locked) { camera.exposureMode = .locked }
            if camera.isWhiteBalanceModeSupported(.locked) { camera.whiteBalanceMode = .locked }
        } else {
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
            if camera.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { camera.whiteBalanceMode = .continuousAutoWhiteBalance }
        }
        camera.unlockForConfiguration()
        let w = try AVAssetWriter(outputURL: dir.appendingPathComponent("video.mov"), fileType: .mov)
        let i = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.hevc,
            AVVideoWidthKey: mode.width, AVVideoHeightKey: mode.height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: mode.bitRate,
                AVVideoExpectedSourceFrameRateKey: mode.fps, AVVideoAllowFrameReorderingKey: false]
        ])
        i.expectsMediaDataInRealTime = true
        i.mediaTimeScale = 1_000_000_000
        guard w.canAdd(i) else { throw Failure("Cannot add writer input") }
        w.add(i)
        writer = w; input = i
        videoStats = SampleStats(); accelStats = SampleStats(); gyroStats = SampleStats()
        origin = nil; lastPTS = nil; frames = 0; drops = 0; accelCount = 0; gyroCount = 0
        firstHost = 0; lastFrameHost = 0; lastAccelHost = 0; lastGyroHost = 0
        startHost = Self.host
        metadata = ["status": "recording", "created_utc": ISO8601DateFormatter().string(from: Date()),
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "camera": camera.localizedName, "camera_id": camera.uniqueID,
            "schema_version": 3, "width": mode.width, "height": mode.height,
            "fps_requested": mode.fps, "imu_hz_requested": requestedIMUHz,
            "codec": "HEVC", "target_bitrate": mode.bitRate,
            "duration_limit_s": duration.map { $0 as Any } ?? NSNull(),
            "camera_format_subtype": CMFormatDescriptionGetMediaSubType(camera.activeFormat.formatDescription),
            "video_field_of_view_degrees": camera.activeFormat.videoFieldOfView,
            "start_host_s": startHost, "camera_lock_requested": lockCamera,
            "exposure_s_at_start": camera.exposureDuration.seconds, "iso_at_start": camera.iso,
            "lens_position_at_start": camera.lensPosition,
            "video_orientation": "native sensor buffers; no output rotation", "motion_axes": "native device axes; not rotated",
            "stabilization_requested": "off", "synchronization_accuracy": "unvalidated",
            "motion_timestamp_basis": "Core Motion seconds since boot",
            "video_timestamp_basis": "session clock converted to host clock"]
        metadata["motion_units"] = ["accelerometer": "g (includes gravity)", "gyroscope": "rad/s"]
        metadata["motion_axis_convention"] = "Apple device axes: +x right, +y toward top, +z out of screen in portrait; right-hand angular velocity"
        metadata["motion_data_kind"] = "Separate Core Motion measurements, not unprocessed hardware ADC data"
        metadata["clock_mapping"] = "CMSyncConvertTime from session.masterClock to CMClockGetHostTimeClock; Core Motion timestamps are boot-relative seconds. Physical sensor latency/offset uncalibrated."
        metadata["explicit_output_rotation_requested"] = false
        metadata["device_orientation_at_start_raw"] = UIDevice.current.orientation.rawValue
        metadata["active_min_frame_duration_s"] = camera.activeVideoMinFrameDuration.seconds
        metadata["active_max_frame_duration_s"] = camera.activeVideoMaxFrameDuration.seconds
        metadata["supported_modes"] = supportedModeLabels()
        try writeMetadata()
        active = true
        motion.accelerometerUpdateInterval = 1.0 / requestedIMUHz
        motion.gyroUpdateInterval = 1.0 / requestedIMUHz
        let ingress = MotionIngress(capacity: 256)
        let deliver: (@escaping (Recorder, Double) -> Void) -> Void = { [weak self] work in
            let arrival = Self.host
            guard ingress.acquire() else {
                if ingress.reportOverflowOnce() {
                    self?.q.async { [weak self] in
                        guard let self = self, self.active, self.generation == take else { return }
                        self.end(reason: "motion ingress backpressure: 256 pending samples")
                    }
                }
                return
            }
            self?.q.async { [weak self] in
                defer { ingress.release() }
                guard let self = self, self.active, self.generation == take else { return }
                work(self, arrival)
            }
        }
        motion.startAccelerometerUpdates(to: motionQueue) { d, e in
          deliver { recorder, arrival in
            if let e = e { recorder.end(reason: "accelerometer: \(e)"); return }
            guard let d = d else { return }
            guard d.timestamp.isFinite else { recorder.end(reason: "invalid motion timestamp"); return }
            guard d.timestamp >= recorder.startHost else { return }
            do {
                let a = d.acceleration
                guard [a.x, a.y, a.z].allSatisfy({ $0.isFinite }),
                      recorder.accelStats.add(d.timestamp, expectedHz: recorder.requestedIMUHz) else {
                    recorder.lastTimestampFailure = "invalid or nonmonotonic accelerometer sample"
                    recorder.end(reason: recorder.lastTimestampFailure!); return
                }
                try recorder.logs["accelerometer"]?.row("\(d.timestamp),\(a.x),\(a.y),\(a.z),\(arrival)")
                recorder.accelCount += 1; recorder.lastAccelHost = arrival
            } catch { recorder.end(reason: "disk: \(error)") }
          }
        }
        motion.startGyroUpdates(to: motionQueue) { d, e in
          deliver { recorder, arrival in
            if let e = e { recorder.end(reason: "gyroscope: \(e)"); return }
            guard let d = d else { return }
            guard d.timestamp.isFinite else { recorder.end(reason: "invalid motion timestamp"); return }
            guard d.timestamp >= recorder.startHost else { return }
            do {
                let r = d.rotationRate
                guard [r.x, r.y, r.z].allSatisfy({ $0.isFinite }),
                      recorder.gyroStats.add(d.timestamp, expectedHz: recorder.requestedIMUHz) else {
                    recorder.lastTimestampFailure = "invalid or nonmonotonic gyroscope sample"
                    recorder.end(reason: recorder.lastTimestampFailure!); return
                }
                try recorder.logs["gyroscope"]?.row("\(d.timestamp),\(r.x),\(r.y),\(r.z),\(arrival)")
                recorder.gyroCount += 1; recorder.lastGyroHost = arrival
            } catch { recorder.end(reason: "disk: \(error)") }
          }
        }
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in self?.tick() }
        timer = t; t.resume()
        publish("Recording…", busy: true)
    }
    private func tick() {
        guard active else { return }
        let now = Self.host
        do {
            for log in logs.values { try log.flush() }
            if ProcessInfo.processInfo.thermalState == .critical { end(reason: "critical thermal state"); return }
            if now - startHost > 5 && (now - lastFrameHost > 3 || now - lastAccelHost > 3 || now - lastGyroHost > 3) {
                end(reason: "camera or motion stream stalled"); return
            }
            if let folder = folder {
                let free = try availableSpace(at: folder)
                if let c = camera {
                    try logs["telemetry"]?.row("\(now),\(ProcessInfo.processInfo.thermalState.rawValue),\(c.exposureDuration.seconds),\(c.iso),\(c.lensPosition)")
                }
                if free < 500_000_000 { end(reason: "low storage"); return }
            }
            if let duration = duration, firstHost > 0 && now - firstHost >= duration { end(reason: "duration reached"); return }
            publishRates()
            let thermal = ProcessInfo.processInfo.thermalState == .serious ? " · HOT" : ""
            publish("Recording \(Int(now - startHost)) s · \(frames) frames · \(drops) drops\(thermal)", busy: true)
        } catch { end(reason: "disk: \(error)") }
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard active, let writer = writer, let input = input else { return }
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetWidth(pixel) == Int(mode.width), CVPixelBufferGetHeight(pixel) == Int(mode.height) else {
            end(reason: "unexpected video dimensions"); return
        }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        // masterClock is the older, iOS 16-compatible name of synchronizationClock.
        guard let clock = session.masterClock, pts.isNumeric else { end(reason: "invalid capture clock or PTS"); return }
        let host = CMSyncConvertTime(pts, from: clock, to: CMClockGetHostTimeClock()).seconds
        guard host.isFinite else { end(reason: "invalid clock conversion"); return }
        guard host >= startHost else { return } // Reject queued preview frames from before this take.
        if let previous = videoStats.last, host <= previous {
            end(reason: "nonmonotonic converted video timestamp"); return
        }
        if let previous = lastPTS, CMTimeCompare(pts, previous) <= 0 {
            lastTimestampFailure = "invalid or nonmonotonic video PTS"
            end(reason: lastTimestampFailure!); return
        }
        lastPTS = pts
        if let duration = duration, firstHost > 0 && host - firstHost >= duration { end(reason: "duration reached"); return }
        lastFrameHost = Self.host
        do {
            if origin == nil {
                guard writer.startWriting() else { throw writer.error ?? Failure("Writer failed to start") }
                writer.startSession(atSourceTime: pts)
                origin = pts; firstHost = host
                metadata["first_video_host_s"] = host
                metadata["first_video_pts_value"] = pts.value
                metadata["first_video_pts_timescale"] = pts.timescale
                metadata["first_video_pts_epoch"] = pts.epoch
                metadata["first_video_pts_flags"] = pts.flags.rawValue
                metadata["stabilization_active_raw"] = connection.activeVideoStabilizationMode.rawValue
                metadata["output_connection_orientation_raw"] = connection.videoOrientation.rawValue
                try writeMetadata()
            }
            guard input.isReadyForMoreMediaData else { try drop(pts, "writer_backpressure"); return }
            guard input.append(sampleBuffer) else { throw writer.error ?? Failure("Frame append failed") }
            try logs["frames"]?.row("\(frames),\(pts.value),\(pts.timescale),\(host),\(CMTimeSubtract(pts, origin!).seconds),\(Self.host),\(pts.epoch),\(pts.flags.rawValue)")
            if let data = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil) as? Data {
                try logs["intrinsics"]?.row("\(frames),\(data.base64EncodedString())")
            }
            videoStats.add(host, expectedHz: Double(mode.fps))
            frames += 1
        } catch { end(reason: "capture: \(error)") }
    }
    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard active else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if let clock = session.masterClock, pts.isNumeric,
           CMSyncConvertTime(pts, from: clock, to: CMClockGetHostTimeClock()).seconds < startHost { return }
        let reason = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_DroppedFrameReason, attachmentModeOut: nil).map { String(describing: $0) } ?? "capture_drop"
        do { try drop(CMSampleBufferGetPresentationTimeStamp(sampleBuffer), reason) }
        catch { end(reason: "drop log: \(error)") }
    }
    private func drop(_ pts: CMTime, _ reason: String) throws {
        drops += 1
        try logs["drops"]?.row("\(pts.value),\(pts.timescale),\(Self.host),\(reason.replacingOccurrences(of: ",", with: ";"))")
    }
    func stop(reason: String = "user stopped") { q.async { self.end(reason: reason) } }
    // Call from main when the app enters the background. This requests time to
    // finish the MOV; it does not enable background camera capture.
    func backgrounded() {
        if busy && backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Finalize dataset") { [weak self] in
                guard let self = self else { return }
                if self.backgroundTask != .invalid {
                    UIApplication.shared.endBackgroundTask(self.backgroundTask)
                    self.backgroundTask = .invalid
                }
            }
        }
        // Always enqueue Stop, including when Start is queued but the main UI
        // has not yet received its asynchronous busy publication.
        stop(reason: "app entered background")
    }
    private func publishRates() {
        let text = String(format: "Measured averages: video %.1f fps · accel %.1f Hz · gyro %.1f Hz",
                          videoStats.hz, accelStats.hz, gyroStats.hz)
        DispatchQueue.main.async { self.rateSummary = text }
    }
    private func end(reason: String) {
        guard active, !finishing else { return }
        active = false; finishing = true
        motion.stopAccelerometerUpdates(); motion.stopGyroUpdates()
        timer?.cancel(); timer = nil
        metadata["stop_reason"] = reason; metadata["stop_host_s"] = Self.host
        do { try logs["errors"]?.row("\(Self.host),\(reason.replacingOccurrences(of: ",", with: ";").replacingOccurrences(of: "\n", with: " "))") }
        catch { metadata["error_log_failure"] = error.localizedDescription }
        metadata["frames_appended"] = frames; metadata["drops"] = drops
        metadata["accelerometer_samples"] = accelCount; metadata["gyroscope_samples"] = gyroCount
        var logErrors: [String] = []
        for (name, log) in logs { do { try log.close() } catch { logErrors.append("\(name): \(error)") } }
        logs.removeAll()
        metadata["log_errors"] = logErrors
        metadata["video_stats"] = videoStats.json
        metadata["accelerometer_stats"] = accelStats.json
        metadata["gyroscope_stats"] = gyroStats.json
        publishRates()
        publish("Finalizing…", busy: true)
        guard let w = writer, w.status == .writing else {
            writer?.cancelWriting(); complete(success: false, error: writer?.error?.localizedDescription ?? "No valid video"); return
        }
        input?.markAsFinished()
        w.finishWriting { self.q.async { self.complete(success: w.status == .completed, error: w.error?.localizedDescription) } }
    }
    private func complete(success: Bool, error: String?) {
        let reason = metadata["stop_reason"] as? String ?? ""
        let writeFailure = !["user stopped", "duration reached"].contains(reason) || frames < 2 || accelCount < 2 || gyroCount < 2
        let logFailure = !(metadata["log_errors"] as? [String] ?? []).isEmpty || metadata["error_log_failure"] != nil
        metadata["status"] = success ? (writeFailure || logFailure ? "finalized_with_errors" : "finalized") : "failed"
        if success && (reason.contains("Interrupted") || reason == "app entered background") {
            metadata["status"] = "interrupted"
        }
        metadata["writer_error"] = error ?? ""
        metadata["completion_clean"] = success && !writeFailure && !logFailure
        var message = success && !writeFailure && !logFailure ? "Saved \(frames) frames. Stop: \(metadata["stop_reason"] ?? "")" : "Recording incomplete: \(error ?? reason)"
        if let errors = metadata["log_errors"] as? [String], !errors.isEmpty { message += " — CSV errors; see manifest" }
        do { try writeMetadata() } catch { message = "Recording incomplete — manifest write failed: \(error)" }
        let folderName = folder?.lastPathComponent ?? ""
        DispatchQueue.main.async { self.lastFolderName = folderName }
        writer = nil; input = nil; finishing = false
        publish(message, ready: session.isRunning)
    }
    private func cleanupFailedStart(_ error: Error) {
        if active { end(reason: "start: \(error)"); return }
        for log in logs.values { try? log.close() }
        logs.removeAll(); writer?.cancelWriting(); writer = nil; input = nil
        if folder != nil {
            metadata = ["status": "failed_start", "error": error.localizedDescription]
            try? writeMetadata()
        }
        publish("Start failed: \(error.localizedDescription)", ready: configured && session.isRunning)
    }
    private func availableSpace(at url: URL) throws -> Int64 {
        var fresh = URL(fileURLWithPath: url.path, isDirectory: true)
        fresh.removeAllCachedResourceValues()
        guard let bytes = try fresh.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage else {
            throw Failure("Unable to read available storage")
        }
        return bytes
    }
    private func supportedModeLabels() -> [String] {
        guard let camera = camera else { return [] }
        return CaptureMode.allCases.filter { requested in
            camera.formats.contains { format in
                let d = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                return d.width == requested.width && d.height == requested.height &&
                    format.videoSupportedFrameRateRanges.contains {
                        $0.minFrameRate <= Double(requested.fps) && $0.maxFrameRate >= Double(requested.fps)
                    }
            }
        }.map { $0.label }
    }
    private func writeMetadata() throws {
        guard let folder = folder else { return }
        try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys]).write(to: folder.appendingPathComponent("manifest.json"), options: .atomic)
    }
    private func publish(_ text: String, ready: Bool = false, busy: Bool = false) {
        DispatchQueue.main.async {
            self.status = text; self.ready = ready; self.busy = busy
            UIApplication.shared.isIdleTimerDisabled = busy
            if !busy && self.backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = .invalid
            }
        }
    }
}

private struct Failure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
private final class CSV {
    private let handle: FileHandle
    private var buffer = Data()
    init(_ url: URL, _ header: String) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw Failure("Cannot create \(url.lastPathComponent)") }
        handle = try FileHandle(forWritingTo: url)
        try row(header)
    }
    func row(_ text: String) throws {
        buffer.append(contentsOf: (text + "\n").utf8)
        if buffer.count >= 32_768 { try flush() }
    }
    func flush() throws {
        guard !buffer.isEmpty else { return }
        try handle.write(contentsOf: buffer); buffer.removeAll(keepingCapacity: true)
    }
    func close() throws {
        do { try flush(); try handle.synchronize() }
        catch { try? handle.close(); throw error }
        try handle.close()
    }
}

// This gate bounds app-owned pending sample blocks. Core Motion manages its own
// delivery queue; timestamp gaps must still be inspected for upstream losses.
private final class MotionIngress {
    private let slots: DispatchSemaphore
    private let lock = NSLock()
    private var overflowReported = false
    init(capacity: Int) { slots = DispatchSemaphore(value: capacity) }
    func acquire() -> Bool { slots.wait(timeout: .now()) == .success }
    func release() { slots.signal() }
    func reportOverflowOnce() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if overflowReported { return false }
        overflowReported = true; return true
    }
}
