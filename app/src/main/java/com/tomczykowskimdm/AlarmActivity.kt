package com.tomczykowskimdm

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class AlarmActivity : ComponentActivity() {

    private var screenHandler: Handler? = null
    private var torchHandler: Handler? = null
    private var isRed = false
    private var torchOn = false
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var mediaPlayer: MediaPlayer? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nic, próbujemy dalej */ }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // pełny ekran + nad ekranem blokady
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        // prosty root bez XML
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFFFFFFFF.toInt())
        setContentView(root)

        // głośność na maksa (dzwonek + multimedia + alarm) i wyjście z trybu cichego
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
        fun max(stream: Int) =
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)
        try {
            max(AudioManager.STREAM_MUSIC)
            max(AudioManager.STREAM_ALARM)
            max(AudioManager.STREAM_RING)
        } catch (_: Exception) {}

        // dźwięk alarmu (loop)
        val alarmUri: Uri =
            Settings.System.DEFAULT_ALARM_ALERT_URI
                ?: Settings.System.DEFAULT_RINGTONE_URI
        mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
            isLooping = true
            try { start() } catch (_: Exception) {}
        }

        // miganie ekranu
        screenHandler = Handler(Looper.getMainLooper())
        screenHandler?.post(object : Runnable {
            override fun run() {
                root.setBackgroundColor(if (isRed) 0xFFFFFFFF.toInt() else 0xFFFF0000.toInt())
                isRed = !isRed
                screenHandler?.postDelayed(this, 250) // 4 Hz
            }
        })

        // przygotowanie latarki
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        // miganie latarki (jeśli dostępna)
        torchHandler = Handler(Looper.getMainLooper())
        torchHandler?.post(object : Runnable {
            override fun run() {
                toggleTorch()
                torchHandler?.postDelayed(this, 250)
            }
        })

        // dotknięcie ekranu -> prośba o PIN
        root.setOnClickListener { showPinDialog() }
    }

    private fun toggleTorch() {
        val id = cameraId ?: return
        try {
            torchOn = !torchOn
            cameraManager.setTorchMode(id, torchOn)
        } catch (_: Exception) {
            // brak uprawnień/flash – trudno, pomijamy
        }
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Kod"
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Wyłącz alarm")
            .setMessage("Podaj kod:")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == "1234") {
                    stopAlarm()
                } else {
                    Toast.makeText(this, "Błędny kod", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anuluj", null)
            .create()
        dlg.show()
    }

    private fun stopAlarm() {
        // zatrzymaj miganie
        screenHandler?.removeCallbacksAndMessages(null)
        torchHandler?.removeCallbacksAndMessages(null)
        try { cameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
        // audio
        mediaPlayer?.run {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        finish()
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
