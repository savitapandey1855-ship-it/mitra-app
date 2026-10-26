package com.mitra.assistant

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Bitmaps -> MP4 (H.264) - YUV buffer tareeke se.
 * Surface/Canvas nahi use karta, isliye har phone pe chalta hai.
 */
object Mp4Maker {

    fun encode(
        frames: List<Bitmap>,
        secondsEach: Float,
        outFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<Boolean, String> {
        if (frames.isEmpty()) return Pair(false, "koi frame nahi")

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            var w = frames[0].width
            var h = frames[0].height
            if (w % 2 != 0) w -= 1
            if (h % 2 != 0) h -= 1

            val fps = 12
            val type = MediaFormat.MIMETYPE_VIDEO_AVC

            val probe = MediaCodec.createEncoderByType(type)
            val caps = probe.codecInfo.getCapabilitiesForType(type)
            probe.release()

            var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            var planar = false
            if (!caps.colorFormats.contains(colorFormat)) {
                if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    planar = true
                } else if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                }
            }

            val format = MediaFormat.createVideoFormat(type, w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, w * h * 3)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            codec = MediaCodec.createEncoderByType(type)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            val frameDur = 1_000_000L / fps

            fun drain(end: Boolean) {
                while (true) {
                    val idx = codec!!.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) return
                        Thread.sleep(5)
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer!!.addTrack(codec!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                    } else if (idx >= 0) {
                        val buf = codec!!.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && muxerStarted &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(trackIndex, buf, info)
                        }
                        codec!!.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            var pts = 0L
            for ((fi, bmp) in frames.withIndex()) {
                val yuv = toYuv(bmp, w, h, planar)
                val count = (secondsEach * fps).toInt().coerceAtLeast(12)
                for (f in 0 until count) {
                    val inIdx = codec.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                        if (ib != null) {
                            ib.clear()
                            ib.put(yuv)
                            codec.queueInputBuffer(inIdx, 0, yuv.size, pts, 0)
                            pts += frameDur
                        }
                    }
                    drain(false)
                }
                onProgress(fi + 1, frames.size)
            }

            val inIdx = codec.dequeueInputBuffer(50_000)
            if (inIdx >= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)

            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) {}
            }

            if (outFile.exists() && outFile.length() > 2000) Pair(true, "ok")
            else Pair(false, "file chhoti hai (" + outFile.length() + " bytes)")
        } catch (e: Exception) {
            Pair(false, (e.message ?: "encoder error"))
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    /** Bitmap -> NV12 (semi-planar) ya I420 (planar). */
    private fun toYuv(bmp: Bitmap, w: Int, h: Int, planar: Boolean): ByteArray {
        val scaled = if (bmp.width == w && bmp.height == h) bmp
        else Bitmap.createScaledBitmap(bmp, w, h, true)
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)

        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        var uIdx = ySize
        var vIdx = ySize + ySize / 4

        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[j * w + i] = clamp(y)

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (planar) {
                        if (uIdx < ySize + ySize / 4) out[uIdx++] = clamp(u)
                        if (vIdx < out.size) out[vIdx++] = clamp(v)
                    } else {
                        if (uIdx + 1 < out.size) {
                            out[uIdx] = clamp(u)
                            out[uIdx + 1] = clamp(v)
                            uIdx += 2
                        }
                    }
                }
            }
        }
        return out
    }

    private fun clamp(v: Int): Byte = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
}
