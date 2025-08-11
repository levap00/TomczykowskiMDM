package com.example.tomczykowskimdm

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi


class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "MyDeviceAdminReceiver"
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        try {

            // 1) Whitelist do kiosk-mode
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

            // 2) Zablokuj ściąganie status bar
admin
            // 3) Wyłącz funkcje ekranu blokady (biometria, trust agents, nieodszyfrowane powiadomienia)
            dpm.setKeyguardDisabledFeatures(
                admin,
                DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS or
                        DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or
                        DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS or
                        DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
            )
        } catch (se: SecurityException) {
            Log.w("MDM", "Brak uses-policy disable-keyguard-features lub nie Device Owner", se)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "DeviceAdmin wyłączony")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "Otrzymano broadcast w DeviceAdminReceiver: ${intent.action}")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete")
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

        // 4) Usuń możliwość tworzenia nowych okien systemowych
        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS)

        // 5) Wyłącz wszystkie paski i przyciski systemowe w kiosk-mode
        dpm.setLockTaskFeatures(
            adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        )
    }




    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.d(TAG, "Wyjście z lock task mode")
    }

    override fun onNetworkLogsAvailable(
        context: Context,
        intent: Intent,
        batchToken: Long,
        networkLogsCount: Int
    ) {
        Log.d(TAG, "Network logs available")
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        Log.d(TAG, "Security logs available")
    }

    @Deprecated("Deprecated in Java")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.d(TAG, "Nieudane logowanie hasłem")
    }
}
