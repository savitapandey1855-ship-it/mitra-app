package com.mitra.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Firebase Auth (REST) - sirf admin ko publish karne ke liye token deta hai. */
object FirebaseAuth {

    // Public Firebase web API key (safe to ship; not a secret)
    private const val WEB_API_KEY = "AIzaSyCTMOIzt4kPC6uKSyWU4n0CTqBTrSt_VF0"

    /** Pair(token, errorMessage) */
    fun signIn(email: String, password: String): Pair<String?, String> {
        return try {
            val conn = URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$WEB_API_KEY")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("returnSecureToken", true)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                val msg = try {
                    JSONObject(err).getJSONObject("error").getString("message")
                } catch (e: Exception) { "HTTP $code" }
                return Pair(null, msg)
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val t = JSONObject(resp).optString("idToken", "")
            if (t.isBlank()) Pair(null, "No token") else Pair(t, "")
        } catch (e: Exception) { Pair(null, e.message ?: "network error") }
    }
}
