# AGENTS.md – AI Coding Guide for TomczykowskiMDM

## Project Overview

**TomczykowskiMDM** is an Android MDM (Mobile Device Management) kiosk klient written in Kotlin. It runs on enrolled devices (minSdk 24, targetSdk 35) as a locked-down system client that communicates with a remote HTTPS server to receive device-control commands, report telemetry, and enforce policies via the Device Admin API.

---

## Quick Start Commands

```powershell
# Build & install debug APK to device
./gradlew installDebug

# Full release build (minified, ProGuard)
./gradlew assembleRelease

# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

**Key configuration:** Server URL in `Config.kt` (currently `https://mdm.ithowtozone.com`); can be overridden via SharedPreferences `server_url` key.

---

## Architecture – Core Components & Data Flow

### 1. **Polling Loop Architecture** (Mutex-protected concurrent model)

**MainActivity** (entry point):
- Polls `GET /device/{id}/command` every **5 seconds**
- Mutex-guards fetch to prevent race conditions
- Deserializes `DeviceCommand` (Moshi) and dispatches:
  - `lock` / `blocked` → launches `LockedActivity`
  - `alarm` → launches `AlarmActivity`
  - `install_apk_url` + checksum → downloads, verifies SHA-256, installs via `PackageInstaller.Session`
  - `disable_camera`, `wipe`, `geofence`, `blocked_apps`, `disable_tethering`, etc.

**LockedActivity** (kiosk/lock screen):
- Separate polling loop every **3 seconds** (waits for `unlock` command)
- Enforces `LockMode.LOCK` (normal) or `BLOCKED` (distress)
- Uses **View Binding** with `activity_locked.xml` (XML-based UI, not Compose)
- Hides system UI; intercepts back button

**TelemetryService** (foreground service):
- Started on `BOOT_COMPLETED` by `BootReceiver`
- Polls every **60 seconds**: battery %, location, device info
- Android 14+ requires 3-arg `startForeground(id, notification, fgsType)` with conditional `FOREGROUND_SERVICE_TYPE_LOCATION` only if permission granted

**AlarmActivity** (full-screen distress):
- Flashing screen (red/black), torch LED strobe, looping alarm sound
- PIN 1234 to dismiss
- Constructed dynamically in code (no XML layout)

### 2. **Command Polling Pattern (Example)**

```kotlin
// In MainActivity
private val cmdMutex = Mutex()

private suspend fun fetchCommand() {
    cmdMutex.withLock {
        val req = Request.Builder()
            .url("$baseUrl/device/$deviceId/command")
            .addHeader("X-Auth-Token", token)
            .get()
            .build()
        val resp = http.newCall(req).execute()
        val cmd = moshi.adapter(DeviceCommand::class.java).fromJson(resp.body?.string() ?: "{}")
        // Dispatch cmd → launch activities, call TelemetryService, etc.
    }
}
```

### 3. **Device Admin Policy Enforcement**

**MyDeviceAdminReceiver** initializes:
- Whitelist this app for lock task (`setLockTaskPackages`)
- On entering lock task: disable camera via `setLockTaskFeatures(LOCK_TASK_FEATURE_NONE)`, add restrictions

**During command dispatch:**
- Camera: `dpm.setCameraDisabled(admin, true)`
- Tethering: `dpm.setUsbDataSignalingEnabled(false)` + WiFi disable
- Wipe: `dpm.wipeData(0)` (factory reset)

### 4. **Shared State & Preferences**

**SharedPreferences `mdm_prefs`:**
- `device_id` – unique device identifier (generated on first run)
- `token_{id}` – auth token for this device
- `blocked` – current lock state (LOCK/BLOCKED)
- `install_attempts_{id}` – retry counter for APK installs
- `server_url` – optional server override

---

## Key Patterns & Conventions

### **Coroutines & Threading**
- **Dispatchers used:** `Dispatchers.IO` (TelemetryService, network), `Dispatchers.Main` (UI updates)
- **Pattern:** `launch(Dispatchers.IO) { /* network */ }` then `withContext(Dispatchers.Main) { /* UI */ }`
- **Play Services integration:** `Task.await()` (from `kotlinx-coroutines-play-services`) wraps Google Play Services Tasks
  - Example: `fused.getCurrentLocation(...).await()` for location fetch

### **HTTP & Networking (OkHttp3)**
- **Single OkHttpClient instance** (reused across MainActivity, LockedActivity, TelemetryService)
- **Headers:** Always include `X-Auth-Token` for authenticated endpoints
- **JSON:** Moshi with `KotlinJsonAdapterFactory` for Kotlin data class support

### **Kotlin Data Classes for Commands**
```kotlin
data class DeviceCommand(
    val lock: Boolean = false,
    val blocked: Boolean = false,
    val install_apk_url: String? = null,
    val install_apk_checksum: String? = null,
    val geofence: GeofenceCommand? = null,
    val blocked_apps: List<String>? = null,
    // ... other fields
)
```
All fields optional with defaults; use `fromJson()` to parse partial responses.

### **APK Installation Flow**
1. Download to cache file: `GET /download/apk` → SHA-256 verify
2. Create `PackageInstaller.Session`
3. Write bytes to session input stream
4. Register `InstallResultReceiver` for callback
5. Report result to server: `POST /device/{id}/install_result`

