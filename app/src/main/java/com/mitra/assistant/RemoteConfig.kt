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
            val inner = o.optJSONObject("config")
            val src = inner ?: o
            val gList = readKeys(src, "gemini")
            val sList = readKeys(src, "sarvam")
            val cfAcc = src.optString("cf_account", "").trim()
            val cfTok = src.optString("cf_token", "").trim()
            if (gList.isEmpty() && sList.isEmpty() && cfTok.isBlank()) continue
            if (gList.isNotEmpty()) KeyPool.save("gemini", gList)
            if (sList.isNotEmpty()) KeyPool.save("sarvam", sList)
            if (cfAcc.isNotBlank()) prefs.edit().putString("cf_account", cfAcc).apply()
            if (cfTok.isNotBlank()) prefs.edit().putString("cf_token", cfTok).apply()
            return true
        }
        return false
    }

    /** "gemini_keys": [..] ya "gemini": "k1,k2" - dono chalega. */
    private fun readKeys(o: JSONObject, name: String): List<String> {
        val out = ArrayList<String>()
        val arr = o.optJSONArray(name + "_keys")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) out.add(v)
            }
        }
        val single = o.optString(name, "").trim()
        if (single.isNotBlank()) {
            single.split(",", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
        }
        return out.distinct()
    }

    /** Admin panel se keys Firebase me publish karo (login/secret ke saath). */
    fun uploadKeys(context: Context, gemini: String, sarvam: String): Pair<Boolean, String> {
        val p0 = context.getSharedPreferences("mitra", Context.MODE_PRIVATE)
        val cfA = p0.getString("cf_account", "") ?: ""
        val cfT = p0.getString("cf_token", "") ?: ""
        val prefs = context.getSharedPreferences("mitra", Context.MODE_PRIVATE)
        val base = configUrl(context)

        val secret = prefs.getString("db_secret", "")?.trim() ?: ""
        val email = prefs.getString("fb_email", "")?.trim() ?: ""
        val pass = prefs.getString("fb_pass", "") ?: ""

        var token = ""
        var loginErr = ""
        if (secret.isNotBlank()) token = secret
        else if (email.isNotBlank() && pass.isNotBlank()) {
            val (t, e) = FirebaseAuth.signIn(email, pass)
            token = t ?: ""
            loginErr = e
        }

        // 1) login/secret ke saath
        if (token.isNotBlank()) {
            val r = put(base, token, gemini, sarvam, cfA, cfT)
            if (r.first) return r
            // 2) fallback: bina auth (agar rules open hain)
            val r2 = put(base, "", gemini, sarvam, cfA, cfT)
            if (r2.first) return Pair(true, "ok")
            return Pair(false, "Login ke saath: ${r.second}\nBina login: ${r2.second}")
        }

        // koi login nahi - seedha try
        val r = put(base, "", gemini, sarvam)
        if (r.first) return Pair(true, "ok")
        val hint = if (loginErr.isNotBlank()) "Firebase login: $loginErr\n" else ""
        return Pair(false, hint + r.second)
    }

    private fun put(
        base: String, token: String, gemini: String, sarvam: String,
        cfAccount: String, cfToken: String
    ): Pair<Boolean, String> {
        var url = base
        if (token.isNotBlank()) {
            val sep = if (url.contains("?")) "&" else "?"
            url = url + sep + "auth=" + java.net.URLEncoder.encode(token, "UTF-8")
        }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val gList = gemini.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            val sList = sarvam.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            val body = JSONObject().apply {
                put("gemini", gList.firstOrNull() ?: "")
                put("sarvam", sList.firstOrNull() ?: "")
                put("gemini_keys", org.json.JSONArray(gList))
                put("sarvam_keys", org.json.JSONArray(sList))
                put("cf_account", cfAccount)
                put("cf_token", cfToken)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) Pair(true, "ok")
            else {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(180) ?: "" } catch (e: Exception) { "" }
                Pair(false, "HTTP $code $err")
            }
        } catch (e: Exception) { Pair(false, e.message ?: "network error") }
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
