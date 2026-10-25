package com.mitra.assistant

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Firebase Realtime Database se API keys chupke se laata hai aur device me save karta hai. */
object RemoteConfig {

    /** Aapka Firebase project - config node. */
    const val DEFAULT_URL =
        "https://bublu-3a32d-default-rtdb.asia-southeast1.firebasedatabase.app/config.json"

    fun configUrl(context: Context): String {
        val p = context.getSharedPreferences("mitra", Context.MODE_PRIVATE)
            .getString("config_url", "")?.trim() ?: ""
        return if (p.isBlank()) DEFAULT_URL else p
    }

    fun fetchAndStore(context: Context): Boolean {
        val prefs = context.getSharedPreferences("mitra", Context.MODE_PRIVATE)
        val url = configUrl(context)
        val candidates = ArrayList<String>()
        candidates.add(url)
        if (url.contains("/config.json")) candidates.add(url.replace("/config.json", "/.json"))

        for (u in candidates) {
            val o = fetchOne(u) ?: continue
            val g = o.optString("gemini", "")
            val s = o.optString("sarvam", "")
            val inner = o.optJSONObject("config")
            val g2 = if (g.isNotBlank()) g else (inner?.optString("gemini", "") ?: "")
            val s2 = if (s.isNotBlank()) s else (inner?.optString("sarvam", "") ?: "")
            if (g2.isBlank() && s2.isBlank()) continue
            val e = prefs.edit()
            if (g2.isNotBlank()) e.putString("gemini_key", g2)
            if (s2.isNotBlank()) e.putString("sarvam_key", s2)
            e.apply()
            return true
        }
        return false
    }

    /** Admin panel se keys Firebase me publish karo - sab devices pe chali jaayengi. */
    fun uploadKeys(context: Context, gemini: String, sarvam: String): Boolean {
        val url = configUrl(context)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val body = JSONObject().apply {
                put("gemini", gemini)
                put("sarvam", sarvam)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) { false }
    }

    private fun fetchOne(url: String): JSONObject? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().readText()
            if (body.isBlank() || body.trim() == "null") null else JSONObject(body)
        } catch (e: Exception) { null }
    }
}
