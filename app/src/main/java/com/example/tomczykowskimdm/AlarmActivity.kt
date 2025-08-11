package com.example.tomczykowskimdm

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.graphics.Color

@Suppress("DEPRECATION")
class AlarmActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var flashing = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val root = FrameLayout(this)
        setContentView(root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, root)
        insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        fun flash() {
            if (!flashing) return
            val current = (root.tag as? Int) ?: 0
            val next = if (current == 0) 1 else 0
            root.setBackgroundColor(if (next == 0) Color.BLACK else Color.WHITE)
            root.tag = next
            ui.postDelayed({ flash() }, 250)
        }
        flash()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer.create(this, uri)
        player?.isLooping = true
        try { player?.start() } catch (_: Exception) {}

        val secs = intent.getIntExtra("duration_sec", 12).coerceIn(5, 120)
        ui.postDelayed({ finish() }, secs * 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        flashing = false
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
    }
}