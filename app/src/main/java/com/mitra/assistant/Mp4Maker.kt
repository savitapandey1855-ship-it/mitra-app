package com.mitra.assistant

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bitmaps + narration -> MP4 (H.264 video + AAC audio).
 * Frames ek-ek karke banti hain (memory safe).
 */
object Mp4Maker {

    fun encode(
        frameAt: (Int) -> Bitmap,
        count: Int,
        durations: List<Float>,
        pcm: ShortArray?,
        sampleRate: Int,
        outFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<Boolean, String> {
        if (count <= 0) return Pair(false, "koi frame nahi")

        var vCodec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        return try {
            val first = frameAt(0)
            var w = first.width
            var h = first.height
            if (w % 2 != 0) w -= 1
            if (h % 2 != 0) h -= 1

            val fps = 12
            val vType = MediaFormat.MIMETYPE_VIDEO_AVC
            val probe = MediaCodec.createEncoderByType(vType)
            val caps = probe.codecInfo.getCapabilitiesForType(vType)
            probe.release()

            var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            var planar = false
            if (!caps.colorFormats.contains(colorFormat)) {
                if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    planar = true
                } else {
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                }
            }

            // ---------- 1) AUDIO pehle (poora encode karke rakh lo) ----------
            var aFormat: MediaFormat? = null
            val aSamples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            if (pcm != null && pcm.isNotEmpty()) {
                try {
                    val rate = if (sampleRate > 0) sampleRate else 22050
                    val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    val af = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1
                    ).apply {
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
                    }
                    aEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aEnc.start()

                    val ain = MediaCodec.BufferInfo()
                    val chunk = 2048
                    val frameDurUs = 1_000_000L * chunk / rate
                    var offset = 0
                    var apts = 0L
                    var eosSent = false
                    var guard = 0

                    while (guard < 100000) {
                        guard++
                        val inIdx = aEnc.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = aEnc.getInputBuffer(inIdx)
                            if (ib != null) {
                                ib.clear()
                                if (offset < pcm.size) {
                                    val n = minOf(chunk, pcm.size - offset)
                                    val bb = ib.order(ByteOrder.LITTLE_ENDIAN)
                                    for (k in 0 until n) bb.putShort(pcm[offset + k])
                                    offset += n
                                    aEnc.queueInputBuffer(inIdx, 0, n * 2, apts, 0)
                                    apts += frameDurUs
                                } else if (!eosSent) {
                                    aEnc.queueInputBuffer(
                                        inIdx, 0, 0, apts, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    eosSent = true
                                }
                            }
                        }

                        val outIdx = aEnc.dequeueOutputBuffer(ain, 10_000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            aFormat = aEnc.outputFormat
                        } else if (outIdx >= 0) {
                            val ob = aEnc.getOutputBuffer(outIdx)
                            if (ob != null && ain.size > 0 &&
                                (ain.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                val arr = ByteArray(ain.size)
                                ob.position(ain.offset)
                                ob.limit(ain.offset + ain.size)
                                ob.get(arr)
                                val bi = MediaCodec.BufferInfo()
                                bi.set(0, arr.size, ain.presentationTimeUs, ain.flags)
                                aSamples.add(Pair(arr, bi))
                            }
                            aEnc.releaseOutputBuffer(outIdx, false)
                            if ((ain.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        } else if (eosSent && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // chalta rahe
                        }
                    }
                    try { aEnc.stop() } catch (e: Exception) {}
                    try { aEnc.release() } catch (e: Exception) {}
                } catch (e: Exception) {
                    aFormat = null
                    aSamples.clear()
                }
            }

            // ---------- 2) VIDEO ----------
            val vFormat = MediaFormat.createVideoFormat(vType, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, w * h * 3)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vCodec = MediaCodec.createEncoderByType(vType)
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vCodec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var vTrack = -1
            var aTrack = -1
            var started = false
            val info = MediaCodec.BufferInfo()
            val frameDur = 1_000_000L / fps

            fun startMuxer() {
                if (started) return
                if (aFormat != null && aSamples.isNotEmpty()) {
                    aTrack = muxer!!.addTrack(aFormat!!)
                }
                vTrack = muxer!!.addTrack(vCodec!!.outputFormat)
                muxer!!.start()
                started = true
                // audio samples likho
                if (aTrack >= 0) {
                    for ((bytes, bi) in aSamples) {
                        val buf = ByteBuffer.allocateDirect(bytes.size)
                        buf.put(bytes)
                        buf.position(0)
                        try { muxer!!.writeSampleData(aTrack, buf, bi) } catch (e: Exception) {}
                    }
                    aSamples.clear()
                }
            }

            fun drainVideo(end: Boolean) {
                while (true) {
                    val idx = vCodec!!.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) return
                        Thread.sleep(5)
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        startMuxer()
                    } else if (idx >= 0) {
                        val buf = vCodec!!.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && started && vTrack >= 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            try { muxer!!.writeSampleData(vTrack, buf, info) } catch (e: Exception) {}
                        }
                        vCodec!!.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            var pts = 0L
            for (i in 0 until count) {
                val bmp = if (i == 0) first else frameAt(i)
                val yuv = toYuv(bmp, w, h, planar)
                val dur = if (i < durations.size) durations[i] else 3f
                val reps = (dur * fps).toInt().coerceAtLeast(6)

                for (r in 0 until reps) {
                    val inIdx = vCodec.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val ib = vCodec.getInputBuffer(inIdx)
                        if (ib != null) {
                            ib.clear()
                            ib.put(yuv)
                            vCodec.queueInputBuffer(inIdx, 0, yuv.size, pts, 0)
                            pts += frameDur
                        }
                    }
                    drainVideo(false)
                }
                onProgress(i + 1, count)
            }

            val vEnd = vCodec.dequeueInputBuffer(50_000)
            if (vEnd >= 0) {
                vCodec.queueInputBuffer(vEnd, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainVideo(true)

            if (started) {
                try { muxer.stop() } catch (e: Exception) {}
            }

            if (outFile.exists() && outFile.length() > 2000) Pair(true, "ok")
            else Pair(false, "file chhoti hai (" + outFile.length() + " bytes)")
        } catch (e: Exception) {
            Pair(false, (e.message ?: "encoder error"))
        } finally {
            try { vCodec?.stop() } catch (e: Exception) {}
            try { vCodec?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

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
                out[j * w + i] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
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

    /** WAV bytes -> (mono PCM shorts, sampleRate) */
    fun wavToPcm(wav: ByteArray): Pair<ShortArray, Int>? {
        return try {
            var pos = 12
            var rate = 22050
            var channels = 1
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= wav.size) {
                val id = String(wav, pos, 4, Charsets.US_ASCII)
                val sz = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    channels = ByteBuffer.wrap(wav, pos + 10, 2)
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    rate = ByteBuffer.wrap(wav, pos + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                } else if (id == "data") {
                    dataOff = pos + 8
                    dataLen = if (sz > 0 && pos + 8 + sz <= wav.size) sz else wav.size - (pos + 8)
                    break
                }
                pos += 8 + sz + (sz % 2)
            }
            if (dataOff < 0 || dataLen <= 0) return null

            val bb = ByteBuffer.wrap(wav, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN)
            val total = dataLen / 2
            val mono = if (channels <= 1) {
                ShortArray(total) { bb.getShort(it * 2) }
            } else {
                val frames = total / channels
                ShortArray(frames) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += bb.getShort((i * channels + c) * 2)
                    (sum / channels).toShort()
                }
            }
            Pair(mono, rate)
        } catch (e: Exception) { null }
    }
}
