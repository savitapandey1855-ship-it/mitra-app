package com.mitra.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Bitmaps -> MP4 (H.264) with a gentle Ken Burns zoom. */
object Mp4Maker {

    fun encode(
        frames: List<Bitmap>,
        secondsEach: Float,
        outFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Boolean {
        if (frames.isEmpty()) return false
        var codec: MediaCodec? = null
        var surface: android.view.Surface? = null
        var muxer: MediaMuxer? = null
        return try {
            var w = frames[0].width
            var h = frames[0].height
            if (w % 2 != 0) w -= 1
            if (h % 2 != 0) h -= 1

            val fps = 24
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, w * h * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            val frameDur = 1_000_000L / fps

            fun drain(endOfStream: Boolean) {
                val status = arrayOf(MediaCodec.BufferInfo())
                while (true) {
                    val idx = codec!!.dequeueOutputBuffer(status[0], 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer!!.addTrack(codec!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                    } else if (idx >= 0) {
                        val buf = codec!!.getOutputBuffer(idx) ?: continue
                        if (buf.position() > 0 || buf.size() > 0) {
                            if (status[0].flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                status[0].size = 0
                            }
                            if (status[0].size > 0 && muxerStarted) {
                                buf.position(status[0].offset)
                                buf.limit(status[0].offset + status[0].size)
                                muxer!!.writeSampleData(trackIndex, buf, status[0])
                            }
                        }
                        codec!!.releaseOutputBuffer(idx, false)
                        if (status[0].flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            for ((fi, bmp) in frames.withIndex()) {
                val count = (secondsEach * fps).toInt().coerceAtLeast(12)
                for (f in 0 until count) {
                    val canvas: Canvas = surface.lockCanvas(null)
                    canvas.drawColor(Color.BLACK)
                    val zoom = 1f + 0.06f * (f.toFloat() / count)
                    val m = Matrix().apply { setScale(zoom, zoom, w / 2f, h / 2f) }
                    canvas.drawBitmap(bmp, m, null)
                    surface.unlockCanvasAndPost(canvas)

                    val bufIdx = codec.dequeueInputBuffer(10_000)
                    if (bufIdx >= 0) {
                        codec.queueInputBuffer(bufIdx, 0, 0, pts, 0)
                        pts += frameDur
                    }
                    drain(false)
                }
                onProgress(fi + 1, frames.size)
            }

            codec.signalEndOfInputStream()
            drain(true)

            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) {}
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { surface?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }
}