### **Android Lifecycle Handling**
- Activities inherit `ComponentActivity` (Compose-compatible base)
- Override `onCreate()` for setup; use `lifecycleScope.launch` for coroutine binding
- `LockedActivity` intercepts `onBackPressed` to prevent escape
- `TelemetryService`: `SupervisorJob()` for scope so one failure doesn't kill polling

### **Lock Task & Kiosk Mode**
- Manifest: `android:lockTaskMode="if_whitelisted"` on `LockedActivity`
- Admin policy: call `setLockTaskPackages()` to whitelist this app
- `LockedActivity.startLockTask()` → system enters lock task mode
- `stopLockTask()` only works if Device Admin is active

---

## Critical Implementation Details

### **Android 14+ Foreground Service (FGS) Type Handling**
```kotlin
// TelemetryService.kt
val fgsType = if (hasLocation) {
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
} else {
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
startForeground(notifId, notif, fgsType)  // 3-arg call
```
**Reason:** Android 14+ crashes if you declare location type in manifest but don't actually have permission. Always check `PackageManager.checkSelfPermission()` first.

### **Permissions & Protected APIs**
- `REQUEST_INSTALL_PACKAGES`, `INSTALL_PACKAGES` → required for APK install
- `BIND_DEVICE_ADMIN`, `MANAGE_DEVICE_ADMINS` → Device Admin operations
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` → TelemetryService
- Many are protected/signature permissions; app must be system or undergo provisioning

### **Proguard & Release Builds**
- `minifyEnabled = true` in release build type
- `proguard-rules.pro` keeps Moshi adapters, DevicePolicyManager, OkHttp3 from obfuscation
- Test on both Debug and Release APKs before shipping

### **Server Communication Timeouts**
- OkHttpClient configured with 3-second timeout for all operations
- Lock polling waits 3s, main polling 5s; if timeout, retry on next loop iteration

---

## Extension Points & Common Modifications

- **Add new command:** Extend `DeviceCommand` data class, add case in `dispatchCommand()`
- **Add telemetry metric:** Extend JSON payload in `TelemetryService.reportTelemetry()`
- **New locked UI:** Modify `LockedActivity` Compose or XML layout
- **New permission:** Add to `AndroidManifest.xml` + request at runtime (Android 6+)

---

## Build & Test Workflow

| Task | Command | Notes |
|------|---------|-------|
| Debug APK | `./gradlew assembleDebug` | For testing; no minification |
| Install debug | `./gradlew installDebug` | Requires device/emulator connected |
| Release APK | `./gradlew assembleRelease` | ProGuard minified; in `app/release/` |
| Unit tests | `./gradlew testDebugUnitTest` | Runs on host JVM |
| Device tests | `./gradlew connectedAndroidTest` | Requires device; Espresso + AndroidJUnit4 |

---

## File Organization Reference

```
app/src/main/java/com/tomczykowskimdm/
├── MainActivity.kt              # Polling loop, command dispatch, APK install
├── LockedActivity.kt            # Lock screen with 3s polling (View Binding)
├── AlarmActivity.kt             # Distress alarm (screen, torch, sound)
├── TelemetryService.kt          # Foreground service, location/battery reporting
├── MyDeviceAdminReceiver.kt     # Device Admin policy enforcement
├── BootReceiver.kt              # BOOT_COMPLETED → starts TelemetryService
├── InstallResultReceiver.kt     # PackageInstaller callback handler
├── ProvisioningActivity.kt      # Device provisioning (QR-based)
└── Config.kt                    # Server URL configuration

app/src/main/res/
├── xml/
│   ├── device_admin.xml         # Device Admin policy descriptor
│   ├── network_security_config.xml  # Cleartext HTTP for ngrok dev
│   └── ...
├── layout/
│   └── activity_locked.xml      # LockedActivity UI
└── ...

app/src/main/AndroidManifest.xml # Permissions, receivers, services, activities
app/build.gradle.kts             # AGP 9.x config, Compose, ViewBinding enabled
gradle/libs.versions.toml        # Dependency versions (Kotlin 2.2.10, AGP 9.2.1)
```

---

## Debugging & Common Issues

1. **"Device not recognized as admin"** → Ensure Device Admin receiver is properly registered and enabled via system settings
2. **"HTTP requests timeout"** → Check network connectivity; server may be down; logs include OkHttp retries
3. **"Foreground service crash on Android 14"** → See FGS type handling section above; verify permission checks
4. **"APK install fails silently"** → Enable `REQUEST_INSTALL_PACKAGES` permission + check ProGuard rules don't obfuscate PackageInstaller
5. **"Geofence check returns null"** → Location permission may be revoked; use `withTimeoutOrNull()` on location fetch

---

## External Dependencies & Integration Points

- **OkHttp3 4.12.0** – HTTP client (all network calls)
- **Moshi 1.15.2** + kotlin plugin – JSON serialization
- **Jetpack Compose 2024.09.00** – MainActivity UI (MainActivity uses Compose; LockedActivity uses XML)
- **Google Play Services 21.3.0** – Fused Location Provider (`TelemetryService`)
- **Kotlin Coroutines 1.8.1** – Async/concurrency (`kotlinx-coroutines-play-services` for Task.await())
- **AndroidX Lifecycle** – ViewModel, LifecycleScope
- **Android Device Admin API** – System-level policy enforcement (DevicePolicyManager, DeviceAdminReceiver)


