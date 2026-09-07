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
 * fetch playlist -> auto-select best -> download segmenti -> decrypt -> merge.
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
        data class Ok(val file: File, val segments: Int, val log: String) : Result
        data class Err(val message: String) : Result
    }

    suspend fun run(opts: DownloadOptions, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            try {
                val playlistText = fetchText(opts.url, opts.headers)
                val isMpd = playlistText.trimStart().startsWith("<") &&
                    (playlistText.contains("<MPD") || opts.url.contains(".mpd", ignoreCase = true))
                val log = StringBuilder()

                val segments: List<Segment>
                if (isMpd) {
                    val streams = DashParser.parse(playlistText, opts.url)
                    if (streams.isEmpty()) return@withContext Result.Err("MPD senza stream scaricabili")
                    // auto-select: video con bandwidth max + primo audio
                    val video = streams.filter {
                        it.mimeType.contains("video") || it.codecs.contains("avc", true) ||
                            it.codecs.contains("hev", true) || it.segments.isNotEmpty()
                    }.maxByOrNull { it.bandwidth } ?: streams.first()
                    log.appendLine("DASH: ${streams.size} stream, scelto bw=${video.bandwidth}")
                    segments = video.segments.take(300).mapIndexed { idx, u -> Segment(idx, u) }
                } else {
                    val pl = HlsParser.parse(playlistText, opts.url)
                    if (pl.isMaster) {
                        val best = if (opts.autoSelectBest) {
                            HlsParser.selectBestVariant(pl.variants)
                        } else {
                            pl.variants.firstOrNull()
                        } ?: return@withContext Result.Err("Master playlist senza varianti")
                        log.appendLine("HLS master: ${pl.variants.size} varianti, scelta bw=${best.bandwidth} ${best.resolution}")
                        val sub = fetchText(best.uri, opts.headers)
                        val subPl = HlsParser.parse(sub, best.uri)
                        segments = subPl.segments
                    } else {
                        segments = pl.segments
                        log.appendLine("HLS media: ${segments.size} segmenti (live=${pl.isLive})")
                    }
                }
                if (segments.isEmpty()) return@withContext Result.Err("Nessun segmento trovato")

                val threads = if (opts.threadCount <= 0) defaultThreadCountForChromebookPlus() else opts.threadCount
                val tmpDir = File(context.cacheDir, "nm3u8dl_tmp/${opts.saveName}")
                val customKey = opts.customHlsKeyHex?.let {
                    try { AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Exception) { null }
                }
                val customIv = opts.customHlsIvHex?.let {
                    try { AesDecryptor.hexToBytes(it).copyOf(16) } catch (_: Exception) { null }
                }
                val files = segDownloader.downloadAll(
                    segments = segments,
                    headers = opts.headers,
                    threadCount = threads,
                    retryCount = opts.retryCount,
                    tmpDir = tmpDir,
                    customKey = customKey,
                    customIv = customIv
                ) { p -> onProgress(p.done, p.total) }

                if (opts.skipMerge) {
                    return@withContext Result.Ok(tmpDir, files.size, log.toString())
                }
                val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads")
                outDir.mkdirs()
                val outFile = File(outDir, opts.saveName + ".ts")
                Muxer.mergeTs(files, outFile, opts.binaryMerge)
                log.appendLine("Merge OK: ${outFile.absolutePath}")
                Result.Ok(outFile, files.size, log.toString())
            } catch (t: Throwable) {
                Result.Err(t.message ?: "Errore sconosciuto")
            }
        }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.addHeader(k, v)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} su $url")
            return r.body?.string() ?: throw RuntimeException("Body vuoto su $url")
        }
    }
}
