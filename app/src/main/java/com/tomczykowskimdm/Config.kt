package com.tomczykowskimdm

import android.content.Context

object Config {
    const val BASE_URL: String = "https://mdm.ithowtozone.com"
}

fun serverUrl(context: Context): String =
    context.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        .getString("server_url", null) ?: Config.BASE_URL