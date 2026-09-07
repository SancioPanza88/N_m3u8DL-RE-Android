package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Facade che orchestra il flusso N_m3u8DL-RE:
 * fetch playlist -> auto-select best video/audio/subs -> download segmenti ->
 * decrypt -> merge -> **mux automatico in un unico file** (come -M dell'originale).
 *
 * Output: `<nome>.mp4` (video+audio) + `<nome>.srt` fianco a fianco (i player
 * Android/ChromeOS caricano il sidecar in automatico). Niente più merge a mano.
 * Output in Download/N_m3u8DL-RE-Android (visibile da Files su ChromeOS/ARCVM).
 */
class DownloaderFacade(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .build(),
    private val segDownloader: SegmentDownloader = SegmentDownloader(client)
) {

    sealed interface Result {
        data class Ok(val file: File, val segments: Int, val log: String, val subsFile: File? = null) : Result
        data class Err(val message: String) : Result
    }

    suspend fun run(opts: DownloadOptions, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            try {
                val playlistText = fetchText(opts.url, opts.headers)
                val isMpd = playlistText.trimStart().startsWith("<") &&
                    (playlistText.contains("<MPD") || opts.url.contains(".mpd", ignoreCase = true))
                val log = StringBuilder()
                val base = sanitize(opts.saveName.ifBlank { "video" })
                val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads")
                outDir.mkdirs()
                val tmpRoot = File(context.cacheDir, "nm3u8dl_tmp/$base")

                if (isMpd) {
                    runDash(opts, playlistText, base, outDir, tmpRoot, log, onProgress)
                } else {
                    runHls(opts, playlistText, base, outDir, tmpRoot, log, onProgress)
                }
            } catch (t: Throwable) {
                Result.Err(t.message ?: "Errore sconosciuto")
            }
        }

    // ---------- DASH ----------

    private suspend fun runDash(
        opts: DownloadOptions,
        mpdXml: String,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val streams = DashParser.parse(mpdXml, opts.url)
        if (streams.isEmpty()) return Result.Err("MPD senza stream scaricabili")
        val video = streams.filter {
            it.mimeType.contains("video") || it.codecs.contains("avc", true) ||
                it.codecs.contains("hev", true) || it.codecs.contains("vp9", true) ||
                it.codecs.contains("av01", true)
        }.maxByOrNull { it.bandwidth } ?: streams.maxByOrNull { it.bandwidth }!!
        val audio = if (opts.includeAudio) {
            streams.filter { it.mimeType.contains("audio") || it.codecs.contains("mp4a", true) }
                .maxByOrNull { it.bandwidth }?.takeIf { it.segments != video.segments }
        } else null
        log.appendLine("DASH: ${streams.size} stream, video bw=${video.bandwidth}" +
            (audio?.let { ", audio bw=${it.bandwidth}" } ?: ", solo video"))

        val threads = effectiveThreads(opts)
        val vSegs = video.segments.take(300).mapIndexed { i, u -> Segment(i, u) }
        val aSegs = audio?.segments?.take(300)?.mapIndexed { i, u -> Segment(i, u) } ?: emptyList()
        val total = vSegs.size + aSegs.size
        val (customKey, customIv) = customKeyIv(opts)

        val vFiles = segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
            File(tmpRoot, "video"), newKeyCache(opts), customKey, customIv) { p -> onProgress(p.done, total) }
        var aFiles: List<File> = emptyList()
        if (aSegs.isNotEmpty()) {
            aFiles = segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                File(tmpRoot, "audio"), newKeyCache(opts), customKey, customIv) { p -> onProgress(vSegs.size + p.done, total) }
        }
        if (opts.skipMerge) return Result.Ok(tmpRoot, total, log.toString())

        val videoTs = Muxer.mergeTs(vFiles, File(tmpRoot, "video.ts"), opts.binaryMerge)
        val audioTs = if (aFiles.isNotEmpty()) {
            Muxer.mergeTs(aFiles, File(tmpRoot, "audio.ts"), opts.binaryMerge)
        } else null
        return finishWithMux(opts, base, outDir, tmpRoot, videoTs, audioTs, null, null, total, log)
    }

    // ---------- HLS ----------

    private suspend fun runHls(
        opts: DownloadOptions,
        playlistText: String,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val pl = HlsParser.parse(playlistText, opts.url)
        if (!pl.isMaster) {
            log.appendLine("HLS media: ${pl.segments.size} segmenti (live=${pl.isLive})")
            if (pl.segments.isEmpty()) return Result.Err("Nessun segmento trovato")
            val threads = effectiveThreads(opts)
            val files = segDownloader.downloadAll(pl.segments, opts.headers, threads,
                opts.retryCount, File(tmpRoot, "video"), newKeyCache(opts),
                customKeyIv(opts).first, customKeyIv(opts).second) { p ->
                onProgress(p.done, pl.segments.size)
            }
            if (opts.skipMerge) return Result.Ok(tmpRoot, files.size, log.toString())
            val merged = Muxer.mergeTs(files, File(tmpRoot, "video.ts"), opts.binaryMerge)
            return finishWithMux(opts, base, outDir, tmpRoot, merged, null, null, null,
                files.size, log)
        }

        // Master: video migliore + audio del suo gruppo + sottotitoli (pref. italiano).
        val best = if (opts.autoSelectBest) HlsParser.selectBestVariant(pl.variants)
        else pl.variants.firstOrNull()
            ?: return Result.Err("Master playlist senza varianti")
        log.appendLine("HLS master: ${pl.variants.size} varianti, video bw=${best.bandwidth} ${best.resolution}")
        val audioTrack = if (opts.includeAudio) {
            HlsParser.selectAudioTrack(pl.audioTracks, best.audioGroup)
        } else null
        val subsTrack = if (opts.includeSubtitles) {
            HlsParser.selectSubtitleTrack(pl.subtitleTracks, best.subtitleGroup, "it")
        } else null
        audioTrack?.let { log.appendLine("Audio: ${it.name} (${it.language.ifEmpty { "?" }})") }
            ?: log.appendLine("Audio: incorporato nel video o assente")
        subsTrack?.let { log.appendLine("Sottotitoli: ${it.name} (${it.language.ifEmpty { "?" }})") }

        val videoPl = HlsParser.parse(fetchText(best.uri, opts.headers), best.uri)
        val audioPl = audioTrack?.let { HlsParser.parse(fetchText(it.uri, opts.headers), it.uri) }
        val vSegs = videoPl.segments
        val aSegs = audioPl?.segments ?: emptyList()
        if (vSegs.isEmpty()) return Result.Err("Playlist video senza segmenti")
        val total = vSegs.size + aSegs.size + 1

        val threads = effectiveThreads(opts)
        val keys = newKeyCache(opts)
        val (customKey, customIv) = customKeyIv(opts)
        val vFiles = segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
            File(tmpRoot, "video"), keys, customKey, customIv) { p -> onProgress(p.done, total) }
        var aFiles: List<File> = emptyList()
        if (aSegs.isNotEmpty()) {
            aFiles = segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                File(tmpRoot, "audio"), keys, customKey, customIv) { p -> onProgress(vSegs.size + p.done, total) }
        }
        // Sottotitoli: segmenti VTT (piccoli, fetch diretto) -> unico SRT.
        var subsFile: File? = null
        if (subsTrack != null) {
            subsFile = downloadSubtitles(subsTrack, opts, File(tmpRoot, "subs"), base, log)
            onProgress(total, total)
        }

        if (opts.skipMerge) return Result.Ok(tmpRoot, total, log.toString(), subsFile)
        val videoTs = Muxer.mergeTs(vFiles, File(tmpRoot, "video.ts"), opts.binaryMerge)
        val audioTs = if (aFiles.isNotEmpty()) {
            Muxer.mergeTs(aFiles, File(tmpRoot, "audio.ts"), opts.binaryMerge)
        } else null
        return finishWithMux(opts, base, outDir, tmpRoot, videoTs, audioTs,
            subsFile?.readText(), subsTrack?.language?.ifEmpty { "it" } ?: "it",
            total, log)
    }

    /** Scarica la playlist subs (VTT o m3u8 di .vtt) e la riduce a un unico SRT. */
    private fun downloadSubtitles(
        track: MediaTrack,
        opts: DownloadOptions,
        tmpDir: File,
        base: String,
        log: StringBuilder
    ): File? {
        return try {
            tmpDir.mkdirs()
            val body = fetchText(track.uri, opts.headers)
            val vtts: List<String> = if (body.contains("#EXTM3U")) {
                val subPl = HlsParser.parse(body, track.uri)
                if (subPl.segments.isEmpty()) return null
                subPl.segments.map { fetchText(it.uri, opts.headers) }
            } else {
                listOf(body)
            }
            val srt = SubtitleUtils.mergeVttSegmentsToSrt(vtts)
            if (srt.isBlank()) {
                log.appendLine("Sottotitoli: nessun cue valido, saltati")
                return null
            }
            val out = File(tmpDir, "$base.srt")
            out.writeText(srt)
            log.appendLine("Sottotitoli: ${vtts.size} segmenti -> SRT")
            out
        } catch (t: Throwable) {
            log.appendLine("Sottotitoli non scaricati: ${t.message}")
            null
        }
    }

    /**
     * Finale: se muxAfterDone => unico .mp4 (+ .srt fianco a fianco).
     * Altrimenti (opzione spenta) => file separati come il programma PC.
     */
    private fun finishWithMux(
        opts: DownloadOptions,
        base: String,
        outDir: File,
        tmpRoot: File,
        videoTs: File,
        audioTs: File?,
        subsSrtText: String?,
        subsLang: String?,
        total: Int,
        log: StringBuilder
    ): Result {
        val subsOut = if (subsSrtText != null) {
            val f = File(outDir, "$base.${subsLang ?: "it"}.srt")
            f.writeText(subsSrtText)
            f
        } else null

        if (!opts.muxAfterDone) {
            // Modalità "file separati" come il programma per PC.
            val vOut = File(outDir, "${base}_video.ts").also { videoTs.copyTo(it, overwrite = true) }
            audioTs?.let { File(outDir, "${base}_audio.ts").also { o -> it.copyTo(o, overwrite = true) } }
            log.appendLine("Mux automatico SPENTO: file separati in ${outDir.absolutePath}")
            cleanup(tmpRoot)
            return Result.Ok(vOut, total, log.toString(), subsOut)
        }

        val mp4 = File(outDir, "$base.mp4")
        val ok = AutoMuxer.remuxToMp4(videoTs, audioTs, mp4)
        return if (ok) {
            log.appendLine("Mux automatico OK: ${mp4.absolutePath}" +
                (subsOut?.let { " + ${it.name}" } ?: " (senza sottotitoli)"))
            cleanup(tmpRoot)
            Result.Ok(mp4, total, log.toString(), subsOut)
        } else {
            // Fallback: codec non supportato da MediaMuxer -> tieni i separati, niente lavoro perso.
            val vOut = File(outDir, "${base}_video.ts").also { videoTs.copyTo(it, overwrite = true) }
            audioTs?.let { File(outDir, "${base}_audio.ts").also { o -> it.copyTo(o, overwrite = true) } }
            log.appendLine("Mux automatico non riuscito (codec non supportato): tenuti i file separati in ${outDir.absolutePath}")
            cleanup(tmpRoot)
            Result.Ok(vOut, total, log.toString(), subsOut)
        }
    }

    private fun cleanup(tmpRoot: File) {
        try { tmpRoot.deleteRecursively() } catch (_: Throwable) {}
    }

    private fun effectiveThreads(opts: DownloadOptions): Int =
        if (opts.threadCount <= 0) defaultThreadCountForChromebookPlus() else opts.threadCount

    private fun newKeyCache(opts: DownloadOptions): MutableMap<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        opts.customHlsKeyHex?.let {
            try { m["__custom"] = AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Throwable) {}
        }
        return m
    }

    private fun customKeyIv(opts: DownloadOptions): Pair<ByteArray?, ByteArray?> {
        val k = opts.customHlsKeyHex?.let {
            try { AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Throwable) { null }
        }
        val iv = opts.customHlsIvHex?.let {
            try { AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Throwable) { null }
        }
        return k to iv
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video" }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.addHeader(k, v)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} su $url")
            return r.body?.string() ?: throw RuntimeException("Body vuoto su $url")
        }
    }
}
