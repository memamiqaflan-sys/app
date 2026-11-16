# HighSpeedRecorder — iPhone 13 Pro

Read **START_HERE.md** for the Windows-only workflow: GitHub cloud build → unsigned IPA → Windows signing/install. You do not need personal access to Xcode or a Mac.

A native SwiftUI / AVFoundation / Core Motion recording implementation. Records the phone's rear camera viewing the room and independent accelerometer + gyro streams simultaneously. No motion reconstruction, filtering, drift correction, ARKit tracking, or marker detection is performed. ArUco markers are recorded as ordinary video image content for later PC analysis.

## Implemented

- Ready-to-open Xcode project and shared scheme; iOS 16+, Swift 5, no external dependencies.
- Physical rear wide camera; fixed lens selection and zoom 1×.
- 1080p240 default, 1080p120, 1080p60, 4K60; runtime supported-mode filtering and explicit failure if unsupported. No silent downgrade.
- HEVC MOV with original capture-time progression, frame reordering disabled. Native sensor buffers are not rotated; output dimensions checked on each frame.
- Manual Start / Stop, optional duration in seconds, and separate 10-second test take.
- Independent accelerometer and gyro callbacks; default 1000 Hz request used to probe the available rate, plus lower request options. Hardware/iOS may cap the delivered rate. No fabricated or interpolated sensor samples.
- Measured average rates from measurement timestamps, not callback timing.
- Original rational video PTS plus session-clock → host-clock conversion. Core Motion measurement timestamps and callback-receipt timestamps both retained.
- Per-take UUID; buffered CSV writes; periodic flush; no full-session frame/sample arrays. At most 256 app-owned pending motion samples; overflow stops the take with an error.
- Video drops and writer backpressure logged; file I/O errors propagated. File status distinguishes incomplete writes from finalized data.
- Optional focus/exposure/white-balance lock; HDR off; video stabilization requested off and reported active mode recorded.
- Start-of-take camera metadata, periodic thermal/exposure/ISO/focus observations, and optional per-frame camera intrinsics attachments.
- Storage/critical thermal/stream-stall checks, capture interruption handling, background finalization request, auto-lock disabled during acquisition/finalization.
- Files app / Apple Devices export, Python validator with eight automated tests, and GitHub macOS build-and-IPA-packaging workflow.

## Output

| File | Contents |
| --- | --- |
| video.mov | HEVC captured video; no audio |
| frames.csv | Appended frame index, original PTS numerator/denominator, converted host seconds, relative movie seconds, callback host seconds |
| accelerometer.csv | Independent measurement timestamp, ax/ay/az in g, callback host seconds |
| gyroscope.csv | Independent measurement timestamp, gx/gy/gz in rad/s, callback host seconds |
| errors.csv | Stop/error event, with callback host time; failed/interrupt status also preserved in manifest |
| drops.csv | Original dropped PTS, callback host time, reason; includes writer backpressure |
| intrinsics.csv | Frame index and Base64 bytes of Apple's intrinsic-matrix attachment, only when supplied |
| telemetry.csv | Once-per-second host time, thermal state, observed exposure/ISO/focus properties |
| manifest.json | Requested configuration, time origins, counts, measured-rate summaries, stop reason, writer and log status |

`intrinsics.csv` can contain only a header on unsupported configurations. The attachment is Apple's native `matrix_float3x3` memory layout, including SIMD padding; do not assume a tightly packed array of nine floats. Retain it as supplementary metadata and calibrate your actual video mode separately for analysis. Telemetry exposure/ISO/focus readings are observations of device properties, NOT exact exposure metadata for individual frames.

The manifest's first_video_* fields identify the writer session origin (first delivered frame), which may precede the first successfully appended frame during backpressure. `video_s` is relative to that exact rational origin. `frames.csv` records only successful appends; verify the finalized decoded movie against it with `--decode-video`.

## Settings and capture precautions

| Mode | Target bitrate | Approximate video MB/minute |
| --- | --- | --- |
| 1080p240 | 50 Mbps | 375 |
| 1080p120 | 30 Mbps | 225 |
| 1080p60 | 20 Mbps | 150 |
| 4K60 | 60 Mbps | 450 |

These are encoding targets, not guarantees. Bright, stable lighting is needed for short exposures and sharp markers. Let autofocus/exposure settle before locking. Hold one landscape orientation throughout the take. The preview is fixed to landscapeRight and the UI supports that orientation only; output remains the native sensor orientation.

