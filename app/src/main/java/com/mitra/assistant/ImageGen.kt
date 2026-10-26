package com.mitra.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Image generation - teen providers, silent fallback:
 * 1) Cloudflare Workers AI (FLUX schnell) - free 10k neurons/day (~173 images)
 * 2) Pollinations (agar key/limit available ho)
 * 3) AI Horde (keyless free, slow)
 */
object ImageGen {

    fun gen(ctx: Context, prompt: String, w: Int = 768, h: Int = 768): Bitmap? {
        val prefs = ctx.getSharedPreferences("mitra", Context.MODE_PRIVATE)
        val acc = prefs.getString("cf_account", "") ?: ""
        val tok = prefs.getString("cf_token", "") ?: ""

        if (acc.isNotBlank() && tok.isNotBlank()) {
            cloudflare(acc, tok, prompt)?.let { return it }
        }
        pollinations(prompt, w, h)?.let { return it }
        horde(prompt, w, h)?.let { return it }
        return null
    }

    /** Cloudflare Workers AI - flux-1-schnell. */
    private fun cloudflare(accountId: String, token: String, prompt: String): Bitmap? {
        return try {
            val url = URL(
                "https://api.cloudflare.com/client/v4/accounts/" + accountId +
                    "/ai/run/@cf/black-forest-labs/flux-1-schnell"
            )
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Content-Type", "application/json")

            val body = JSONObject().apply {
                put("prompt", prompt.take(2000))
                put("steps", 4)
            }.toString()

            val os = c.outputStream
            val writer = OutputStreamWriter(os, "UTF-8")
            writer.write(body)
            writer.flush()
            writer.close()

            if (c.responseCode !in 200..299) return null
            val text = c.inputStream.bufferedReader().readText()
            val b64 = JSONObject(text).optJSONObject("result")?.optString("image") ?: return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    private fun pollinations(prompt: String, w: Int, h: Int): Bitmap? {
        return try {
            val q = URLEncoder.encode(prompt, "UTF-8")
            val c = URL("https://image.pollinations.ai/prompt/$q?width=$w&height=$h&nologo=true")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 60000
            if (c.responseCode !in 200..299) return null
            BitmapFactory.decodeStream(c.inputStream)
        } catch (e: Exception) { null }
    }

    /** AI Horde - keyless (0000000000), free par queue slow. */
    private fun horde(prompt: String, w: Int, h: Int): Bitmap? {
        return try {
            val payload = JSONObject().apply {
                put("prompt", prompt)
                put("params", JSONObject().apply {
                    put("width", 512)
                    put("height", 512)
                    put("steps", 20)
                    put("n", 1)
                })
                put("nsfw", false)
                put("models", org.json.JSONArray().put("stable_diffusion"))
            }.toString()

            val c = URL("https://stablehorde.net/api/v2/generate/async").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("apikey", "0000000000")
            c.setRequestProperty("Content-Type", "application/json")
            val writer = OutputStreamWriter(c.outputStream, "UTF-8")
            writer.write(payload)
            writer.flush()
            writer.close()
            if (c.responseCode !in 200..299) return null
            val id = JSONObject(c.inputStream.bufferedReader().readText()).optString("id")
            if (id.isBlank()) return null

            // check karo (max ~90s)
            for (i in 0 until 30) {
                Thread.sleep(3000)
                val chk = URL("https://stablehorde.net/api/v2/generate/check/$id").openConnection() as HttpURLConnection
                chk.connectTimeout = 10000
                chk.readTimeout = 10000
                val j = JSONObject(chk.inputStream.bufferedReader().readText())
                if (j.optBoolean("done")) break
                if (!j.optBoolean("is_possible", true)) return null
            }

            val st = URL("https://stablehorde.net/api/v2/generate/status/$id").openConnection() as HttpURLConnection
            st.connectTimeout = 10000
            st.readTimeout = 20000
            val sj = JSONObject(st.inputStream.bufferedReader().readText())
            val gens = sj.optJSONArray("generations") ?: return null
            if (gens.length() == 0) return null
            val img = gens.optJSONObject(0)?.optString("img") ?: return null
            val dl = URL(img).openConnection() as HttpURLConnection
            dl.connectTimeout = 10000
            dl.readTimeout = 30000
            BitmapFactory.decodeStream(dl.inputStream)
        } catch (e: Exception) { null }
    }
}
