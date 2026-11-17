# POCO Research Recorder — Android

Native Camera2 + MediaCodec + SensorManager app for POCO X3 NFC / Android 10 or newer. It records video, independent accelerometer and gyroscope measurements, and the timing/metadata required for later Windows/Python/MATLAB analysis. No ARKit, SLAM, sensor fusion, gravity subtraction, interpolation, filtering, drift correction or live marker detection is implemented.

## Install wirelessly

1. Open the successful **Build POCO Android APK** run on your phone or Windows browser. Download **PocoRecorder-APK** under Artifacts while signed into GitHub. Extract its ZIP to get **PocoRecorder.apk**. The artifact is retained for 14 days.
2. Copy/download the APK onto the phone. Tap it in Files/File Manager. If asked, allow **Install unknown apps / Allow from this source** for the browser or file manager you are using. MIUI may place this under Settings → Privacy protection → Special permissions. No root, developer account, developer mode, USB debugging or cable is needed.
3. Install **POCO Research Recorder** and allow Camera. No microphone, internet or storage-wide permission is requested. An app running without the camera permission gives an explicit permission message.
4. Future workflow builds use a generated signing identity kept in repository Actions secrets. The key/password are not in source or logs. Install updates over this same app to preserve datasets; do not uninstall it before exporting your data. Losing/replacing that signing key can require uninstalling first. No seven-day Apple-style refresh is needed.

This is a personal research/test APK, not a Play Store release. Installation and camera performance still require the actual phone. The app does not upload data.

## First phone check

1. Open the app. Its list includes only target modes Android advertises for the selected physical, non-logical rear camera. It prefers the largest exposed rear sensor. Read the mode label; no silent resolution/fps fallback is performed.
2. **Export camera / sensor report** first. Save `POCO-capabilities.json` to Downloads or a cloud-drive location. This report is the result to send back if 120/240 fps modes are absent or rejected.
3. Select **1920×1080 · 120 fps** if offered. Tap **Prepare preview**. If high-speed capture is not exposed by the firmware, try an offered 60/30 fps mode for the initial data-integrity check. The stock Camera app's slow-motion support does not guarantee Camera2 access for another app.
4. Let focus and exposure settle. Enable the optional lock request if desired. High-speed sessions can override AF/AE/AWB controls; actual reported settings are in `capture_results.csv`. Stabilization is requested off, including OIS if supported, but this is not proof that every internal processing stage is disabled.
5. Tap **10-second recording check**. Keep the app in the foreground. Note **measured** video fps, accelerometer Hz and gyro Hz. Fastest sensor delivery is requested independently, not guaranteed. A lower measured rate is not replaced with invented samples.
6. Wait for **SAVED**, then **Export selected take ZIP**. Choose Downloads or a cloud drive. Incomplete/interrupted takes can also be exported; they must not be treated as complete datasets. Large ZIP exports stream from disk; they can take time.
7. Copy/download the ZIP on Windows and extract it. Play `video.mp4`: check duration, orientation, marker visibility and sharpness. The movie runs on the original capture timeline, not slow-motion playback. Rotation is an MP4 orientation hint; pixel buffers are not rotated/copied in the app.
8. Run the validator below. Send back the manifest, validator JSON report, and capability report. These contain device/camera descriptions, not passwords. Do not send private room footage unless you want to share it.
9. Test manual Start/Stop and a second take, then a longer recording. Monitor heat, actual rates and gaps. Foreground-only capture stops on backgrounding/interruption; background finalization is best effort under Android process limits. Power loss/forced termination can leave an incomplete MP4; crash recovery is not promised.

## Dataset

The saved-take selector restores all take folders after reopening the app. Choose a take, then Export selected take ZIP. Acquisition is disabled and the screen is kept awake during export; stay in the app until confirmation.

Each take is `Recording-UUID`, stored in app-specific external storage and exported intact as a ZIP. App uninstall deletes the app's local data. No deletion feature is provided; use Android's app storage controls only after exporting.

| File | Meaning |
| --- | --- |
| video.mp4 | Hardware HEVC when reported supported, otherwise hardware AVC; actual codec recorded. No audio. |
| frames.csv | Encoded frame index, original MediaCodec PTS in microseconds, MP4 PTS relative to first encoded keyframe, callback elapsed ns, encoded bytes, flags. |
| accelerometer.csv | Original measurement ns, x/y/z m/s² including gravity, separate callback-arrival ns, optional bias estimates. |
| gyroscope.csv | Original measurement ns, x/y/z rad/s, separate callback-arrival ns, optional bias estimates. |
| capture_results.csv | Original camera frame numbers / SENSOR_TIMESTAMP ns, callback elapsed ns, reported exposure, ISO, focus, stabilization, AF/AE/AWB settings. High-speed metadata can be sparse/batched; row indices are not codec frame indices. |
| intrinsics.csv | Camera-provided `[fx; fy; cx; cy; skew]` where available. Supplemental metadata, not a complete calibration. Header-only is allowed. |
| events.csv | Capture failures, explicit pre-keyframe/pre-start skips, stop/error events. |
| telemetry.csv | Once-per-second elapsed ns / Android thermal status. Short takes may contain only its header. |
| manifest.json | Requested settings, clock conventions, actual encoder, sensor identity/types/units, counts/rates/gaps, errors, finalization status and stop reason. |

