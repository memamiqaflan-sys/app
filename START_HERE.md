# Windows-only setup — no personal access to Xcode required

The recorder remains a native iPhone app. GitHub's hosted Mac compiles it automatically; you control the process from a Windows browser. A Windows signing tool then installs the compiled app on your iPhone. You do not need to borrow a Mac, use Xcode's interface, rent a remote desktop, or purchase a developer membership for the free-account sideloading route.

This ZIP contains source and a cloud build workflow, NOT an already compiled IPA. The workflow has not been run on your behalf. Its first run is the actual iOS compilation check. Signing/installation and 240 fps recording still need verification on your phone.

## What you need

- Windows PC, iPhone 13 Pro, USB cable, internet.
- GitHub account for the cloud build.
- Apple Account for personal signing through a Windows sideloading tool.
- Sideloadly from https://sideloadly.io/ (or AltStore Classic as an alternative).

GitHub's hosted macOS build uses your account's Actions allowance. Standard runners are free for public repositories; private repositories use included compute allowance and may incur charges beyond it. A private repository keeps your source private. The workflow does not request Apple credentials or use GitHub secrets.

## Part 1 — create the IPA using your browser

1. Create a GitHub account at https://github.com if needed.
2. Click **New repository**. Name it `iphone-motion-recorder`. Choose Private if you prefer; initialize it with a README so it has a default branch.
3. Keep the downloaded **HighSpeedRecorder_iPhone13Pro.zip** intact. In the repository choose **Add file → Upload files**, upload that ZIP to the repository root, and commit it. Do NOT upload it inside a folder or change its filename.
4. Extract a second copy of the ZIP locally on Windows. Inside the extracted HighSpeedRecorder folder, open **BUILD_ON_GITHUB.yml** using Notepad. Select all and copy its contents.
5. Back in the GitHub repository choose **Add file → Create new file**. Enter this exact filename:

   `.github/workflows/build.yml`

6. Paste the copied YAML contents and commit. You have now supplied the two required items: the project ZIP and the workflow file.
7. Click the repository's **Actions** tab. Enable workflows if GitHub asks. Select **Build iPhone IPA → Run workflow → Run workflow** on the default branch.
8. Wait for the run to finish. It extracts the ZIP, tests the data validator, compiles the iPhone app on GitHub's Mac, checks the executable platform/architecture, and packages an unsigned IPA.
9. Open the completed run. Under **Artifacts**, download **HighSpeedRecorder-iPhone-IPA**. Extract that downloaded artifact ZIP. Inside you will find:
   - `HighSpeedRecorder.ipa`
   - `SHA256.txt`

The IPA is the file you install in Part 2. Do not give Sideloadly the source ZIP or the outer GitHub artifact ZIP.

If the run is red, open the failed step and copy the FIRST compiler error, or download `ios-build-log`. No usable IPA is claimed until the cloud build succeeds. The workflow accepts a directly uploaded source tree too, but the ZIP route above avoids hidden-folder/upload problems on Windows.

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

- **Actions workflow absent:** create `.github/workflows/build.yml` on the default branch using the contents of BUILD_ON_GITHUB.yml.
- **Project ZIP not found:** upload the unchanged source ZIP to the repository root under the exact name HighSpeedRecorder_iPhone13Pro.zip.
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
