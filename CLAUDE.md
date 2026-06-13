# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```powershell
# Build debug APK
./gradlew assembleDebug

# Build release APK (minified with ProGuard)
./gradlew assembleRelease

# Build and install on connected device/emulator
./gradlew installDebug

# Full build
./gradlew build
```

## Test Commands

```powershell
# Run unit tests
./gradlew test

# Run specific unit test variant
./gradlew testDebugUnitTest

# Run instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

## Architecture Overview

This is an Android MDM (Mobile Device Management) kiosk client written in Kotlin. It communicates with a remote server over HTTPS, polling for device commands and sending telemetry.

**Server URL** is configured in `app/src/main/java/com/tomczykowskimdm/Config.kt` — currently a hardcoded ngrok tunnel for development.

### Component Responsibilities

| Component | Role |
|-----------|------|
| `MainActivity.kt` | Entry point: device enrollment, command polling loop (every 5s), APK installation, Compose UI |
| `LockedActivity.kt` | Kiosk/locked screen UI; polls for unlock command (every 3s); supports LOCK and BLOCKED modes |
| `AlarmActivity.kt` | Full-screen distress alarm with flashing screen, torch LED, and looping alarm sound; PIN 1234 dismisses |
| `TelemetryService.kt` | Foreground service started on boot; reports battery %, location, and device info every 60s |
| `MyDeviceAdminReceiver.kt` | Device Admin policy handler; enforces camera disable, kiosk lock task mode, keyguard settings |
| `BootReceiver.kt` | Starts `TelemetryService` on `BOOT_COMPLETED` |
| `InstallResultReceiver.kt` | Receives `PackageInstaller` callbacks; reports APK install result to server |

### Command Flow

`MainActivity` polls `GET /device/{id}/command` every 5 seconds. Commands dispatched:

- `lock` / `blocked` → launches `LockedActivity`
- `disable_camera` → enforces via `DevicePolicyManager`
- `wipe` → factory reset
- `alarm` → launches `AlarmActivity`
- `geofence` → checks device location against a boundary
- `blocked_apps` → hides specified packages
- `disable_tethering` → disables USB/WiFi tethering
- `install_apk_url` + `install_apk_checksum` → downloads APK, verifies SHA-256, installs via `PackageInstaller.Session`

### SharedPreferences

`mdm_prefs` store: `device_id`, `token_{id}`, `blocked` (lock state), `install_attempts_{id}` (retry counter).

### Networking

Uses **OkHttp3** for all HTTP. Auth via `X-Auth-Token` header. JSON serialization with **Moshi**. Command polling uses a `Mutex` to prevent concurrent fetches.

### Tech Stack

- Kotlin + Coroutines (with `kotlinx-coroutines-play-services` for `Task.await()`)
- Jetpack Compose + Material3 (main UI in `MainActivity`)
- View Binding (used in `LockedActivity` with `activity_locked.xml`)
- Google Play Services Fused Location Provider
- Device Admin API (`DevicePolicyManager`)
- `PackageInstaller.Session` for APK installs

### Key Configuration

- **compileSdk / targetSdk:** 35 | **minSdk:** 24
- **Java compatibility:** 11
- Release builds are minified with ProGuard (`minifyEnabled = true`)
- `viewBinding = true` and `compose = true` build features are both enabled
- `network_security_config.xml` permits cleartext HTTP (dev/ngrok)