The iPhone 13 Pro wide camera has sensor-shift optical stabilization. Requesting video stabilization off is not a certification that all optical motion/image processing is removed. Reported active mode is retained; empirical validation remains necessary.

The app does not hard-code a 30-minute maximum. Manual mode lasts until Stop or an error/device limit. It checks for >1 GB available before Start and stops below 500 MB. Keep substantially more free storage for long high-rate takes. Serious thermal state is shown as HOT; critical state stops acquisition. The app never silently changes fps to manage heat.

All acquisition state and CSV I/O run on a serial queue. Motion delivery callbacks run on a separate serial OperationQueue and record arrival time before admission to that queue. A semaphore bounds pending app-owned sample blocks to 256; the first rejection queues a terminal backpressure error. AVFoundation discards late frames rather than accumulating image arrays, and rejected frames are counted. Core Motion can temporarily queue callbacks when the app is busy. Core Motion manages its internal delivery queue; no zero-loss guarantee is made for upstream delivery. Inspect actual timestamp gaps. CSV buffers are limited by flush thresholds, and write exceptions stop the take.

At Stop, callbacks not yet accepted are ignored; a boundary sensor sample can be absent. The validator reports whether sensor timestamps fully bracket video. Preserve original records and use the common interval in later analysis. Native Core Motion axes are not rotated into camera coordinates.

Backgrounding ends the take and requests limited background execution for finalization. iOS termination, power loss, or exhausted background execution can still leave an unfinished MOV. No automatic resume, segmentation, or crash-recovery guarantee is provided. Existing datasets are never deleted by the app.

## Build notes

- For Windows, use .github/workflows/build.yml and START_HERE.md. GitHub runs the iOS compiler remotely; Windows Sideloadly signs the resulting IPA. If you later obtain a Mac, the Xcode project also supports normal direct builds.
- Swift 5 language mode, minimal strict concurrency, nonisolated default. Shared acquisition state is serialized explicitly; UI publication is dispatched to main.
- `AVCaptureSession.masterClock` and `videoOrientation` are retained for iOS 16 compatibility. Newer SDKs may show deprecation warnings; the explicit clock conversion is intentional.
- The privacy manifest declares disk-space checks for write admission/low-storage stopping. Raw available-capacity numbers are not exported.
- No background-camera entitlement, microphone access, third-party SDK, or cloud service is used.
- The bundled GitHub workflow produces an unsigned physical-device IPA; it has no Apple credentials and does not publish or install the app. Sign/install the IPA using the Windows route in START_HERE.md.

## Verification status

The initial repaired branch passed a real GitHub Actions iPhoneOS SDK Release build and unsigned IPA packaging in [run 43914464969](https://github.com/memamiqaflan-sys/app/actions/runs/43914464969). The original failure was an incorrect `UUID().uuidString()` call; Swift's `uuidString` is a property. Check the newest successful run on this branch for the current revision's artifact.

The workflow also compiles and runs Swift timestamp-statistics checks and the Python validator tests. These exercise invalid timestamps, observed rates, gaps, counts, missing files, and incomplete recordings. They do not verify camera or sensor performance.

Phone-only checks remain: signing/installing, camera/motion authorization, sustained 240 fps encoding, measured maximum IMU rates, orientation/preview agreement, focus/exposure settling, physical synchronization accuracy, thermal endurance, interruptions/backgrounding, repeated takes, and Windows export. Use the checklist in START_HERE.md.

No signed app or physical-phone performance result is claimed. An unsigned IPA requires Windows signing before installation. `completion_clean` describes successful file finalization, not proof of zero lost samples. Interrupted, stalled, low-storage, thermal, sensor, encoder, and disk errors never receive a clean completion status.

## Technical references

- iPhone 13 Pro specifications: https://support.apple.com/en-us/111871
- Format selection: https://developer.apple.com/documentation/avfoundation/capture-device-formats
- High-frame-rate / HEVC guidance: https://developer.apple.com/library/archive/documentation/DeviceInformation/Reference/iOSDeviceCompatibility/Cameras/Cameras.html
- Capture clock: https://developer.apple.com/documentation/avfoundation/avcapturesession/synchronizationclock
- Motion timestamp: https://developer.apple.com/documentation/coremotion/cmlogitem/timestamp
- Actual motion intervals: https://developer.apple.com/documentation/coremotion/cmmotionmanager/accelerometerupdateinterval
