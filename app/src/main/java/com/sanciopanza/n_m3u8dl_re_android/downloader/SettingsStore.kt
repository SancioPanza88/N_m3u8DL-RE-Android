package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nm3u8dl_settings")

/** Persistenza leggera di TUTTE le opzioni (stessi nomi delle flag CLI). */
class SettingsStore(private val context: Context) {

    private val K = object {
        val THREADS = intPreferencesKey("thread_count")
        val RETRY = intPreferencesKey("retry")
        val TIMEOUT = intPreferencesKey("timeout")
        val TAKE = intPreferencesKey("take")
        val WAIT = intPreferencesKey("wait")
        val SAVE = stringPreferencesKey("save_name")
        val HEADERS = stringPreferencesKey("headers")
        val MUX = booleanPreferencesKey("mux_after_done")
        val PATTERN = stringPreferencesKey("save_pattern")
        val BASE = stringPreferencesKey("base_url")
        val PROXY = stringPreferencesKey("proxy")
        val TASKAT = stringPreferencesKey("task_at")
        val APPENDQ = booleanPreferencesKey("append_q")
        val MT = booleanPreferencesKey("mt")
        val MAXSPEED = stringPreferencesKey("max_speed")
        val SKIPDL = booleanPreferencesKey("skip_dl")
        val SKIPMERGE = booleanPreferencesKey("skip_merge")
        val CHECK = booleanPreferencesKey("check")
        val BIN = booleanPreferencesKey("bin")
        val DEL = booleanPreferencesKey("del")
        val META = booleanPreferencesKey("meta")
        val NOLOG = booleanPreferencesKey("nolog")
        val LOGLEVEL = stringPreferencesKey("loglevel")
        val RANGE = stringPreferencesKey("range")
        val AD = stringPreferencesKey("ad")
        val MULTIMAP = booleanPreferencesKey("multimap")
        val AUTOSEL = booleanPreferencesKey("autosel")
        val SV = stringPreferencesKey("sv")
        val SA = stringPreferencesKey("sa")
        val SS = stringPreferencesKey("ss")
        val DV = stringPreferencesKey("dv")
        val DA = stringPreferencesKey("da")
        val DS = stringPreferencesKey("ds")
        val SUBONLY = booleanPreferencesKey("subonly")
        val SUBFMT = stringPreferencesKey("subfmt")
        val SUBFIX = booleanPreferencesKey("subfix")
        val FIXVTT = booleanPreferencesKey("fixvtt")
        val KEYS = stringPreferencesKey("keys")
        val KEYFILE = stringPreferencesKey("keyfile")
        val HLSM = stringPreferencesKey("hlsm")
        val HLSK = stringPreferencesKey("hlsk")
        val HLSI = stringPreferencesKey("hlsi")
        val VOD = booleanPreferencesKey("vod")
        val RTMERGE = booleanPreferencesKey("rtmerge")
        val KEEPSEG = booleanPreferencesKey("keepseg")
        val RECLIMIT = stringPreferencesKey("reclimit")
        val MUXFMT = stringPreferencesKey("muxfmt")
        val MUXKEEP = booleanPreferencesKey("muxkeep")
        val MUXSKIPSUB = booleanPreferencesKey("muxskipsub")
        val MUXIMPORT = stringPreferencesKey("muximport")
    }

    suspend fun load(): DownloadOptions {
        val d = context.dataStore.data.first()
        fun s(k: androidx.datastore.preferences.core.Preferences.Key<String>) = d[k] ?: ""
        fun b(k: androidx.datastore.preferences.core.Preferences.Key<Boolean>, def: Boolean) = d[k] ?: def
        fun i(k: androidx.datastore.preferences.core.Preferences.Key<Int>, def: Int) = d[k] ?: def
        return DownloadOptions(
            url = "",
            baseUrl = s(K.BASE),
            headers = parseHeaders(s(K.HEADERS)),
            customProxy = s(K.PROXY),
            httpTimeoutSec = i(K.TIMEOUT, 100),
            taskStartAt = s(K.TASKAT),
            appendUrlParams = b(K.APPENDQ, false),
            saveName = s(K.SAVE).ifEmpty { "video" },
            savePattern = s(K.PATTERN),
            writeMetaJson = b(K.META, true),
            noLog = b(K.NOLOG, false),
            logLevel = s(K.LOGLEVEL).ifEmpty { "INFO" },
            threadCount = i(K.THREADS, defaultThreadCountForChromebookPlus()),
            retryCount = i(K.RETRY, 3),
            concurrentDownload = b(K.MT, false),
            maxSpeed = s(K.MAXSPEED),
            skipDownload = b(K.SKIPDL, false),
            skipMerge = b(K.SKIPMERGE, false),
            checkSegmentsCount = b(K.CHECK, true),
            binaryMerge = b(K.BIN, false),
            delAfterDone = b(K.DEL, true),
            customRange = s(K.RANGE),
            adKeyword = s(K.AD),
            allowHlsMultiExtMap = b(K.MULTIMAP, false),
            autoSelectBest = b(K.AUTOSEL, true),
            selectVideo = s(K.SV),
            selectAudio = s(K.SA),
            selectSubtitle = s(K.SS),
            dropVideo = s(K.DV),
            dropAudio = s(K.DA),
            dropSubtitle = s(K.DS),
            subOnly = b(K.SUBONLY, false),
            subFormat = s(K.SUBFMT).ifEmpty { "SRT" },
            autoSubtitleFix = b(K.SUBFIX, true),
            liveFixVttByAudio = b(K.FIXVTT, false),
            keyEntries = s(K.KEYS).lines().map { it.trim() }.filter { it.isNotEmpty() },
            keyTextFileContent = s(K.KEYFILE),
            customHlsMethod = s(K.HLSM),
            customHlsKeyHex = s(K.HLSK).ifEmpty { null },
            customHlsIvHex = s(K.HLSI).ifEmpty { null },
            livePerformAsVod = b(K.VOD, false),
            liveRealTimeMerge = b(K.RTMERGE, false),
            liveKeepSegments = b(K.KEEPSEG, true),
            liveRecordLimit = s(K.RECLIMIT),
            liveWaitTimeSec = i(K.WAIT, 0),
            liveTakeCount = i(K.TAKE, 16),
            muxAfterDone = b(K.MUX, true),
            muxFormat = s(K.MUXFMT).ifEmpty { "mp4" },
            muxKeepFiles = b(K.MUXKEEP, false),
            muxSkipSub = b(K.MUXSKIPSUB, false),
            muxImportPath = s(K.MUXIMPORT)
        )
    }

