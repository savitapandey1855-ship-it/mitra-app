package com.mitra.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Video ke liye title/description/hashtags banata hai aur app kholta hai. */
object UploadHelper {

    data class Meta(val title: String, val description: String, val tags: String)

    /** AI se title, description aur hashtags banao. */
    fun makeMeta(topic: String, storyText: String, vertical: Boolean): Meta {
        val kind = if (vertical) "YouTube Short / Instagram Reel" else "YouTube video"
        val prompt = "Ek $kind ke liye metadata banao. Topic: $topic. " +
                "Story ka summary: " + storyText.take(600) + "\n" +
                "Reply exactly in this format (3 lines, no extra text):\n" +
                "TITLE: <catchy Hindi/Hinglish title, max 70 chars>\n" +
                "DESC: <2-3 line description in Hinglish, friendly, max 300 chars>\n" +
                "TAGS: <10 hashtags space separated, mix Hindi + English>"

        val key = KeyPool.current("gemini")
        var raw = ""
        if (key != null) {
            val r = Gemini.ask(key, prompt)
            if (r.first) raw = r.second
        }
        if (raw.isBlank()) {
            val sk = KeyPool.current("sarvam")
            if (sk != null) {
                val r = SarvamChat.ask(sk, prompt)
                if (r.first) raw = r.second
            }
        }

        var title = "कहानी: " + topic
        var desc = "Bublu Story Studio se bani ek pyaari si kahani - " + topic + "."
        var tags = "#kahani #hindikahani #cartoon #kidsstory #bublu #storytime #hindicartoon #bacchonkikahani #moralstory #shorts"

        for (line in raw.split("\n")) {
            val l = line.trim()
            when {
                l.startsWith("TITLE:", true) -> title = l.substringAfter(":").trim().take(90).ifBlank { title }
                l.startsWith("DESC:", true) -> desc = l.substringAfter(":").trim().take(400).ifBlank { desc }
                l.startsWith("TAGS:", true) -> tags = l.substringAfter(":").trim().take(400).ifBlank { tags }
            }
        }
        return Meta(title, desc, tags)
    }

    /** App kholo (install ho to), warna browser. */
    fun openApp(ctx: Context, platform: String): Boolean {
        val pkg = when (platform.lowercase()) {
            "youtube" -> "com.google.android.youtube"
            "instagram" -> "com.instagram.android"
            "facebook" -> "com.facebook.katana"
            else -> ""
        }
        if (pkg.isNotBlank()) {
            try {
                val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return true
                }
            } catch (e: Exception) {}
        }
        val url = when (platform.lowercase()) {
            "youtube" -> "https://studio.youtube.com"
            "instagram" -> "https://www.instagram.com"
            "facebook" -> "https://www.facebook.com"
            else -> return false
        }
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) { false }
    }

    /** Steps jo user ko dikhane hain. */
    fun steps(platform: String, vertical: Boolean): String {
        val fmt = if (vertical) "Short / Reel (9:16)" else "Video (16:9)"
        return when (platform.lowercase()) {
            "youtube" -> "1) YouTube kholo\n2) + (Create) dabao → Upload a video\n" +
                    "3) Gallery → Movies se apni video chuno\n4) Title paste karo (Copy button se)\n" +
                    "5) Description + hashtags paste karo\n" +
                    "6) Visibility: Public → Upload\n\nFormat: " + fmt
            "instagram" -> "1) Instagram kholo\n2) + → Reel\n3) Gallery se video chuno\n" +
                    "4) Caption paste karo (Copy button se)\n5) Share dabao\n\nFormat: " + fmt
            "facebook" -> "1) Facebook kholo\n2) Create → Reel / Video\n3) Gallery se video chuno\n" +
                    "4) Caption paste karo → Publish\n\nFormat: " + fmt
            else -> ""
        }
    }
}
