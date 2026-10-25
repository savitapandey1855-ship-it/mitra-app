package com.mitra.assistant

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Firebase (ya koi bhi URL) se API keys chupke se laata hai aur device me save karta hai. */
object RemoteConfig {
    fun fetchAndStore(context: Context): Boolean {
        val prefs = context.getSharedPreferences("mitra", Context.MODE_PRIVATE)
        val url = prefs.getString("config_url", "")?.trim() ?: ""
        if (url.isBlank()) return false
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().readText()
            val o = JSONObject(body)
            val g = o.optString("gemini", "")
            val s = o.optString("sarvam", "")
            val e = prefs.edit()
            if (g.isNotBlank()) e.putString("gemini_key", g)
            if (s.isNotBlank()) e.putString("sarvam_key", s)
            e.apply()
            g.isNotBlank() || s.isNotBlank()
        } catch (e: Exception) { false }
    }
}
