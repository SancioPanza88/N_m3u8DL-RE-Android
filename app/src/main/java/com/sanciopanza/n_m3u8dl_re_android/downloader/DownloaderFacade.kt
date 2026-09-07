package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Facade che orchestra il flusso N_m3u8DL-RE con le STESSE opzioni dell'originale
 * (stessi nomi/flag: vedi OptionLogic.toCliCommand e README "Tabella parità").
 * fetch playlist -> select/drop regex -> download segmenti -> decrypt ->
 * merge -> **mux automatico in un unico file** (-M).
 *
 * Output: `<nome>.mp4` (video+audio) + `<nome>.srt/.vtt` fianco a fianco.
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

    private lateinit var http: OkHttpClient
    private lateinit var opts: DownloadOptions
    private lateinit var keyMap: Map<String, String>

    suspend fun run(o: DownloadOptions, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            try {
                opts = o
                http = buildHttpClient(o)
                segDownloader.client = http
                keyMap = OptionLogic.parseKeyEntries(o.keyEntries, o.keyTextFileContent)

                // --task-start-at
                val waitMs = OptionLogic.parseTaskStartAt(o.taskStartAt) - System.currentTimeMillis()
                if (waitMs > 0) {
                    Thread.sleep(waitMs.coerceAtMost(6 * 3600 * 1000L))
                }

                val playlistText = fetchText(o.url, o.headers)
                val isMpd = playlistText.trimStart().startsWith("<") &&
                    (playlistText.contains("<MPD") || o.url.contains(".mpd", ignoreCase = true))
                val log = StringBuilder()
                log.appendLine("[${o.logLevel}] N_m3u8DL-RE Android <Equivalent: ${OptionLogic.toCliCommand(o)}>")
                val base = sanitize(o.saveName.ifBlank { "video" })
                val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads")
                outDir.mkdirs()
                val tmpRoot = File(context.cacheDir, "nm3u8dl_tmp/$base")

                val r = if (isMpd) {
                    runDash(playlistText, base, outDir, tmpRoot, log, onProgress)
                } else {
                    runHls(playlistText, base, outDir, tmpRoot, log, onProgress)
                }
                // --no-log / log file (l'originale scrive il log su file di default)
                if (!o.noLog && r is Result.Ok) {
                    try { File(outDir, "$base.log").writeText(r.log) } catch (_: Throwable) {}
                }
                r
            } catch (t: Throwable) {
                Result.Err(t.message ?: "Errore sconosciuto")
            }
        }

    private fun buildHttpClient(o: DownloadOptions): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(o.httpTimeoutSec.coerceAtLeast(5).toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        val proxy = o.customProxy.trim()
        if (proxy.isNotEmpty()) {
            try {
                val u = URL(if (proxy.contains("://")) proxy else "http://$proxy")
                val port = if (u.port > 0) u.port else 8080
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, port)))
            } catch (_: Exception) {
            }
        }
        return b.build()
    }

    private fun maxBps(): Long =
        if (opts.maxSpeedKbps > 0) opts.maxSpeedKbps * 1000L / 8
        else OptionLogic.parseMaxSpeedToBps(opts.maxSpeed)

    private fun effectiveBase(fallback: String): String =
        opts.baseUrl.trim().ifEmpty { fallback }

    /** --append-url-params: accoda i param dell'URL input ai segmenti (utile es. kakao.com). */
    private fun applyUrlParams(segs: List<Segment>): List<Segment> {
        if (!opts.appendUrlParams) return segs
        val q = try { URL(opts.url).query } catch (_: Exception) { null }
        if (q.isNullOrEmpty()) return segs
        return segs.map { s ->
            val sep = if (s.uri.contains("?")) "&" else "?"
            s.copy(uri = s.uri + sep + q)
        }
    }

    private fun applyRangeAndAds(segs: List<Segment>): List<Segment> =
        OptionLogic.filterAdSegments(OptionLogic.applyCustomRange(segs, opts.customRange), opts.adKeyword)

    /** --custom-hls-method NONE: nessun KEY (scarica i byte così come sono). */
    private fun applyCustomMethod(segs: List<Segment>): List<Segment> {
        val m = opts.customHlsMethod.trim().uppercase()
        if (m.isEmpty() || m == "UNKNOWN") return segs
        if (m == "NONE") return segs.map { it.copy(keyUri = null, ivHex = null) }
        return segs // AES_128/CENC/ecc: chiavi da playlist o --key/--custom-hls-key
    }

    // ---------- DASH ----------

    private suspend fun runDash(
        mpdXml: String,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val streams = DashParser.parse(mpdXml, opts.url)
        if (streams.isEmpty()) return Result.Err("MPD senza stream scaricabili")
        // -sv/-dv su "bandwidth codecs mime"
        val videoPool = streams.filter { s ->
            val t = "${s.bandwidth} ${s.codecs} ${s.mimeType} ${s.width}x${s.height}"
            val sel = OptionLogic.matchSelect(opts.selectVideo, t)
            val drop = OptionLogic.matchSelect(opts.dropVideo, t) == true
            (sel == null || sel) && !drop &&
                (s.mimeType.contains("video") || s.codecs.contains("avc", true) ||
                    s.codecs.contains("hev", true) || s.codecs.contains("vp9", true) ||
                    s.codecs.contains("av01", true))
        }
        val video = (if (opts.autoSelectBest && opts.selectVideo.isEmpty()) {
            videoPool.maxByOrNull { it.bandwidth }
        } else {
            videoPool.firstOrNull()
        } ?: videoPool.maxByOrNull { it.bandwidth }) ?: streams.maxByOrNull { it.bandwidth }!!
        val audio = if (opts.includeAudio && !opts.subOnly) {
            val pool = streams.filter { s ->
                val t = "${s.bandwidth} ${s.codecs} ${s.mimeType}"
                val sel = OptionLogic.matchSelect(opts.selectAudio, t)
                val drop = OptionLogic.matchSelect(opts.dropAudio, t) == true
                (sel == null || sel) && !drop &&
                    (s.mimeType.contains("audio") || s.codecs.contains("mp4a", true))
            }
            (if (opts.autoSelectBest && opts.selectAudio.isEmpty()) pool.maxByOrNull { it.bandwidth }
            else pool.firstOrNull())?.takeIf { it.segments != video.segments }
        } else null
        if (opts.subOnly) {
            return Result.Err("--sub-only: DASH senza sottotitoli testuali separati in questo port (usa HLS per i sub)")
        }
        log.appendLine("DASH: ${streams.size} stream, video bw=${video.bandwidth}" +
            (audio?.let { ", audio bw=${it.bandwidth}" } ?: ", solo video"))

        val threads = effectiveThreads()
        val vSegs = applyRangeAndAds(applyCustomMethod(
            video.segments.take(300).mapIndexed { i, u -> Segment(i, u) }))
        val aSegs = applyRangeAndAds(audio?.segments?.take(300)
            ?.mapIndexed { i, u -> Segment(i, u) } ?: emptyList())
        if (vSegs.isEmpty()) return Result.Err("Nessun segmento video (custom-range/ad-keyword troppo stretti?)")
        val total = vSegs.size + aSegs.size
        val (customKey, customIv) = customKeyIv()

        if (opts.skipDownload) {
            writeMetaJson(outDir, base, "DASH", video, audio, vSegs.size + aSegs.size)
            return Result.Ok(tmpRoot, total, log.toString())
        }
        val vFiles: List<File>
        var aFiles: List<File> = emptyList()
        if (opts.concurrentDownload && aSegs.isNotEmpty()) {
            val done = AtomicInteger(0)
            coroutineScope {
                val jv = async {
                    segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
                        File(tmpRoot, "video"), newKeyCache(), customKey, customIv,
                        maxBps(), keyMap) { onProgress(done.incrementAndGet(), total) }
                }
                val ja = async {
                    segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                        File(tmpRoot, "audio"), newKeyCache(), customKey, customIv,
                        maxBps(), keyMap) { onProgress(done.incrementAndGet(), total) }
                }
                vFiles = jv.await()
                aFiles = ja.await()
            }
        } else {
            vFiles = segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
                File(tmpRoot, "video"), newKeyCache(), customKey, customIv,
                maxBps(), keyMap) { p -> onProgress(p.done, total) }
            if (aSegs.isNotEmpty()) {
                aFiles = segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                    File(tmpRoot, "audio"), newKeyCache(), customKey, customIv,
                    maxBps(), keyMap) { p -> onProgress(vSegs.size + p.done, total) }
            }
        }
        if (opts.checkSegmentsCount && vFiles.size != vSegs.size) {
            return Result.Err("--check-segments-count: attesi ${vSegs.size}, scaricati ${vFiles.size}")
        }
        if (opts.skipMerge) return Result.Ok(tmpRoot, total, log.toString())

        val videoTs = Muxer.mergeTs(vFiles, File(tmpRoot, "video.ts"), opts.binaryMerge)
        val audioTs = if (aFiles.isNotEmpty()) {
            Muxer.mergeTs(aFiles, File(tmpRoot, "audio.ts"), opts.binaryMerge)
        } else null
        if (opts.writeMetaJson) writeMetaJson(outDir, base, "DASH", video, audio, total)
        return finishWithMux(base, outDir, tmpRoot, videoTs, audioTs, null, null,
            total, log, video, audio)
    }

    // ---------- HLS ----------

    private suspend fun runHls(
        playlistText: String,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val pl = HlsParser.parse(playlistText, effectiveBase(opts.url))
        if (!pl.isMaster) {
            return runHlsMedia(pl, playlistText, base, outDir, tmpRoot, log, onProgress,
                audioGroup = "", subsGroup = "", isLiveMedia = pl.isLive, mediaUrl = opts.url)
        }

        // Master: -sv/-dv sulle varianti, -sa/-da/-ss/-ds sulle tracce.
        val pool = pl.variants.filter { v ->
            val t = OptionLogic.variantText(v)
            val sel = OptionLogic.matchSelect(opts.selectVideo, t)
            val drop = OptionLogic.matchSelect(opts.dropVideo, t) == true
            (sel == null || sel) && !drop
        }
        if (pool.isEmpty()) return Result.Err("Nessuna variante video dopo -sv/-dv")
        val best = (if (opts.autoSelectBest && opts.selectVideo.isEmpty()) {
            HlsParser.selectBestVariant(pool)
        } else {
            pool.firstOrNull()
        }) ?: pool.maxByOrNull { it.bandwidth }!!
        log.appendLine("HLS master: ${pl.variants.size} varianti, video bw=${best.bandwidth} ${best.resolution}")

        val audioTrack = if (opts.includeAudio && !opts.subOnly) {
            val tracks = pl.audioTracks.filter { t ->
                val sel = OptionLogic.matchSelect(opts.selectAudio, OptionLogic.trackText(t))
                val drop = OptionLogic.matchSelect(opts.dropAudio, OptionLogic.trackText(t)) == true
                (sel == null || sel) && !drop
            }
            HlsParser.selectAudioTrack(tracks, best.audioGroup)
        } else null
        val subsTrack = if (opts.includeSubtitles && !opts.muxSkipSub) {
            val tracks = pl.subtitleTracks.filter { t ->
                val sel = OptionLogic.matchSelect(opts.selectSubtitle, OptionLogic.trackText(t))
                val drop = OptionLogic.matchSelect(opts.dropSubtitle, OptionLogic.trackText(t)) == true
                (sel == null || sel) && !drop
            }
            HlsParser.selectSubtitleTrack(tracks, best.subtitleGroup, "it")
        } else null

        if (opts.subOnly) {
            val st = subsTrack ?: return Result.Err("--sub-only: nessun sottotitolo trovato")
            val sf = downloadSubtitles(st, File(tmpRoot, "subs"), base, log)
            onProgress(1, 1)
            if (sf == null) return Result.Err("--sub-only: download sottotitoli fallito")
            val ext = if (opts.subFormat.equals("VTT", true)) "vtt" else "it.srt"
            val out = File(outDir, "$base.$ext").also { sf.copyTo(it, overwrite = true) }
            if (opts.writeMetaJson) writeMetaJson(outDir, base, "HLS-sub-only", null, null, 1, st)
            if (opts.delAfterDone) cleanup(tmpRoot)
            log.appendLine("Sub-only OK: ${out.absolutePath}")
            return Result.Ok(out, 1, log.toString(), out)
        }

        audioTrack?.let { log.appendLine("Audio: ${it.name} (${it.language.ifEmpty { "?" }})") }
            ?: log.appendLine("Audio: incorporato nel video o assente")
        subsTrack?.let { log.appendLine("Sottotitoli: ${it.name} (${it.language.ifEmpty { "?" }})") }

        val videoPl = HlsParser.parse(fetchText(best.uri, opts.headers), effectiveBase(best.uri))
        val audioPl = audioTrack?.let { HlsParser.parse(fetchText(it.uri, opts.headers), effectiveBase(it.uri)) }
        val isLive = videoPl.isLive && !opts.livePerformAsVod

        if (isLive) {
            return runHlsLive(videoPl, best.uri, audioPl, audioTrack?.uri ?: "",
                subsTrack, base, outDir, tmpRoot, log, onProgress, best)
        }

        val vSegs = applyRangeAndAds(applyCustomMethod(videoPl.segments))
        val aSegs = applyRangeAndAds(applyCustomMethod(audioPl?.segments ?: emptyList()))
        if (vSegs.isEmpty()) return Result.Err("Playlist video senza segmenti (range/ad-keyword?)")
        val total = vSegs.size + aSegs.size + 1

        val threads = effectiveThreads()
        val keys = newKeyCache()
        val (customKey, customIv) = customKeyIv()
        val vFiles: List<File>
        var aFiles: List<File> = emptyList()
        if (opts.skipDownload) {
            writeMetaJson(outDir, base, "HLS", best, audioTrack, total, subsTrack)
            return Result.Ok(tmpRoot, total, log.toString())
        }
        if (opts.concurrentDownload && aSegs.isNotEmpty()) {
            val done = AtomicInteger(0)
            coroutineScope {
                val jv = async {
                    segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
                        File(tmpRoot, "video"), keys, customKey, customIv,
                        maxBps(), keyMap) { onProgress(done.incrementAndGet(), total) }
                }
                val ja = async {
                    segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                        File(tmpRoot, "audio"), keys, customKey, customIv,
                        maxBps(), keyMap) { onProgress(done.incrementAndGet(), total) }
                }
                vFiles = jv.await()
                aFiles = ja.await()
            }
        } else {
            vFiles = segDownloader.downloadAll(vSegs, opts.headers, threads, opts.retryCount,
                File(tmpRoot, "video"), keys, customKey, customIv,
                maxBps(), keyMap) { p -> onProgress(p.done, total) }
            if (aSegs.isNotEmpty()) {
                aFiles = segDownloader.downloadAll(aSegs, opts.headers, threads, opts.retryCount,
                    File(tmpRoot, "audio"), keys, customKey, customIv,
                    maxBps(), keyMap) { p -> onProgress(vSegs.size + p.done, total) }
            }
        }
        if (opts.checkSegmentsCount && vFiles.size != vSegs.size) {
            return Result.Err("--check-segments-count: attesi ${vSegs.size}, scaricati ${vFiles.size}")
        }
        var subsFile: File? = null
        if (subsTrack != null) {
            subsFile = downloadSubtitles(subsTrack, File(tmpRoot, "subs"), base, log)
            onProgress(total, total)
        }

        if (opts.skipMerge) return Result.Ok(tmpRoot, total, log.toString(), subsFile)
        val videoTs = Muxer.mergeTs(vFiles, File(tmpRoot, "video.ts"), opts.binaryMerge)
        val audioTs = if (aFiles.isNotEmpty()) {
            Muxer.mergeTs(aFiles, File(tmpRoot, "audio.ts"), opts.binaryMerge)
        } else null
        if (!opts.liveKeepSegments) {
            try { File(tmpRoot, "video").deleteRecursively() } catch (_: Throwable) {}
            try { File(tmpRoot, "audio").deleteRecursively() } catch (_: Throwable) {}
        }
        if (opts.writeMetaJson) writeMetaJson(outDir, base, "HLS", best, audioTrack, total, subsTrack)
        return finishWithMux(base, outDir, tmpRoot, videoTs, audioTs,
            subsFile?.readText(), subsTrack?.language?.ifEmpty { "it" } ?: "it",
            total, log, best, audioTrack, subsTrack)
    }

    /** Playlist media diretta (non master). */
    private suspend fun runHlsMedia(
        pl: Playlist,
        playlistText: String,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit,
        audioGroup: String,
        subsGroup: String,
        isLiveMedia: Boolean,
        mediaUrl: String
    ): Result {
        if (isLiveMedia && !opts.livePerformAsVod) {
            return runHlsLive(pl, mediaUrl, null, "", null, base, outDir, tmpRoot,
                log, onProgress, null)
        }
        val segs = applyRangeAndAds(applyCustomMethod(pl.segments))
        log.appendLine("HLS media: ${segs.size} segmenti (live=${pl.isLive})")
        if (segs.isEmpty()) return Result.Err("Nessun segmento trovato")
        if (opts.skipDownload) {
            writeMetaJson(outDir, base, "HLS-media", null, null, segs.size)
            return Result.Ok(tmpRoot, segs.size, log.toString())
        }
        val threads = effectiveThreads()
        val (customKey, customIv) = customKeyIv()
        val files = segDownloader.downloadAll(segs, opts.headers, threads,
            opts.retryCount, File(tmpRoot, "video"), newKeyCache(),
            customKey, customIv, maxBps(), keyMap) { p ->
            onProgress(p.done, segs.size)
        }
        if (opts.checkSegmentsCount && files.size != segs.size) {
            return Result.Err("--check-segments-count: attesi ${segs.size}, scaricati ${files.size}")
        }
        if (opts.skipMerge) return Result.Ok(tmpRoot, files.size, log.toString())
        val merged = Muxer.mergeTs(files, File(tmpRoot, "video.ts"), opts.binaryMerge)
        if (opts.writeMetaJson) writeMetaJson(outDir, base, "HLS-media", null, null, files.size)
        return finishWithMux(base, outDir, tmpRoot, merged, null, null, null,
            files.size, log, null, null, null)
    }

    /**
     * --live-*: registrazione live HLS. Primo batch --live-take-count, poi polling
     * ogni --live-wait-time (o target-duration) fino a #EXT-X-ENDLIST o
     * --live-record-limit. Con --live-real-time-merge accoda via via nel .ts.
     */
    private suspend fun runHlsLive(
        firstPl: Playlist,
        videoMediaUrl: String,
        audioPl0: Playlist?,
        audioMediaUrl: String,
        subsTrack: MediaTrack?,
        base: String,
        outDir: File,
        tmpRoot: File,
        log: StringBuilder,
        onProgress: (Int, Int) -> Unit,
        best: StreamVariant?
    ): Result {
        val limitSec = OptionLogic.parseRecordLimitToSec(opts.liveRecordLimit)
        val startMs = System.currentTimeMillis()
        log.appendLine("LIVE: registrazione (take=${opts.liveTakeCount}, " +
            "wait=${opts.liveWaitTimeSec}s, limit=${opts.liveRecordLimit.ifEmpty { "no" }})")
        val videoTs = File(tmpRoot, "video.ts")
        if (opts.liveRealTimeMerge) {
            videoTs.parentFile?.mkdirs()
            if (videoTs.exists()) videoTs.delete()
        }
        val seen = LinkedHashSet<String>()
        val orderedFiles = mutableListOf<File>()
        var fileIdx = 0
        var done = 0
        var first = true
        val threads = effectiveThreads()
        val keys = newKeyCache()
        val (customKey, customIv) = customKeyIv()
        val vTmp = File(tmpRoot, "video").also { it.mkdirs() }

        while (true) {
            val text = fetchText(videoMediaUrl, opts.headers)
            val pl = HlsParser.parse(text, effectiveBase(videoMediaUrl))
            var fresh = pl.segments.filter { seen.add(it.uri) }
            if (first) {
                fresh = fresh.take(opts.liveTakeCount.coerceAtLeast(1))
                first = false
            }
            fresh = applyRangeAndAds(applyCustomMethod(fresh))
            if (fresh.isNotEmpty()) {
                val batch = fresh.map { it.copy(index = fileIdx++) }
                val files = segDownloader.downloadAll(batch, opts.headers, threads,
                    opts.retryCount, vTmp, keys, customKey, customIv, maxBps(), keyMap) { p ->
                    onProgress(done + p.done, done + batch.size)
                }
                done += batch.size
                onProgress(done, done)
                if (opts.liveRealTimeMerge) {
                    videoTs.appendBytes(files.sortedBy { it.name }.flatMap { it.readBytes().asList() }.toByteArray())
                    if (!opts.liveKeepSegments) files.forEach { try { it.delete() } catch (_: Throwable) {} }
                } else {
                    orderedFiles.addAll(files)
                }
            }
            val ended = text.contains("#EXT-X-ENDLIST")
            val overLimit = limitSec > 0 && (System.currentTimeMillis() - startMs) / 1000 >= limitSec
            if (ended || overLimit) {
                log.appendLine("LIVE: stop (${if (ended) "ENDLIST" else "record-limit"}) dopo $done segmenti")
                break
            }
            val waitSec = (if (opts.liveWaitTimeSec > 0) opts.liveWaitTimeSec
            else pl.targetDuration.takeIf { it > 0 } ?: 5).toLong()
            Thread.sleep(waitSec * 1000)
        }
        if (done == 0) return Result.Err("LIVE: nessun segmento registrato")

        val finalVideo: File = if (opts.liveRealTimeMerge) {
            videoTs
        } else {
            Muxer.mergeTs(orderedFiles.sortedBy { it.name }, videoTs, opts.binaryMerge)
        }
        // Audio live: un giro secco (best effort).
        var audioTs: File? = null
        if (audioPl0 != null && audioMediaUrl.isNotEmpty()) {
            try {
                val at = fetchText(audioMediaUrl, opts.headers)
                val ap = HlsParser.parse(at, effectiveBase(audioMediaUrl))
                val aSegs = applyRangeAndAds(ap.segments.take(300))
                if (aSegs.isNotEmpty()) {
                    val af = segDownloader.downloadAll(aSegs, opts.headers, threads,
                        opts.retryCount, File(tmpRoot, "audio"), keys, customKey, customIv,
                        maxBps(), keyMap) { p -> onProgress(done + p.done, done + aSegs.size) }
                    audioTs = Muxer.mergeTs(af, File(tmpRoot, "audio.ts"), opts.binaryMerge)
                }
            } catch (t: Throwable) {
                log.appendLine("LIVE audio saltato: ${t.message}")
            }
        }
        var subsFile: File? = null
        if (subsTrack != null) {
            subsFile = downloadSubtitles(subsTrack, File(tmpRoot, "subs"), base, log)
        }
        if (opts.skipMerge) return Result.Ok(tmpRoot, done, log.toString(), subsFile)
        if (opts.writeMetaJson) writeMetaJson(outDir, base, "HLS-live", best, null, done, subsTrack)
        return finishWithMux(base, outDir, tmpRoot, finalVideo, audioTs,
            subsFile?.readText(), subsTrack?.language?.ifEmpty { "it" } ?: "it",
            done, log, best, null, subsTrack)
    }

    /** Scarica la playlist subs (VTT o m3u8 di .vtt) e la riduce a un unico SRT/VTT. */
    private fun downloadSubtitles(
        track: MediaTrack,
        tmpDir: File,
        base: String,
        log: StringBuilder
    ): File? {
        return try {
            tmpDir.mkdirs()
            val body = fetchText(track.uri, opts.headers)
            if (!opts.autoSubtitleFix) {
                // --auto-subtitle-fix false: salva così com'è (singolo file o primo segmento).
                val raw = if (body.contains("#EXTM3U")) {
                    val subPl = HlsParser.parse(body, effectiveBase(track.uri))
                    if (subPl.segments.isEmpty()) return null
                    subPl.segments.joinToString("\n") { fetchText(it.uri, opts.headers) }
                } else body
                val ext = if (opts.subFormat.equals("VTT", true)) "vtt" else "srt"
                return File(tmpDir, "$base.$ext").also { it.writeText(raw) }
            }
            val vtts: List<String> = if (body.contains("#EXTM3U")) {
                val subPl = HlsParser.parse(body, effectiveBase(track.uri))
                if (subPl.segments.isEmpty()) return null
                // --live-fix-vtt-by-audio: best effort, i cue HLS sono già temporizzati;
                // normalizziamo comunque i timestamp (fix attivo di default).
                subPl.segments.map { fetchText(it.uri, opts.headers) }
            } else {
                listOf(body)
            }
            if (opts.subFormat.equals("VTT", true)) {
                val merged = vtts.joinToString("\n").let {
                    if (it.startsWith("WEBVTT")) it else "WEBVTT\n\n$it"
                }
                File(tmpDir, "$base.vtt").also { it.writeText(merged) }
            } else {
                val srt = SubtitleUtils.mergeVttSegmentsToSrt(vtts)
                if (srt.isBlank()) {
                    log.appendLine("Sottotitoli: nessun cue valido, saltati")
                    return null
                }
                File(tmpDir, "$base.srt").also { it.writeText(srt) }
            }.also {
                log.appendLine("Sottotitoli (${opts.subFormat.uppercase()}): ${vtts.size} segmenti")
            }
        } catch (t: Throwable) {
            log.appendLine("Sottotitoli non scaricati: ${t.message}")
            null
        }
    }

    /**
     * Finale -M/--mux-after-done: unico file (+ subs fianco a fianco).
     * Spento => file separati come il programma PC.
     */
    private fun finishWithMux(
        base: String,
        outDir: File,
        tmpRoot: File,
        videoTs: File,
        audioTs: File?,
        subsSrtText: String?,
        subsLang: String?,
        total: Int,
        log: StringBuilder,
        video: Any?,
        audio: Any?,
        subsTrack: MediaTrack? = null
    ): Result {
        val subsOut = if (subsSrtText != null && !opts.muxSkipSub) {
            val ext = if (opts.subFormat.equals("VTT", true)) "vtt" else "${subsLang ?: "it"}.srt"
            File(outDir, applyPattern(base, "SUBTITLES", "", 0, subsLang ?: "", ext)).also {
                it.writeText(subsSrtText)
            }
        } else null
        // --mux-import: file esterno copiato accanto all'output.
        opts.muxImportPath.trim().takeIf { it.isNotEmpty() }?.let { p ->
            try {
                val src = File(p)
                if (src.exists()) {
                    src.copyTo(File(outDir, src.name), overwrite = true)
                    log.appendLine("Mux-import: ${src.name} copiato in output")
                } else log.appendLine("Mux-import: non trovato: $p")
            } catch (t: Throwable) {
                log.appendLine("Mux-import fallito: ${t.message}")
            }
        }

        if (!opts.muxAfterDone) {
            val vOut = File(outDir, applyPattern(base, "VIDEO", "", 0, "", "ts"))
                .also { videoTs.copyTo(it, overwrite = true) }
            audioTs?.let {
                File(outDir, applyPattern(base, "AUDIO", "", 0, "", "ts"))
                    .also { o -> it.copyTo(o, overwrite = true) }
            }
            log.appendLine("Mux automatico SPENTO: file separati in ${outDir.absolutePath}")
            if (opts.delAfterDone && !opts.muxKeepFiles) cleanup(tmpRoot)
            return Result.Ok(vOut, total, log.toString(), subsOut)
        }

        if (!opts.muxFormat.equals("mp4", ignoreCase = true)) {
            log.appendLine("Nota: mux ${opts.muxFormat} non supportato su Android, uso mp4 (MediaMuxer HW)")
        }
        val mp4Name = applyPattern(base, "VIDEO", "", 0, "", "mp4")
        val mp4 = File(outDir, mp4Name)
        val ok = AutoMuxer.remuxToMp4(videoTs, audioTs, mp4)
        return if (ok) {
            log.appendLine("Mux automatico OK: ${mp4.absolutePath}" +
                (subsOut?.let { " + ${it.name}" } ?: " (senza sottotitoli)"))
            if (opts.delAfterDone && !opts.muxKeepFiles) cleanup(tmpRoot)
            Result.Ok(mp4, total, log.toString(), subsOut)
        } else {
            val vOut = File(outDir, applyPattern(base, "VIDEO", "", 0, "", "ts"))
                .also { videoTs.copyTo(it, overwrite = true) }
            audioTs?.let {
                File(outDir, applyPattern(base, "AUDIO", "", 0, "", "ts"))
                    .also { o -> it.copyTo(o, overwrite = true) }
            }
            log.appendLine("Mux automatico non riuscito (codec non supportato): tenuti i file separati in ${outDir.absolutePath}")
            if (opts.delAfterDone && !opts.muxKeepFiles) cleanup(tmpRoot)
            Result.Ok(vOut, total, log.toString(), subsOut)
        }
    }

    /** --save-pattern con <SaveName>/<Resolution>/<Bandwidth>/<Codecs>/<Language>/<MediaType>/<GroupId>/<Ext>. */
    private fun applyPattern(
        base: String, mediaType: String, codecs: String, bandwidth: Long,
        language: String, ext: String
    ): String {
        val p = opts.savePattern.trim()
        if (p.isEmpty()) {
            return when (mediaType) {
                "VIDEO" -> if (ext == "mp4") "$base.mp4" else "${base}_video.$ext"
                "AUDIO" -> "${base}_audio.$ext"
                else -> "$base.$ext"
            }
        }
        return OptionLogic.renderSavePattern(p, mapOf(
            "SaveName" to base, "Resolution" to "", "Bandwidth" to bandwidth.toString(),
            "Codecs" to codecs, "Language" to language, "MediaType" to mediaType,
            "GroupId" to "", "Ext" to ext, "Id" to "", "Channels" to "",
            "FrameRate" to "", "VideoRange" to ""
        )) + if (ext.isNotEmpty() && !p.contains("<Ext>")) ".$ext" else ""
    }

    /** --write-meta-json: info parse in JSON (come l'originale). */
    private fun writeMetaJson(
        outDir: File, base: String, kind: String, video: Any?, audio: Any?,
        total: Int, subs: MediaTrack? = null
    ) {
        try {
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val json = buildString {
                append("{\"url\":${q(opts.url)},\"type\":${q(kind)},\"segments\":$total,")
                append("\"video\":${video?.let { q(it.toString()) } ?: "null"},")
                append("\"audio\":${audio?.let { q(it.toString()) } ?: "null"},")
                append("\"subtitles\":${subs?.let { q("${it.language}/${it.name}") } ?: "null"},")
                append("\"cli\":${q(OptionLogic.toCliCommand(opts))}}")
            }
            File(outDir, "$base.json").writeText(json)
        } catch (_: Throwable) {
        }
    }

    private fun cleanup(tmpRoot: File) {
        try { tmpRoot.deleteRecursively() } catch (_: Throwable) {}
    }

    private fun effectiveThreads(): Int =
        if (opts.threadCount <= 0) defaultThreadCountForChromebookPlus() else opts.threadCount

    private fun newKeyCache(): MutableMap<String, ByteArray> {
        val m = java.util.Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
        opts.customHlsKeyHex?.let {
            try { m["__custom"] = AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Throwable) {}
        }
        return m
    }

    private fun customKeyIv(): Pair<ByteArray?, ByteArray?> {
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
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} su $url")
            return r.body?.string() ?: throw RuntimeException("Body vuoto su $url")
        }
    }
}
