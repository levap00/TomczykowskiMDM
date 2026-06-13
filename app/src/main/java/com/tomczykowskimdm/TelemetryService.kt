package com.tomczykowskimdm

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.*
import android.os.Build
import android.annotation.SuppressLint
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.tasks.await

class TelemetryService : Service() {

    private val channelId = "telemetry_channel"
    private val notifId = 42
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        createNotifChannel()
        fused = LocationServices.getFusedLocationProviderClient(this)
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MDM – telemetria aktywna")
            .setContentText("Wysyłanie stanu baterii i lokalizacji…")
            .setOngoing(true)
            .build()
        startForeground(notifId, notif)

        scope.launch {
            telemetryLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY – wróci po zabiciu procesu
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?) = null

    private suspend fun telemetryLoop() {
        // pętla działająca dopóki Service żyje
        while (scope.isActive) {
            try {
                val batteryPct = readBattery()
                val loc = safeGetLocation()
                sendReport(batteryPct, loc)
            } catch (t: Throwable) {
                // swallow i spróbuj później
            }
            delay(60_000) // co minutę (dopasuj pod siebie)
        }
    }

    private fun readBattery(): Int? {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter) ?: return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }


    private suspend fun safeGetLocation(): Location? {
        val fineGranted = checkSelfPermission(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null

        return try {
            fused.lastLocation.await()   // OK – masz import kotlinx-coroutines-play-services
        } catch (_: Throwable) { null }
    }

    private fun sendReport(battery: Int?, loc: Location?) {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return
        val token = prefs.getString("token_$deviceId", null) ?: return
        val url = URL("${serverUrl(this)}/device/$deviceId/report")

        val payload = JSONObject().apply {
            put("battery", battery)
            if (loc != null) {
                put("location", JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                    put("accuracy", loc.accuracy)
                })
            }
        }
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Auth-Token", token)
            doOutput = true
            outputStream.use { it.write(payload.toString().toByteArray()) }
            inputStream.bufferedReader().use { it.readText() }
            disconnect()
        }
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "Telemetry", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}

@RequiresPermission("android.permission.READ_PRIVILEGED_PHONE_STATE")
@SuppressLint("HardwareIds")
private fun getSerialFallback(ctx: Context): String {
    val serial: String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
        else @Suppress("DEPRECATION") Build.SERIAL
    } catch (_: SecurityException) { null }

    return serial ?: Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ANDROID_ID
    ) ?: "unknown"
}
