package com.tomczykowskimdm

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.edit

class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProvisioningState.saveAdminExtras(this, intent)

        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> finishWithProvisioningMode()
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> finishPolicyCompliance()
            else -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun finishWithProvisioningMode() {
        val allowedModes = intent.getIntArrayExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
        )
        val mode = selectProvisioningMode(allowedModes)
        val result = Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun selectProvisioningMode(allowedModes: IntArray?): Int {
        val fullyManaged = DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        val managedProfile = DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
        val modes = allowedModes ?: return fullyManaged

        return when {
            modes.isEmpty() -> fullyManaged
            modes.contains(fullyManaged) -> fullyManaged
            modes.contains(managedProfile) -> managedProfile
            else -> modes.first()
        }
    }

    private fun finishPolicyCompliance() {
        ProvisioningPolicies.apply(this)
        setResult(RESULT_OK)
        finish()
    }
}

object ProvisioningState {
    private const val PREFS = "mdm_prefs"

    fun saveAdminExtras(context: Context, intent: Intent) {
        val extras = adminExtras(intent) ?: return
        val serverUrl = extras.getString("server_url")
        val enrollmentToken = extras.getString("enrollment_token")

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (!serverUrl.isNullOrBlank()) putString("server_url", serverUrl)
            if (!enrollmentToken.isNullOrBlank()) putString("enrollment_token", enrollmentToken)
        }
    }

    private fun adminExtras(intent: Intent): PersistableBundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
    }
}

object ProvisioningPolicies {
    private const val TAG = "ProvisioningPolicies"

    fun apply(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        val pkg = context.packageName

        try {
            dpm.setLockTaskPackages(admin, arrayOf(pkg))
        } catch (e: Exception) {
            Log.w(TAG, "setLockTaskPackages failed", e)
        }

        try {
            dpm.setKeyguardDisabledFeatures(
                admin,
                DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS or
                    DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or
                    DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS or
                    DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
            )
        } catch (e: Exception) {
            Log.w(TAG, "setKeyguardDisabledFeatures failed", e)
        }

        grantPermission(dpm, admin, pkg, Manifest.permission.ACCESS_FINE_LOCATION)
        grantPermission(dpm, admin, pkg, Manifest.permission.ACCESS_COARSE_LOCATION)
        grantPermission(dpm, admin, pkg, Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantPermission(dpm, admin, pkg, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun grantPermission(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        permission: String
    ) {
        try {
            dpm.setPermissionGrantState(
                admin,
                packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Grant $permission failed", e)
        }
    }
}
