package com.tomczykowskimdm

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            val apkUrl = intent.getStringExtra("install_apk_url")
            val checksum = intent.getStringExtra("checksum")
            val deviceId = intent.getStringExtra("device_id")
            val token = intent.getStringExtra("token")

            val success = status == PackageInstaller.STATUS_SUCCESS

            Log.d(TAG, "Install result for apkUrl=$apkUrl checksum=$checksum status=$status message=$message")

            if (deviceId == null || token == null) {
                Log.d(TAG, "Brak deviceId lub token w intent, pomijam raport")
                return@launch
            }

            try {
                val reportPayload = mutableMapOf(
                    "install_apk_url" to apkUrl,
                    "checksum" to checksum,
                    "success" to success,
                    "message" to message
                )
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter<Map<String, Any?>>(Types.newParameterizedType(
                    Map::class.java, String::class.java, Any::class.java
                ))
                val jsonBody = adapter.toJson(reportPayload)
                val client = OkHttpClient()
                val url = "${serverUrl(context)}/device/$deviceId/report-install"
                val mediaType = "application/json".toMediaType()
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(mediaType))
                    .addHeader("X-Auth-Token", token)
                    .build()
                client.newCall(request).execute().use { resp ->
                    Log.d(TAG, "Raport instalacji wysłany, status: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Nie udało się wysłać raportu instalacji: ${e.message}")
            }
        }
    }
}
