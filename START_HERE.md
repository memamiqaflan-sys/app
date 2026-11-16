# Windows-only setup — no personal access to Xcode required

The recorder remains a native iPhone app. GitHub's hosted Mac compiles it automatically; you control the process from a Windows browser. A Windows signing tool then installs the compiled app on your iPhone. You do not need to borrow a Mac, use Xcode's interface, rent a remote desktop, or purchase a developer membership for the free-account sideloading route.

The repository now contains the actual source files. The original ZIP is retained as a backup; the build no longer extracts it. The output is an **unsigned IPA**. Windows signing and phone testing are separate steps.

## Download the IPA

Open the successful Actions run linked in the handoff. Under **Artifacts**, download **HighSpeedRecorder-iPhone-IPA** and extract the artifact ZIP. Use the inner **HighSpeedRecorder.ipa** in Sideloadly, not the ZIP. `SHA256.txt` contains its checksum. Artifacts are retained for seven days.

To rebuild later, open this repository's **Actions → Build iPhone IPA → Run workflow**, choose `codex/recorder-build-repair` (or `main` after merging), and click **Run workflow**. Wait for a green result before downloading. A queued macOS job is not a compiler failure. `ios-build-log` contains the compiler output.

The workflow builds for physical iPhoneOS, checks the arm64 executable, and packages the app without Apple credentials. Standard GitHub-hosted runners in public repositories have free compute usage. Private repositories consume plan allowances and can incur charges beyond them; larger runners are billed even in public repositories. Artifact storage has separate plan allowances. This workflow selects a standard runner, keeps artifacts for seven days, and does not purchase services. See the current [GitHub billing documentation](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions).

## Part 2 — sign and install from Windows

1. Download the current Windows version of **Sideloadly** from https://sideloadly.io/ . Follow its official Windows setup instructions, including required Apple device drivers/iTunes/iCloud components. Use its official download links for prerequisites.
2. Connect your iPhone by USB, unlock it and tap **Trust This Computer** when asked.
3. Open Sideloadly. Confirm your iPhone appears as the target device.
4. Drag **HighSpeedRecorder.ipa** into Sideloadly or use its IPA file selector.
5. Choose the normal Apple Account signing/install mode. Enter your Apple Account in the local tool and complete its sign-in/2FA prompts. No Apple password or verification code belongs in GitHub, the source files, or chat.
6. Press **Start** and wait for signing and installation. Keep the default app identity stable for later updates; if you choose a custom bundle ID, reuse it on subsequent installs.
7. If iOS reports an untrusted developer, open **Settings → General → VPN & Device Management**, select the relevant developer entry and trust it.
8. Enable **Settings → Privacy & Security → Developer Mode** if prompted, restart and confirm as instructed.
9. Open **HighSpeedRecorder** from your Home Screen. Grant camera/motion access when requested.

A free Apple account normally allows seven days before the app must be refreshed/re-signed and limits active sideloaded apps. Sideloadly supports refresh while the Windows computer can reach your phone. Back up recordings before reinstalling; keep the same Apple Account and bundle ID to update the same app. Apple authentication/tool compatibility can change, so use the current official release and troubleshoot any specific signing error with its official FAQ.

Alternative: **AltStore Classic + AltServer for Windows** can also sign/install IPA files. Follow https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows . Use Classic, not AltStore PAL, for arbitrary IPA sideloading. Its free-account refresh requirements also apply.

## Part 3 — record your data

1. Hold the phone in the supported landscape orientation. Select **1080p · 240 fps**. If the camera format is unavailable, choose 120 or 60 fps; the app does not silently downgrade.
2. Leave **Maximum-rate probe (1000 Hz request)** selected initially. This requests a high sensor rate; it does NOT promise 1000 Hz. Read the actual measured accelerometer/gyro rates shown on screen.
3. Point the camera at your room/ArUco markers. Use bright, stable lighting. Wait for focus/exposure to settle, then leave the camera-lock toggle enabled if appropriate. Reselect a video mode to restore autofocus between takes.
4. Press **10-second recording check**. This saves an actual dataset. Inspect the video, measured rates, and drop count before longer recording.
5. For your experiment leave **Optional time limit OFF** and press **Start**. Press **Stop & Save** whenever you choose. Wait for saving to finish. No fixed 30-minute limit is imposed.
6. Keep the app in the foreground; backgrounding/interruption stops the take. Storage, thermal, and recording failures can also stop it, with the reason recorded.

