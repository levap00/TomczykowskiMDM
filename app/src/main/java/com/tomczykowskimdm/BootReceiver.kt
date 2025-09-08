package com.tomczykowskimdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, TelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }
}
