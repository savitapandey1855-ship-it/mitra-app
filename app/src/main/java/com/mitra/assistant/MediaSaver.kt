package com.mitra.assistant

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Video/photo ko phone ki gallery me sahi tareeke se save karta hai. */
object MediaSaver {

    /** MP4 ko gallery me daalo. Return: (saved, message) */
    fun saveVideo(ctx: Context, src: File, name: String): Pair<Boolean, String> {
        if (!src.exists() || src.length() < 2000) {
            return Pair(false, "video file hi nahi bani (" + src.length() + " bytes)")
        }
        val sizeMb = String.format("%.1f", src.length() / 1048576.0)

        // --- Android 10+ : MediaStore with IS_PENDING ---
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        src.inputStream().use { ins -> ins.copyTo(os) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    return Pair(true, "Gallery > Movies me save ho gayi (" + sizeMb + " MB)")
                }
            } catch (e: Exception) {
                // neeche fallback
            }
        }

        // --- Android 9 aur purane: seedha file + scan ---
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Bublu"
            )
            if (dir.exists() || dir.mkdirs()) {
                val dest = File(dir, name)
                src.inputStream().use { ins -> dest.outputStream().use { os -> ins.copyTo(os) } }
                MediaScannerConnection.scanFile(
                    ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null
                )
                return Pair(true, "Gallery > Movies/Bublu me save ho gayi (" + sizeMb + " MB)")
            }
        } catch (e: Exception) {
            // neeche fallback
        }

        // --- aakhri fallback: app ka apna folder (permission nahi chahiye) ---
        return try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val dest = File(dir, name)
            src.inputStream().use { ins -> dest.outputStream().use { os -> ins.copyTo(os) } }
            MediaScannerConnection.scanFile(
                ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null
            )
            Pair(true, "App folder me save hui: " + dest.absolutePath)
        } catch (e: Exception) {
            Pair(false, "save fail: " + (e.message ?: "unknown"))
        }
    }

    /** PNG frame ko Pictures me daalo. */
    fun saveImage(ctx: Context, bitmap: android.graphics.Bitmap, name: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, os)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Bublu"
                )
                if (!dir.exists() && !dir.mkdirs()) return false
                val dest = File(dir, name)
                dest.outputStream().use { os ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, os)
                }
                MediaScannerConnection.scanFile(
                    ctx, arrayOf(dest.absolutePath), arrayOf("image/png"), null
                )
                true
            }
        } catch (e: Exception) { false }
    }
}
