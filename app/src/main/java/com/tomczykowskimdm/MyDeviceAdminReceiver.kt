package com.tomczykowskimdm

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

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ProvisioningPolicies.apply(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        ProvisioningState.saveAdminExtras(context, intent)
        ProvisioningPolicies.apply(context)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply lock task policy", e)
        }
    }
}
