package com.tomczykowskimdm

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tomczykowskimdm.databinding.ActivityLockedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LockedActivity : ComponentActivity() {


    enum class LockMode { LOCK, BLOCKED }
    private var mode: LockMode = LockMode.LOCK

    companion object {
        private const val PREFS = "mdm_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val POLL_INTERVAL_MS = 3_000L
    }

    private lateinit var binding: ActivityLockedBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var insetsController: WindowInsetsControllerCompat

    private val http = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try { stopLockTask() } catch (_: Exception) {}
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        mode = when (intent.getStringExtra("mode")) {
            "blocked" -> LockMode.BLOCKED
            else -> LockMode.LOCK
        }

        // No-screenshot
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityLockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Immersive kiosk UI
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, binding.root)
        hideSystemBars()

        // Block BACK
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })

        // Message
        binding.lockedMessage.text = if (mode == LockMode.BLOCKED) {
            "Zablokowane, zgłoś się do administratora"
        } else {
            "Telefon zablokowany\nSkontaktuj się z Adminem"
        }

        // Hard block touch for BLOCKED
        if (mode == LockMode.BLOCKED) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        }

        // Polling UNLOCK/UNBLOCK – niezależnie od MainActivity
        startUnlockPolling()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    unlockReceiver,
                    IntentFilter("com.tomczykowskimdm.UNLOCK"),
                    RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(unlockReceiver, IntentFilter("com.tomczykowskimdm.UNLOCK"))
            }
        } catch (_: Exception) {}
        try { startLockTask() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
    }

    @SuppressLint("SetTextI18n")
    private fun startUnlockPolling() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)

        lifecycleScope.launch {
            while (isActive) {
                try {
                    val token = currentToken(deviceId)
                    if (!deviceId.isNullOrBlank() && !token.isNullOrBlank()) {
                        val req = Request.Builder()
                            .url("${serverUrl(this@LockedActivity)}/device/$deviceId/command")
                            .addHeader("X-Auth-Token", token)
                            .build()
                        withContext(Dispatchers.IO) {
                            http.newCall(req).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                if (body.isNotEmpty()) {
                                    val json = JSONObject(body)
                                    val isLocked = json.optBoolean("lock", false)
                                    val isBlocked = json.optBoolean("blocked", false)

                                    if (!isLocked && !isBlocked) {
                                        try { stopLockTask() } catch (_: Exception) {}
                                        finish()
                                    } else if (isBlocked && mode != LockMode.BLOCKED) {
                                        mode = LockMode.BLOCKED
                                        runOnUiThread {
                                            window.setFlags(
                                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                            )
                                            binding.lockedMessage.text =
                                                "Zablokowane, zgłoś się do administratora"
                                        }
                                    } else if (!isBlocked && mode != LockMode.LOCK) {
                                        mode = LockMode.LOCK
                                        runOnUiThread {
                                            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                                            binding.lockedMessage.text =
                                                "Telefon zablokowany\nSkontaktuj się z Adminem"
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* cicho, próbujemy dalej */ }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun currentToken(deviceId: String?): String? {
        if (deviceId.isNullOrBlank()) return null
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return p.getString("token_$deviceId", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
