# AmpyDeskGPT — AmpybiOS Desktop Edition 0.1

**Initial Android Studio source and Windows portable bridge. CI build and real Pixel testing required.**

This is an Android-hosted desktop UI served to a PC browser, not a replacement for Android or Windows itself.

## Included

- Animated AmpybiOS desktop in the PC browser: taskbar, app drawer, movable/resizable wobbly windows, organic glass, neon cyan/gold, violet and monochrome themes, blur and animation customization, fullscreen.
- Real boot statuses are requested from Android via the local API. No simulated process success.
- Android app listing and launch requests (subject to Android background activity restrictions).
- PC to Pixel uploads and downloads, with drag-and-drop. Isolated app-owned Transfer directory, maximum 100 MB per file.
- Optional Accessibility service: screen snapshots and short touch injection with mouse or keyboard keymaps; Home, Back, Recents and Notifications.
- Optional Device Owner receiver with active state shown on phone and PC.
- Foreground LAN server and CPU/Wi-Fi wake locks. Browser desktop and transfer may continue while phone display sleeps, but Android real-screen screenshots/touch require the phone display ON and unlocked.
- An optional Windows 11 x64 portable self-contained AmpyDeskBridge.exe. The PC user runs it manually and pastes the phone pairing URL; it opens an Edge app-style window with Android-chosen Windowed or Fullscreen mode.
- GitHub Actions compiles Windows helper on Windows, bundles it into Android APK assets, compiles APK on Linux, and publishes both artifacts.

## Setup

1. Open GitHub Actions for this repository. Download the successful AmpyDeskGPT-debug-APK artifact (install app-debug.apk on your Pixel 7 Pro).
2. On Pixel, open AmpyDeskGPT and press AVVIA DESKTOP / SERVER.
3. Connect phone and PC to the same trusted Wi-Fi. Alternatively enable Android USB tethering, which exposes a network adapter to Windows on compatible connections.
4. Copy the **entire** URL shown on the phone (including the secret t parameter) into Edge or Chrome on the PC. No PC application installation is required to use browser mode.
5. File Transfer: drop files from Windows into the transfer pane, or download phone vault files to your PC.
6. Android Remote: optionally enable Android Accessibility for AmpyDeskGPT. Unlock the Pixel and keep its display on. Click the screenshot to tap Android, or use the remappable keyboard keys.
7. In Customization select Windowed/Fullscreen mode and optionally download the Windows Bridge EXE on PC. Run it manually, paste the paired URL and press START.

**A Windows EXE cannot legitimately transfer from a phone and silently run in PC memory without explicit user execution or prior trusted PC software.** This design has no Windows installation and no permanent service; the Windows EXE requires a manual first launch and is not a full Windows automation agent.

## Device Owner

Device Owner cannot be requested with a normal permission pop-up or silently made active by an APK. An eligible device needs provisioning, often a reset if already configured and containing accounts. BACK UP FIRST; do not reset a working Pixel for this prototype. ADB on a properly eligible, provisionable device may use:

    adb shell dpm set-device-owner io.ampy.deskgpt/.AdminReceiver

The OS may reject this operation. The app does not require Device Owner for desktop, file transfer or Accessibility remote.

## Limitations

- Wi-Fi local network supported. USB works through USB tethering, **not** by merely connecting a USB cable. Bluetooth remote link is not implemented in 0.1.
- No actual Android screen mirroring/control while screen physically off or locked. No root or bypass of secure Android restrictions.
- No 60 FPS live video, no continuous joystick holds; keymap generates short Android gestures only.
- Apps are launched on Android; third-party Android APK windows cannot be arbitrarily reparented into the browser windows.
- Windows Bridge v0.1 is a user-approved shell launcher, **not yet a Windows native file/process controller**.
- All LAN traffic is HTTP, not encrypted. The shared secret URL is required for each request, but network eavesdropping remains possible. Do not use public Wi-Fi or forward port 8777. Future rev: TLS pairing, device access approvals, token rotation, and file access via Storage Access Framework.
- Large EXE is embedded in the Actions Android APK; local Android builds without this asset will return a 404 on the optional /bridge.exe route.

## Build

Android Studio: Java 17, Gradle 8.13, Android Gradle Plugin 8.13.2, Android SDK 36. Run:

    gradle :app:assembleDebug

The workflow downloads the Windows helper artifact before Android compilation. To build manually outside CI, Windows helper requires .NET 8 SDK for publish, not on end-user PC.

## Inspiration

Source repositories reviewed: Frak89/AmpybiOS, Frak89/AmpyStargate, Frak89/AmpyRAW-Cyberdark. Glass, organic shapes, holographic surfaces, neon cyan/gold accents and transitions are adapted as original web CSS, without redistributing licensed third-party fonts or proprietary art.
