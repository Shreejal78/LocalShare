# LocalShare — Android Local File Sharing App

**LocalShare** is an installable Android application built with Kotlin, Jetpack Compose, and Material 3 that lets users select files and folders from their device, start an embedded local HTTP server, and share them with any device (iOS, Android, Windows, macOS, Linux) connected to the same Wi-Fi or mobile hotspot.

---

## Features

- **Storage Access Framework (SAF) Integration**:
  - Select single or multiple files (`ACTION_OPEN_DOCUMENT`).
  - Select complete directory trees (`ACTION_OPEN_DOCUMENT_TREE`).
  - Read-only streaming via `ContentResolver` with persistable permissions.
  - Zero arbitrary storage access needed.

- **Embedded Local HTTP Server**:
  - Real lightweight HTTP server running on-device (default port: `8080`).
  - Configurable port with port conflict detection (`BindException` handling).
  - Accurate local IP address detection (Wi-Fi, Ethernet, Hotspot AP).
  - Background execution via Android Foreground Service (`SharingForegroundService`) with ongoing notification and "Stop Server" action.
  - Wi-Fi high-performance lock and partial wake lock management.
  - Real-time active client connections and request activity logs.

- **Offline Web File Browser**:
  - Clean, responsive Material-inspired web interface served directly from device assets (`/app/src/main/assets/web/`).
  - 100% self-contained with zero CDN or external internet dependencies (works in isolated LANs and offline mobile hotspots).
  - Grid / List view toggle.
  - Real-time search and sorting (by name, size, modification date).
  - Breadcrumb navigation for nested folders.
  - Media streaming with HTTP 206 `Content-Range` support (for seeking in video/audio and resuming downloads).
  - In-browser file preview modal (Images, Audio player, Video player, PDF viewer, Text/Code viewer).
  - On-the-fly streaming ZIP downloads for entire folders or multiple selected items.

- **Security & Privacy**:
  - Strict allowlist: Only explicitly selected document URIs and folder trees are accessible.
  - Strict directory traversal protection: rejects `..`, absolute paths, and backslashes.
  - Optional password protection with session tokens and brute-force rate limiting.
  - Read-only sharing: remote users cannot upload, modify, or delete files.

- **QR Code & Easy Sharing**:
  - Instant on-device QR code generation via ZXing.
  - One-tap URL copy and Android native ShareSheet integration.

---

## Build & Installation

### Requirements
- Android SDK 36 (compileSdk), Minimum Android 8.0 (API 26)
- Java 11 / 17
- Gradle 8+

### Compile Debug APK
```bash
gradle :app:assembleDebug
```
The resulting APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Run Local Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

### Generating a Signed Release APK / AAB
1. In Android Studio, select **Build > Generate Signed Bundle / APK**.
2. Select **APK** or **Android App Bundle**.
3. Choose or create your release keystore (`.jks`).
4. Select build variant `release` and click **Finish**.
Alternatively via CLI:
```bash
gradle :app:assembleRelease
```

---

## Troubleshooting Guide

### 1. Remote Device Cannot Open the URL
- **Same Network**: Verify both your Android phone and the remote client device are connected to the exact same Wi-Fi router or Android Hotspot.
- **Client Isolation / AP Isolation**: Some public Wi-Fi networks (hotels, airports, universities) enable Client Isolation, preventing devices from communicating directly. To resolve this:
  - Enable **Personal Hotspot** on your Android device and connect your laptop/other device to the hotspot. LocalShare automatically detects hotspot subnets (`192.168.43.x`).
- **Firewall / VPN**: Ensure any VPN running on either device is disabled for local LAN traffic.

### 2. Port Conflict Error
- If port `8080` is in use by another service on your phone, open **LocalShare > Settings > Server Port**, change to `8081` or another open port between `1024` and `65535`, and save.

### 3. Wi-Fi IP Address Changes
- If your phone switches between networks or reconnects, LocalShare automatically updates the displayed URL. Tap the **Copy** or **QR Code** button on the dashboard to access the refreshed URL.

---

## License
Created for local peer-to-peer sharing.
