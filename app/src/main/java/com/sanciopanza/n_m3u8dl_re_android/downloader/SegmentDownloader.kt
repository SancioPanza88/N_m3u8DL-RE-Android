package com.sanciopanza.n_m3u8dl_re_android.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloader multi-thread dei segmenti (equivalente --thread-count di N_m3u8DL-RE).
 * Su Chromebook Plus il default è 8+ thread (vedi Models.kt).
 */
class SegmentDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    data class Progress(val done: Int, val total: Int)

    suspend fun downloadAll(
        segments: List<Segment>,
        headers: Map<String, String>,
        threadCount: Int,
        retryCount: Int,
        tmpDir: File,
        keyCache: MutableMap<String, ByteArray> = mutableMapOf(),
        customKey: ByteArray? = null,
        customIv: ByteArray? = null,
        onProgress: (Progress) -> Unit = {}
    ): List<File> = withContext(Dispatchers.IO) {
        tmpDir.mkdirs()
        val sem = Semaphore(threadCount.coerceAtLeast(1))
        var done = 0
        val jobs = segments.map { seg ->
            async {
                sem.withPermit {
                    var attempt = 0
                    var lastErr: Exception? = null
                    while (attempt <= retryCount) {
                        try {
                            val outFile = File(tmpDir, "seg_%05d.ts".format(seg.index))
                            if (!outFile.exists() || outFile.length() == 0L) {
                                val bytes = fetchBytes(seg.uri, headers)
                                val plain = maybeDecrypt(seg, bytes, headers, keyCache, customKey, customIv)
                                outFile.writeBytes(plain)
                            }
                            synchronized(this@SegmentDownloader) {
                                done++
                                onProgress(Progress(done, segments.size))
                            }
                            return@withPermit outFile
                        } catch (e: Exception) {
                            lastErr = e
                            attempt++
                        }
                    }
                    throw lastErr ?: RuntimeException("Download failed: ${seg.uri}")
                }
            }
        }
        jobs.awaitAll()
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.addHeader(k, v)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: throw RuntimeException("Empty body for $url")
        }
    }

    private fun maybeDecrypt(
        seg: Segment,
        bytes: ByteArray,
        headers: Map<String, String>,
        keyCache: MutableMap<String, ByteArray>,
        customKey: ByteArray?,
        customIv: ByteArray?
    ): ByteArray {
        // Se nessun KEY e nessuna custom key => chiaro.
        if (seg.keyUri == null && customKey == null) return bytes
        val key: ByteArray = customKey ?: run {
            val kUri = seg.keyUri ?: return bytes
            keyCache.getOrPut(kUri) { fetchBytes(kUri, headers) }
        }
        if (key.size != 16) return bytes // non-AES128 (es. SAMPLE-AES/CENC): best effort, salvo in chiaro
        val iv: ByteArray = customIv ?: seg.ivHex?.let {
            try { AesDecryptor.hexToBytes(it) } catch (_: Exception) { null }
        } ?: AesDecryptor.defaultIvForSegment(seg.index)
        return try {
            AesDecryptor.decryptAes128Cbc(bytes, key.copyOf(16), iv.copyOf(16))
        } catch (_: Exception) {
            bytes
        }
    }
}
