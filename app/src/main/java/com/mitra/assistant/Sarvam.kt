package com.mitra.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Sarvam Saaras v4 speech-to-text (REST). Key user apne phone me daalta hai. */
object Sarvam {

    fun transcribe(key: String, wav: ByteArray, langCode: String = "unknown", mode: String = "translit"): Pair<Boolean, String> {
        return try {
            val url = URL("https://api.sarvam.ai/speech-to-text")
            val boundary = "----bublu" + System.currentTimeMillis()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("api-subscription-key", key)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
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
}