Uncalibrated accelerometer/gyro variants are preferred when available. Their first three components are saved without subtracting reported bias; bias estimates are retained in separate columns. If unavailable, the standard OS sensor is used and its actual sensor type recorded. Neither API is unprocessed hardware ADC data. Native axes: x right, y toward top, z out of the screen in natural portrait, right-handed; not rotated into camera coordinates.

## Timing and integrity

SensorEvent timestamps and callback arrival times are separate. Android sensor timestamps and `elapsedRealtimeNanos()` are ns since boot including sleep. For camera timestamp source **REALTIME**, camera SENSOR_TIMESTAMP uses that same timebase. For **UNKNOWN**, there is no guaranteed camera/IMU clock mapping; preserve data and perform temporal calibration. The manifest exports the source.

Direct Camera2 → encoder Surface input is expected to carry camera timestamps into encoder PTS quantized to microseconds. Both original streams are preserved so this relationship can be checked. Never align by callback receipt, by CSV row number, or by assuming equal sensor/video rates. MP4 PTS is explicitly `encoded_pts_us - first_encoded_pts_us`; no slowed playback. Physical camera/IMU synchronization accuracy and sensor latency remain uncalibrated, regardless of software clock consistency.

All acquisition lifecycle state, codec callbacks and writes run on a serial handler. Sensor ingress has its own delivery thread and a nonblocking 512-slot gate; exhaustion stops the take with an error. Each session has a generation token so old callbacks cannot become a new recording. Codec/camera queues are OS-managed; no zero-drop claim is made. Encoded timestamp gaps and camera-reported failures are retained. Sparse high-speed capture metadata is not proof of full per-frame sensor/exposure measurements.

App CSV buffers are 32 KiB per file. There are no whole-take frame/sample arrays. Disk is flushed periodically; sidecars and video are synced before successful manifest finalization. Close/sync/encoder failures prevent a clean dataset status, even if the MP4 container closes successfully. Require at least 1 GB free to start; stop below 500 MB, at critical thermal status, or after a stream stall. Serious thermal pressure is shown in the UI. These thresholds do not guarantee a long recording will fit or remain cool.

Default duration is manual Start/Stop. The check uses the same encoder/acquisition pipeline with a ten-second limit from Start. A short keyframe wait may make video slightly shorter than sensor coverage. Stop drains the codec through EOS, with a six-second timeout; sensors stop accepting samples at the stop boundary, so tail video may extend beyond the last IMU sample. Use the common coverage interval in later analysis. Screen auto-lock is disabled while the preview/acquisition/finalization is active and restored afterwards.

## Windows validator

In PowerShell, from this repository's `android` folder:

```powershell
py tools\validate_dataset.py "C:\Data\Recording-YOUR-ID"
py tools\validate_dataset.py "C:\Data\Recording-YOUR-ID" --decode-video
```

The second command requires ffprobe on PATH. It also checks decoded frame count and video dimensions. The validator never alters recordings or performs motion analysis. It checks completeness flags/files, counts, measurement ordering, observed rates/gaps, and frame/MP4 timestamp mapping. Camera-clock warnings require attention rather than automatic correction. This Android dataset schema is separate from the iOS validator.

## Build

GitHub Actions `.github/workflows/android.yml` runs Android SDK compilation, Java unit tests, lint, Python validator tests, APK assembly and `apksigner verify`. It publishes `PocoRecorder-APK` (APK/checksum) and `android-build-report` (compiler/test/lint reports). Standard public-repository runner compute is free; artifact storage has plan allowances. No paid service is purchased.

Locally on Windows with Java 17+ and Android SDK 35: run `gradlew.bat testDebugUnitTest lintDebug assembleDebug` in the android directory, or open it in Android Studio. The cloud build requires no local development setup. Local builds without repository signing secrets use Android's local debug signing identity; this can differ from the cloud key.

The wrapper is pinned to Gradle 8.9 with a distribution checksum. Android Gradle Plugin 8.7.3, compile/target SDK 35, minimum Android 10; no third-party runtime SDKs. JUnit is test-only. There is no background camera service, network permission, account requirement or telemetry SDK.

## Phone-only verification still required

Actual POCO X3 NFC firmware-exposed modes, high-speed session/encoder compatibility, sustained rates, preview/video orientation, AF/AE/AWB behavior, thermal endurance, actual timestamp relationship, interruption/EOS handling, repeated takes and ZIP transfer. Compilation and synthetic tests cannot establish these properties.

References: [POCO X3 NFC specifications](https://www.po.co/global/poco-x3-nfc/specs/), [Camera2 high-speed sessions](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession), [sensor timestamp](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp), [camera timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE), [sensor rate limits](https://developer.android.com/guide/topics/sensors/sensors_overview#sensors-rate-limited).
