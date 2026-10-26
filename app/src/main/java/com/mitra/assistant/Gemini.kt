package com.mitra.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small helper to talk to the Gemini API.
 * Tries several current model names until one works, and returns the real error text.
 */
object Gemini {

    // Fast (lite) pehle, phir normal flash. Purane/naam-wale hata diye (fail hone pe time waste hota tha).
    private val MODELS = listOf(
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    @Volatile private var working: String? = null

    private fun badKey(e: String): Boolean =
        e.contains("api key not valid") || e.contains("api_key_invalid") ||
            e.contains("permission_denied") || e.contains("leaked") || e.contains("unauthenticated")

    /** Returns Pair(ok, text). On failure, text is a short readable error. */
    /** Public entry - agar key fail ho to chupke se agli key try karta hai. */
    fun ask(key: String, prompt: String): Pair<Boolean, String> {
        val r = askOnce(key, prompt)
        if (r.first) return r
        val n = KeyPool.count("gemini")
        if (n <= 1) return r
        var last = r
        for (i in 0 until n) {
            KeyPool.markBad("gemini")
            val k = KeyPool.current("gemini") ?: break
            if (k == key) break
            val r2 = askOnce(k, prompt)
            if (r2.first) return r2
            last = r2
        }
        return last
    }

    private fun askOnce(key: String, prompt: String): Pair<Boolean, String> {
        // Pehle jo model pichhli baar chala tha, wahi try karo (fast)
        working?.let { m ->
            val r = call(key, m, prompt)
            if (r.first) return r
            if (badKey(r.second.lowercase())) return r
            working = null
        }
        var last = "koi model kaam nahi kiya"
        for (m in MODELS) {
            val r = call(key, m, prompt)
            if (r.first) { working = m; return r }
            last = r.second
            if (badKey(r.second.lowercase())) return r
        }
        return Pair(false, last)
    }

    private fun call(key: String, model: String, prompt: String): Pair<Boolean, String> {
        return try {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 18000
            val body = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put("generationConfig", JSONObject().put("maxOutputTokens", 300))
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: ""

            if (code in 200..299) {
                val parts = JSONObject(resp)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought", false) && p.has("text")) {
                        sb.append(p.getString("text"))
                    }
                }
                val txt = sb.toString().trim()
                if (txt.isEmpty()) Pair(false, "$model: khali jawab") else Pair(true, txt)
            } else {
                val msg = try {
                    JSONObject(resp).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP $code"
                }
                Pair(false, "$model -> $msg")
            }
        } catch (e: Exception) {
            Pair(false, "$model -> ${e.message}")
        }
    }
}