    // Compat con la vecchia UI minimale
    suspend fun loadThreads(): Int = context.dataStore.data.map { it[K.THREADS] ?: defaultThreadCountForChromebookPlus() }.first()
    suspend fun loadSaveName(): String = context.dataStore.data.map { it[K.SAVE] ?: "video" }.first()
    suspend fun loadMux(): Boolean = context.dataStore.data.map { it[K.MUX] ?: true }.first()
    suspend fun saveThreads(v: Int) { context.dataStore.edit { it[K.THREADS] = v } }
    suspend fun saveAll(threads: Int, saveName: String, headersRaw: String, muxAfterDone: Boolean = true) {
        context.dataStore.edit {
            it[K.THREADS] = threads
            it[K.SAVE] = saveName
            it[K.HEADERS] = headersRaw
            it[K.MUX] = muxAfterDone
        }
    }

    suspend fun saveAllOptions(o: DownloadOptions, headersRaw: String) {
        context.dataStore.edit {
            it[K.THREADS] = o.threadCount
            it[K.RETRY] = o.retryCount
            it[K.TIMEOUT] = o.httpTimeoutSec
            it[K.TAKE] = o.liveTakeCount
            it[K.WAIT] = o.liveWaitTimeSec
            it[K.SAVE] = o.saveName
            it[K.HEADERS] = headersRaw
            it[K.MUX] = o.muxAfterDone
            it[K.PATTERN] = o.savePattern
            it[K.BASE] = o.baseUrl
            it[K.PROXY] = o.customProxy
            it[K.TASKAT] = o.taskStartAt
            it[K.APPENDQ] = o.appendUrlParams
            it[K.MT] = o.concurrentDownload
            it[K.MAXSPEED] = o.maxSpeed
            it[K.SKIPDL] = o.skipDownload
            it[K.SKIPMERGE] = o.skipMerge
            it[K.CHECK] = o.checkSegmentsCount
            it[K.BIN] = o.binaryMerge
            it[K.DEL] = o.delAfterDone
            it[K.META] = o.writeMetaJson
            it[K.NOLOG] = o.noLog
            it[K.LOGLEVEL] = o.logLevel
            it[K.RANGE] = o.customRange
            it[K.AD] = o.adKeyword
            it[K.MULTIMAP] = o.allowHlsMultiExtMap
            it[K.AUTOSEL] = o.autoSelectBest
            it[K.SV] = o.selectVideo
            it[K.SA] = o.selectAudio
            it[K.SS] = o.selectSubtitle
            it[K.DV] = o.dropVideo
            it[K.DA] = o.dropAudio
            it[K.DS] = o.dropSubtitle
            it[K.SUBONLY] = o.subOnly
            it[K.SUBFMT] = o.subFormat
            it[K.SUBFIX] = o.autoSubtitleFix
            it[K.FIXVTT] = o.liveFixVttByAudio
            it[K.KEYS] = o.keyEntries.joinToString("\n")
            it[K.KEYFILE] = o.keyTextFileContent
            it[K.HLSM] = o.customHlsMethod
            it[K.HLSK] = o.customHlsKeyHex ?: ""
            it[K.HLSI] = o.customHlsIvHex ?: ""
            it[K.VOD] = o.livePerformAsVod
            it[K.RTMERGE] = o.liveRealTimeMerge
            it[K.KEEPSEG] = o.liveKeepSegments
            it[K.RECLIMIT] = o.liveRecordLimit
            it[K.MUXFMT] = o.muxFormat
            it[K.MUXKEEP] = o.muxKeepFiles
            it[K.MUXSKIPSUB] = o.muxSkipSub
            it[K.MUXIMPORT] = o.muxImportPath
        }
    }

    companion object {
        /** "Cookie: x; User-Agent: y" oppure righe "K: V" => mappa headers. */
        fun parseHeaders(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val lines = raw.split("\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
            for (l in lines) {
                val idx = l.indexOf(":")
                if (idx > 0) {
                    out[l.substring(0, idx).trim()] = l.substring(idx + 1).trim()
                }
            }
            return out
        }
    }
}
