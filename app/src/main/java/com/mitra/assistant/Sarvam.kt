package com.mitra.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Sarvam Saaras v4 speech-to-text (REST). Key user apne phone me daalta hai. */
object Sarvam {

    /** Key test - chhota TTS call karke exact jawab deta hai. */
    fun testKey(key: String): Pair<Boolean, String> {
        return try {
            val conn = URL("https://api.sarvam.ai/text-to-speech").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("api-subscription-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 18000
            val body = org.json.JSONObject().apply {
                put("text", "test")
                put("target_language_code", "hi-IN")
                put("speaker", "priya")
                put("model", "bulbul:v3")
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                Pair(true, "Key is working (HTTP $code)")
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(220) ?: "" } catch (e: Exception) { "" }
                Pair(false, "HTTP $code\n$err")
            }
        } catch (e: Exception) {
            Pair(false, "Network: ${e.message ?: "error"}")
        }
    }

    fun transcribe(key: String, wav: ByteArray, langCode: String = "unknown", mode: String = "translit"): Pair<Boolean, String> {
        return try {
            val url = URL("https://api.sarvam.ai/speech-to-text")
            val boundary = "----bublu" + System.currentTimeMillis()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("api-subscription-key", key)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val os = conn.outputStream

            fun field(name: String, value: String) {
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                os.write("$value\r\n".toByteArray())
            }
            field("model", "saaras:v4")
            field("mode", mode)
            field("language_code", langCode)

            os.write("--$boundary\r\n".toByteArray())
            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
            os.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            os.write(wav)
            os.write("\r\n--$boundary--\r\n".toByteArray())
            os.flush()
            os.close()

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                val m = Regex("\"transcript\"\\s*:\\s*\"(.*?)\"", RegexOption.DOT_MATCHES_ALL).find(body)
                Pair(true, m?.groupValues?.get(1) ?: "")
            } else {
                Pair(false, "HTTP $code: ${body.take(180)}")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "network error")
        }
    }

    /** Text -> speech (Bulbul v3). WAV bytes return karta hai. */
    fun tts(key: String, text: String, speaker: String = "priya"): Pair<Boolean, ByteArray?> {
        return ttsCall(key, text, "en-IN", speaker, "bulbul:v3")
    }

    private fun ttsCall(key: String, text: String, lang: String, speaker: String, model: String): Pair<Boolean, ByteArray?> {
        return try {
            val conn = URL("https://api.sarvam.ai/text-to-speech").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("api-subscription-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 25000
            val body = org.json.JSONObject().apply {
                put("text", text)
                put("language_code", lang)
                put("speaker", speaker)
                put("model", model)
                put("pace", 0.95)
                put("speech_sample_rate", 24000)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                val arr = org.json.JSONObject(resp).optJSONArray("audios")
                val b64 = if (arr != null && arr.length() > 0) arr.getString(0) else null
                if (b64 == null) Pair(false, null)
                else Pair(true, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            Pair(false, null)
        }
    }
}
