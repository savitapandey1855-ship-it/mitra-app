package com.mitra.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sarvam ka chat (thinking) model - Gemini fail hone pe backup dimaag. */
object SarvamChat {

    private const val ENDPOINT = "https://api.sarvam.ai/v1/chat/completions"

    fun ask(key: String, prompt: String): Pair<Boolean, String> {
        for (model in listOf("sarvam-m", "sarvam-105b", "sarvam-30b")) {
            val r = call(key, model, prompt)
            if (r.first) return r
        }
        return Pair(false, "sarvam chat failed")
    }

    private fun call(key: String, model: String, prompt: String): Pair<Boolean, String> {
        return try {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("api-subscription-key", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

            val messages = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Bublu, a helpful assistant for an Indian student. Reply briefly and clearly.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                put("max_tokens", 900)
                put("messages", messages)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) {}
                return Pair(false, "HTTP $code")
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val txt = JSONObject(resp)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            if (txt.isBlank()) Pair(false, "empty reply") else Pair(true, txt.trim())
        } catch (e: Exception) {
            Pair(false, e.message ?: "network")
        }
    }
}
