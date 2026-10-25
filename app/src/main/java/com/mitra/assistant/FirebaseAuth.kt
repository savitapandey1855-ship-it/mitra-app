package com.mitra.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Firebase Auth (REST) - sirf admin ko publish karne ke liye token deta hai. */
object FirebaseAuth {

    // Public Firebase web API key (safe to ship; not a secret)
    private const val WEB_API_KEY = "AIzaSyCTMOIzt4kPC6uKSyWU4n0CTqBTrSt_VF0"

    fun signIn(email: String, password: String): String? {
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
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optString("idToken", "").ifBlank { null }
        } catch (e: Exception) { null }
    }
}
