# xCam - Universal Virtual Camera & Media Injection Xposed Module

**xCam** is a high-performance, universal Xposed module built with the modern **LibXposed API (API 101)**. It seamlessly injects virtual media (videos and images) into live camera feeds, photo capture routines, and video recording sessions across a wide variety of Android applications, supporting **Android 9 up to Android 16 (API 28 - API 36+)**.

---

## 🚀 Key Features

### 📸 Universal Media Support
- **Video & Image Support**: Inject standard video files (`.mp4`, `.mkv`, etc.) or still photos (`.jpg`, `.png`, etc.) with automated seamless loop processing.
- **Real-Time Transformations**: Rotate media on-the-fly (90°, 180°, 270°) and horizontally mirror preview output to match physical camera sensor orientation.

### 🎥 Multi-Layer Pipeline Interception
- **Camera1 & Camera2**: Surface and preview stream hijacking supporting both legacy APIs and modern `OutputConfiguration` pipelines.
- **Hardware ImageReader Hijacking**: Directly injects synthesized YUV420 and JPEG data into native `ImageReader` queues.
- **Universal Capture Replacement**: Intercepts `BitmapFactory` decode streams, `Bitmap.compress`, and `ContentResolver`/`MediaStore` file descriptors to ensure 100% replacement success on photos taken in target apps.
- **Synchronized Video Recording**: Employs hardware Surface-to-Surface transcoding (`MediaCodec`) to replace `MediaRecorder` output with millisecond accuracy matching the live preview, producing authentic IDR keyframes without green screen artifacts or PTS drift.
- **WebRTC Injection**: Replaces incoming video tracks in video call and live streaming apps.
- **Virtual Audio Feed**: Synchronized audio capture routing for media sources with sound.

### 🛠️ Diagnostics & Telemetry
- **Pipeline Monitor**: Real-time inspection of active hook execution routes (e.g., `ImageReader(YUV) ➔ Camera2(SurfaceTexture) ➔ MediaRecorder#start ➔ MediaRecorder(VideoTrimmed)`).
- **Capture Alert**: Heads-up notification card with captured image thumbnail preview and quick-dismiss capabilities.

---

## 📊 Feature Availability

| Feature | Android 9 - 11 (Legacy) | Android 12 - 16 (Modern) |
| :--- | :---: | :---: |
| **Video Playback (.mp4)** | ✅ | ✅ |
| **Still Images (.jpg / .png)** | ✅ | ✅ |
| **Rotate (90° / 180° / 270°)** | ✅ | ✅ |
| **Horizontal Mirroring** | ✅ | ✅ |
| **Photo Capture Hijacking** | ✅ | ✅ |
| **Video Recording Replacement** | ✅ | ✅ |
| **Live Route Pipeline Monitor** | ✅ | ✅ |
| **Capture Alert Notification** | ✅ | ✅ |

---


## Viewing logs from targeted apps

xCam's log viewer can mirror **logcat** so you can see output from the apps you hook (camera errors, MediaCodec warnings, crashes) next to xCam's own diagnostics. Android only allows this after a one-time permission grant, because `READ_LOGS` can't be requested from a normal permission dialog.

Without the grant, the Logcat toggle still works but only shows xCam's own process.

### Requirements

- xCam installed with the `READ_LOGS` permission declared (included in the manifest).
- Developer options enabled: **Settings → About phone → tap Build number 7 times**.
- The target app added to xCam's scope in LSPosed.

### Option A: from a computer (adb over USB)

1. Enable **USB debugging** in Developer options and connect the phone.
2. Confirm the device shows up:
   ```
   adb devices
   ```
3. Grant the permission:
   ```
   adb shell pm grant com.hazbu.xcam android.permission.READ_LOGS
   ```
4. Force-stop xCam (Settings → Apps → xCam → Force stop) and open it again. The grant only applies to a fresh process.

### Option B: from Termux (no computer, Android 11+)

Termux can't grant the permission by itself, but it can talk to the phone's own Wireless debugging service.

1. Install the tools:
   ```
   pkg update && pkg install android-tools
   ```
2. In **Developer options → Wireless debugging**, turn it on (the phone must be on Wi-Fi).
3. Tap **Pair device with pairing code**. Note the pairing port and the 6-digit code. Keep that screen visible, using split-screen or a floating window if needed.
4. In Termux:
   ```
   adb pair localhost:<PAIRING_PORT>
   ```
   Enter the code when asked.
5. Go back to the main Wireless debugging screen and note the (different) **connection port** next to the IP address:
   ```
   adb connect localhost:<CONNECTION_PORT>
   ```
6. Grant the permission:
   ```
   adb shell pm grant com.hazbu.xcam android.permission.READ_LOGS
   ```
7. Force-stop xCam and reopen it.

**Rooted devices** can skip all of this: `su -c "pm grant com.hazbu.xcam android.permission.READ_LOGS"`.

### Using it

1. Open xCam and scroll to **Logs**.
2. Turn on the **Logcat** chip.
3. Open the target app and use its camera.
4. Rows from other apps look like this: `[10:42:15.120] [ERROR] [LOGCAT] MediaCodec(1234) message`. The number in brackets is the app's process ID.
5. Pick **LOGCAT** in the category dropdown to see only logcat lines, or pick any other category to hide them. The level chips filter by severity as usual.

### Notes and troubleshooting

- **Only seeing xCam's own lines:** the grant didn't apply. Re-run it, then force-stop xCam. Check with `adb shell dumpsys package com.hazbu.xcam | grep READ_LOGS`, which should show `granted=true`.
- **`Unknown permission` or `not a changeable permission type`:** the permission isn't in the installed build's manifest. Rebuild and reinstall xCam, then grant again.
- **Grant lost:** reinstalling xCam or rebooting some devices can clear it. Run the command again.
- **Too much output:** logcat can be very chatty. Raise `LOGCAT_MIN_PRIORITY` in `Constants.kt` from `"I"` to `"W"` to store only warnings and above from other apps.
- **Wireless debugging disconnects** when you leave the Wi-Fi network. You only need it for the grant, so it can be switched off afterward.
- **Where hook logs go:** xCam's own hook messages are written through the Xposed log as well, so they also appear in the LSPosed manager's log screen.

## 📦 Installation & Setup

### 📱 Rooted Environments
**Requirements**:
- Rooted Android device (Android 9.0 - 16.0)
- Xposed framework provider supporting modern API (e.g., **LSPosed**, **Vector**, **KernelSU + Zygisk**, **APatch**)

### 🛡️ Non-Rooted Environments
**Requirements**:
- Non-rooted Android device (Android 9.0 - 14.0+)
- APK patcher tool such as **LSPatch** (recommended) or **Shizuku + LSPatch**

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0**.

```text
Copyright (C) 2026 hazbu
```

## ⚠️ Disclaimer

This module is intended for **educational, testing, and development purposes only**. Use responsibly and at your own risk. The developer is not responsible for any misuse, account suspensions, or violations of third-party application terms of service.