At the 240 fps target bitrate, video is approximately 375 MB/minute plus overhead. Actual size varies. The app requires >1 GB available to start and stops below 500 MB; that admission threshold is not a guarantee of enough space for your intended duration.

## Part 4 — transfer and verify on Windows

On iPhone open **Files → On My iPhone → HighSpeedRecorder**. Each `Recording-UUID` folder is one dataset. Long-press → Compress, then copy the ZIP using your preferred file transfer method. Apple Devices file sharing on Windows is another option; do not replace working sideloading prerequisites just to use it.

Transfer the entire folder, not only the video. Preserve original files and timestamps. On Windows with Python installed, open PowerShell in the extracted source project folder:

```powershell
py tools\validate_dataset.py "C:\Data\Recording-YOUR-ID"
```

If ffprobe is installed and on PATH, additionally verify decoded frame count:

```powershell
py tools\validate_dataset.py "C:\Data\Recording-YOUR-ID" --decode-video
```

The recorder and validator do not reconstruct motion or correct sensor drift.

## Troubleshooting

- **Actions workflow absent:** select the repair branch; merge the pull request to make tracked-source builds available on main.
- **Source missing:** use a complete checkout of the repair branch; this workflow builds tracked source and does not extract the archive.
- **Cloud compile fails:** inspect the first compiler error in Build unsigned physical-iPhone app. The log artifact is retained when possible.
- **Sideloadly cannot see iPhone:** verify USB trust and follow its official device-driver troubleshooting. Use an actual data cable.
- **App will not open after a week:** refresh its signature; no source rebuild is normally necessary.
- **Sensor rate is lower than requested:** the delivered rate is hardware/iOS limited; use the measured value. Do not interpret interpolation as extra measurements.
- **Video drops or low fps:** inspect lighting/thermal state, then compare a 120 fps take. No performance guarantee is claimed before on-phone testing.

## Primary references

- GitHub macOS runners: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- GitHub usage/billing: https://docs.github.com/en/actions/concepts/billing-and-usage
- Sideloadly: https://sideloadly.io/
- Sideloadly account, refresh, and Windows FAQ: https://sideloadly.io/faq
- AltStore Windows setup: https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows
- AltStore refresh limits: https://faq.altstore.io/altstore-classic/your-altstore

## First-recording acceptance check

1. Run the 10-second check after preview focus/exposure settle. Note requested mode, measured video fps, accelerometer Hz, gyroscope Hz, and logged drops. A 1000 Hz request is not a promise of delivery.
2. Wait for Saved. Export the whole folder. Check `manifest.json`: status `finalized`, `completion_clean: true`, no sidecar errors, and the expected stop reason. Completeness does not imply zero timestamp gaps or physical synchronization accuracy.
3. Play `video.mov` on Windows. Check duration is about ten real seconds, orientation, visibility of markers, and image sharpness.
4. Run the Python validator, preferably with `--decode-video`. Send its JSON report and the manifest (no credentials). These checks detect ordering/count/gap problems; they do not calibrate camera–IMU latency.
5. Repeat a manual Start/Stop take, then test a longer take while watching temperature and drops. Keep the app foregrounded. Backgrounding/interruption stops acquisition; an interrupted container can still contain useful partial data, but its dataset is marked incomplete.

Official Windows and signing guidance checked on 2026-11-16: [Sideloadly](https://sideloadly.io/) and its [FAQ](https://sideloadly.io/faq). Free-account app signatures expire after seven days and must be refreshed. Enable the local auto-refresh daemon if desired; the Windows computer must be able to reach the phone. Enter Apple credentials only into the local signing tool. No paid developer membership is required for this route. Back up recordings before reinstalling, and keep the same bundle ID/account for updates.
