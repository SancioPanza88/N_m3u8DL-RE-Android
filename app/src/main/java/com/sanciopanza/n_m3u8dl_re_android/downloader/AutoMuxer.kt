package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Mux automatico video+audio in un unico .mp4 (equivalente di `-M` / `--mux-after-done`
 * di N_m3u8DL-RE), senza ricodifica: i campioni vengono copiati 1:1 con
 * [MediaMuxer], quindi gira a velocità I/O anche su Chromebook base e sfrutta
 * il demuxer HW del SoC. Nessuna dipendenza ffmpeg nativa.
 *
 * - [remuxToMp4]: unisce video.ts + audio.ts (o due .mp4 fmp4) in un unico .mp4.
 * - [remuxSingleToMp4]: converte un singolo .ts muxato (video+audio insieme) in .mp4.
 * Ritornano true a successo, false se i codec non sono supportati (il chiamante
 * tiene allora i file separati e lo segnala nel log).
 */
object AutoMuxer {

    fun remuxToMp4(videoFile: File, audioFile: File?, outMp4: File): Boolean {
        return try {
            outMp4.parentFile?.mkdirs()
            if (outMp4.exists()) outMp4.delete()
            if (audioFile == null || !audioFile.exists()) {
                remuxSingleToMp4(videoFile, outMp4)
            } else {
                combine(videoFile, audioFile, outMp4)
            }
            outMp4.exists() && outMp4.length() > 0
        } catch (_: Throwable) {
            try { if (outMp4.exists() && outMp4.length() == 0L) outMp4.delete() } catch (_: Throwable) {}
            false
        }
    }

    fun remuxSingleToMp4(inFile: File, outMp4: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            outMp4.parentFile?.mkdirs()
            if (outMp4.exists()) outMp4.delete()
            extractor = MediaExtractor()
            extractor.setDataSource(inFile.absolutePath)
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val indexMap = addTracks(extractor, muxer, acceptMime = null)
            if (indexMap.isEmpty()) return false
            muxer.start()
            copyAllSamples(extractor, muxer, indexMap)
            true
        } catch (_: Throwable) {
            try { if (outMp4.exists() && outMp4.length() == 0L) outMp4.delete() } catch (_: Throwable) {}
            false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun combine(videoFile: File, audioFile: File, outMp4: File): Boolean {
        var vExt: MediaExtractor? = null
        var aExt: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            vExt = MediaExtractor()
            vExt.setDataSource(videoFile.absolutePath)
            aExt = MediaExtractor()
            aExt.setDataSource(audioFile.absolutePath)
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vMap = addTracks(vExt, muxer) { mime -> mime.startsWith("video/") }
            val aMap = addTracks(aExt, muxer) { mime -> mime.startsWith("audio/") }
            if (vMap.isEmpty() && aMap.isEmpty()) return false
            muxer.start()

            // Copia interlacciata per presentationTime: il player non deve seekare avanti/indietro.
            val vInfo = if (vMap.isNotEmpty())SrcState(vExt, vMap) else null
            val aInfo = if (aMap.isNotEmpty())SrcState(aExt, aMap) else null
            vInfo?.advance()
            aInfo?.advance()
            while (vInfo?.hasSample == true || aInfo?.hasSample == true) {
                val takeVideo = when {
                    vInfo?.hasSample != true -> false
                    aInfo?.hasSample != true -> true
                    else -> vInfo.sampleTime <= aInfo.sampleTime
                }
                if (takeVideo) vInfo!!.writeSample(muxer) else aInfo!!.writeSample(muxer)
            }
            true
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { vExt?.release() } catch (_: Throwable) {}
            try { aExt?.release() } catch (_: Throwable) {}
        }
    }

    private fun addTracks(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        acceptMime: ((String) -> Boolean)? = null
    ): Map<Int, Int> {
        val map = LinkedHashMap<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format: MediaFormat = extractor.getTrackFormat(i)
            val mime: String = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("text/") || mime.startsWith("application/")) continue
            if (acceptMime != null && !acceptMime(mime)) continue
            extractor.selectTrack(i)
            map[i] = muxer.addTrack(format)
        }
        return map
    }

    private class SrcState(
        val extractor: MediaExtractor,
        val indexMap: Map<Int, Int>
    ) {
        val buffer: ByteBuffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var hasSample = false
        var sampleTime = 0L
        var muxerTrack = 0

        fun advance() {
            hasSample = try {
                val sz = extractor.readSampleData(buffer, 0)
                if (sz < 0) {
                    false
                } else {
                    info.offset = 0
                    info.size = sz
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    sampleTime = extractor.sampleTime
                    muxerTrack = indexMap[extractor.sampleTrackIndex] ?: 0
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun writeSample(muxer: MediaMuxer) {
            try {
                muxer.writeSampleData(muxerTrack, buffer, info)
            } catch (_: Throwable) {
                // Campione corrotto: salta senza abortire tutto il mux.
            }
            try { extractor.advance() } catch (_: Throwable) {}
            advance()
        }
    }

    private fun copyAllSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        indexMap: Map<Int, Int>
    ) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val track = try { extractor.sampleTrackIndex } catch (_: Throwable) { -1 }
            if (track < 0) break
            val muxerTrack = indexMap[track]
            if (muxerTrack == null) {
                try { extractor.advance() } catch (_: Throwable) { break }
                continue
            }
            val size = try { extractor.readSampleData(buffer, 0) } catch (_: Throwable) { -1 }
            if (size < 0) break // fine stream
            info.offset = 0
            info.size = size
            try {
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
            } catch (_: Throwable) {
                // Vai avanti: un campione guasto non deve buttare tutto il file.
            }
            try { extractor.advance() } catch (_: Throwable) { break }
        }
    }
}
