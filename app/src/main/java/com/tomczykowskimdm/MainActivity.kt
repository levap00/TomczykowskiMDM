package com.tomczykowskimdm

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt



data class DeviceCommand(
    val lock: Boolean = false,
    val blocked: Boolean = false,
    val disable_camera: Boolean = false,
    val wipe: Boolean = false,
    val run_diagnostics: Boolean = false,
    val geofence: GeofenceCommand? = null,
    val blocked_apps: List<String>? = null,
    val disable_tethering: Boolean = false,
    val install_apk_url: String? = null,
    val install_apk_checksum: String? = null,
    val alarm: Boolean = false,
    val alarm_interval_sec: Int? = null
)

data class GeofenceCommand(val lat: Double, val lon: Double, val radius: Double)

class MainActivity : ComponentActivity() {


    companion object { private const val TAG = "MDM" }

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    private val http by lazy { OkHttpClient() }
    private val fetchMutex = Mutex()
    private var lastCommandVersion: Int = 0

    // Aktywacja admina
    private val activateAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "Powrót z aktywacji admina, aktywny=${::dpm.isInitialized && dpm.isAdminActive(adminComponent)}")
    }

    // Uprawnienie do lokalizacji
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.d(TAG, "ACCESS_FINE_LOCATION granted=$granted") }

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== START APP (BASE_URL=${Config.BASE_URL}) ===")

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        getSharedPreferences("mdm_prefs", MODE_PRIVATE).edit { putString("device_id", deviceId) }

        ensureAdminActive()

        setContent {
            var status by remember { mutableStateOf("Init…") }
            val scope = rememberCoroutineScope()

            LaunchedEffect(deviceId) {
                if (loadToken(this@MainActivity, deviceId) == null) {
                    status = enrollAndReport(deviceId)
                }
                while (isActive) {
                    val r = sendReport(deviceId)
                    val f = fetchAndApply(deviceId)
                    status = "$f\n$r"
                    delay(5_000)
                }
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("DeviceId: $deviceId")
                    Text("Status: $status")
                    if (!dpm.isAdminActive(adminComponent)) {
                        Text("Device Admin NIE aktywny.")
                        Button(onClick = { ensureAdminActive() }) { Text("Aktywuj Device Admin") }
                    }
                    Button(onClick = {
                        scope.launch {
                            val f = fetchAndApply(deviceId)
                            val r = sendReport(deviceId)
                            status = "$f\n$r"
                        }
                    }) { Text("Odśwież teraz") }
                }
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val toAsk = mutableListOf<String>()
        val svc = Intent(this, TelemetryService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toAsk += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toAsk += android.Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toAsk += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (toAsk.isNotEmpty()) {
            requestPermissions(toAsk.toTypedArray(), 1001)
        }
    }



    private fun ensureAdminActive() {
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Potrzebne do zdalnego zarządzania")
            }
            activateAdminLauncher.launch(intent)
        }
    }

    private fun saveToken(context: Context, deviceId: String, token: String) {
        context.getSharedPreferences("mdm_prefs", MODE_PRIVATE).edit { putString("token_$deviceId", token) }
    }
    private fun loadToken(context: Context, deviceId: String): String? {
        return context.getSharedPreferences("mdm_prefs", MODE_PRIVATE).getString("token_$deviceId", null)
    }

    /** ======= ENROLL + REPORT ======= */

    private suspend fun enrollAndReport(deviceId: String): String = try {
        val payloadJson = buildTelemetryJson()
        val mediaType = "application/json".toMediaType()

        val registerUrl = "${Config.BASE_URL}/device/$deviceId/register"
        Log.d(TAG, "Enroll -> $registerUrl")
        val regBody = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(registerUrl).post(payloadJson.toRequestBody(mediaType)).build()
            http.newCall(req).execute().use { it.body?.string() }
        } ?: return "Brak odpowiedzi przy rejestracji"

        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val parsed = moshi.adapter<Map<String, Any?>>(mapType).fromJson(regBody) ?: return "Nieparsowalny JSON rejestracji"
        val token = (parsed["token"] as? String) ?: return "Brak tokenu w odpowiedzi"
        saveToken(this, deviceId, token)

        // pierwszy report
        val reportUrl = "${Config.BASE_URL}/device/$deviceId/report"
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(reportUrl)
                .post(payloadJson.toRequestBody(mediaType))
                .addHeader("X-Auth-Token", token)
                .build()
            http.newCall(req).execute().use { Log.d(TAG, "Initial report status: ${it.code}") }
        }

        "Zarejestrowano i przesłano telemetrykę"
    } catch (e: Exception) {
        Log.d(TAG, "Błąd enrolmentu", e); "Błąd enrolmentu: ${e.message}"
    }

    private suspend fun sendReport(deviceId: String): String = try {
        val token = loadToken(this, deviceId) ?: return "Brak tokenu (report)"
        val mediaType = "application/json".toMediaType()
        val reportUrl = "${Config.BASE_URL}/device/$deviceId/report"
        val payloadJson = buildTelemetryJson()
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(reportUrl)
                .post(payloadJson.toRequestBody(mediaType))
                .addHeader("X-Auth-Token", token)
                .build()
            http.newCall(req).execute().use { Log.d(TAG, "Report status: ${it.code}") }
        }
        "Wysłano raport"
    } catch (e: Exception) {
        Log.d(TAG, "Błąd reportowania", e); "Błąd reportowania: ${e.message}"
    }

    private suspend fun buildTelemetryJson(): String {
        val androidVersion = Build.VERSION.RELEASE ?: "unknown"
        val batteryIntent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra("level", -1) ?: -1
        val scale = batteryIntent?.getIntExtra("scale", 100) ?: 100
        val batteryPct = if (scale > 0) (level * 100) / scale else 0
        val rooted = try { listOf("/system/bin/su", "/system/xbin/su").any { File(it).exists() } } catch (_: Exception) { false }

        // >>> NOWE: Fused location
        val locationMap = try { getFreshLocationMap() } catch (_: Exception) { emptyMap<String, Any?>() }

        val baseMap = mutableMapOf<String, Any?>(
            "android_version" to androidVersion,
            "battery" to batteryPct,
            "rooted" to rooted
        ).apply { putAll(locationMap) }


        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(mapType).toJson(baseMap)
    }

    /** ======= FETCH & APPLY ======= */

    private suspend fun fetchAndApply(deviceId: String): String = fetchMutex.withLock {
        try {
            val token = loadToken(this, deviceId) ?: return@withLock "Brak tokenu, trzeba zarejestrować"
            val commandUrl = "${Config.BASE_URL}/device/$deviceId/command"

            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(commandUrl).addHeader("X-Auth-Token", token).build()
                http.newCall(req).execute().use { it.body?.string() }
            } ?: return@withLock "Brak odpowiedzi z serwera"

            Log.d(TAG, "Command raw: $body")

            val mapAdapter = moshi.adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
            val parsedMap = try { mapAdapter.fromJson(body) ?: emptyMap() }
            catch (e: Exception) { Log.d(TAG, "parse map err", e); emptyMap<String, Any?>() }

            val version = when (val v = parsedMap["command_version"]) {
                is Double -> v.toInt()
                is Int -> v
                is String -> v.toIntOrNull() ?: 0
                else -> 0
            }
            if (version != lastCommandVersion) lastCommandVersion = version else Log.d(TAG, "No version change: $version")

            val cmd = try { moshi.adapter(DeviceCommand::class.java).fromJson(body) } catch (_: Exception) { null }
            if (cmd == null) return@withLock "Nieparsowalny JSON komend"

            // Instalacja APK – jedna, z retry
            if (!cmd.install_apk_url.isNullOrBlank() && !cmd.install_apk_checksum.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val res = downloadAndInstallApkWithRetry(deviceId, token,
                        cmd.install_apk_url, cmd.install_apk_checksum
                    )
                    Log.d(TAG, "Install result: $res")
                }
            }

            applyCommand(cmd)
            "Zastosowano: lock=${cmd.lock}, blocked=${cmd.blocked}, cam=${cmd.disable_camera}, wipe=${cmd.wipe}, tethering=${cmd.disable_tethering} (ver=$version)"
        } catch (e: Exception) {
            Log.d(TAG, "fetchAndApply error", e); "Błąd: ${e.message}"
        }
    }

    private suspend fun applyCommand(cmd: DeviceCommand) = withContext(Dispatchers.Main) {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val admin = adminComponent

        if (!dpm.isAdminActive(admin)) {
            Log.d(TAG, "Admin nieaktywny – pomijam komendy admina"); return@withContext
        }

        when {
            cmd.blocked -> {
                prefs.edit { putBoolean("blocked", true) }
                startActivity(Intent(this@MainActivity, LockedActivity::class.java).apply {
                    putExtra("mode", "blocked")
                })
            }
            cmd.lock -> {
                prefs.edit { putBoolean("blocked", false) }
                try { dpm.lockNow() } catch (_: Exception) {}
                startActivity(Intent(this@MainActivity, LockedActivity::class.java).apply {
                    putExtra("mode", "lock")
                })
            }
            else -> {
                prefs.edit { putBoolean("blocked", false) }
                sendBroadcast(Intent("com.tomczykowskimdm.UNLOCK"))
                try { stopLockTask() } catch (_: Exception) {}
            }
        }

        try { dpm.setCameraDisabled(admin, cmd.disable_camera) }
        catch (se: SecurityException) { Log.w(TAG, "setCameraDisabled blocked: ${se.message}") }

        if (cmd.wipe) dpm.wipeData(0)

        if (cmd.alarm) {
            startActivity(Intent(this@MainActivity, AlarmActivity::class.java).apply {
                putExtra("duration_sec", cmd.alarm_interval_sec ?: 12)
            })
        }

        if (cmd.disable_tethering)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
        else
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)

        cmd.blocked_apps?.forEach { pkg ->
            try { dpm.setApplicationHidden(admin, pkg, true) }
            catch (e: Exception) { Log.w(TAG, "Hide $pkg failed: ${e.message}") }
        }
    }

    /** ======= APK INSTALL (single path, retry) ======= */

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun downloadAndInstallApkWithRetry(
        deviceId: String,
        token: String,
        apkUrl: String,
        expectedSha256: String,
        maxAttempts: Int = 3
    ): String {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val lastTriedKey = "install_attempts_${apkUrl.hashCode()}"
        var attempt = prefs.getInt(lastTriedKey, 0)

        while (attempt < maxAttempts) {
            attempt++; prefs.edit { putInt(lastTriedKey, attempt) }
            try {
                val apkBytes = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        resp.body?.bytes() ?: throw Exception("Brak ciała odpowiedzi")
                    }
                }

                val digest = sha256(apkBytes)
                if (!digest.equals(expectedSha256, ignoreCase = true)) {
                    throw Exception("Checksum mismatch: oczekiwano $expectedSha256, otrzymano $digest")
                }

                val tempApk = File(cacheDir, "temp.apk")
                FileOutputStream(tempApk).use { it.write(apkBytes) }

                val packageInstaller = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = packageInstaller.createSession(params)
                packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite("apk", 0, apkBytes.size.toLong()).use { out ->
                        out.write(apkBytes); session.fsync(out)
                    }
                    val intent = Intent(this, InstallResultReceiver::class.java).apply {
                        putExtra("device_id", deviceId)
                        putExtra("token", token)
                        putExtra("install_apk_url", apkUrl)
                        putExtra("checksum", expectedSha256)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        this, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pendingIntent.intentSender)
                }

                prefs.edit { remove(lastTriedKey) }
                return "Zainicjowano instalację APK (attempt $attempt)"
            } catch (e: Exception) {
                Log.d(TAG, "Install attempt $attempt failed: ${e.message}")
                if (attempt >= maxAttempts) return "Nie udało się zainstalować APK po $attempt próbach: ${e.message}"
                delay(1000L * (1 shl (attempt - 1)))
            }
        }
        return "Niepowodzenie instalacji APK"
    }

    /** ======= LOCATION ======= */

    // Fused: spróbuj lastLocation, potem erzac „current” z wysoką dokładnością
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun getFreshLocationMap(timeoutMs: Long = 5000): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return emptyMap()

        val fused = LocationServices.getFusedLocationProviderClient(this)
        val lastLoc = try { fused.lastLocation.await() } catch (_: Exception) { null }


        // 1) Spróbuj lastLocation przez callback
        val last = withTimeoutOrNull(1500) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                fused.lastLocation
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc, null) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null, null) }
            }
        }
        if (last != null && last.accuracy <= 100f) {
            return mapOf("location" to mapOf(
                "lat" to last.latitude,
                "lon" to last.longitude,
                "accuracy" to last.accuracy
            ))
        }

        // 2) Wymuś świeży fix
        val cts = CancellationTokenSource()
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc, null) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null, null) }
            }
        }

        return if (fresh != null)
            mapOf("location" to mapOf(
                "lat" to fresh.latitude,
                "lon" to fresh.longitude,
                "accuracy" to fresh.accuracy
            ))
        else emptyMap()
    }

    }

    /** ======= UTILS ======= */

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

