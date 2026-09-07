package com.sanciopanza.n_m3u8dl_re_android.downloader

/**
 * Port Kotlin dei modelli core di N_m3u8DL-RE (originale C# MIT by nilaoda).
 * HLS (M3U8) / DASH (MPD) / ISM.
 *
 * [DownloadOptions] rispecchia 1:1 le flag CLI dell'originale (stessi nomi e
 * stessi default): vedi README "Tabella parità opzioni".
 */
data class StreamVariant(
    val bandwidth: Long = 0L,
    val resolution: String = "",
    val codecs: String = "",
    val uri: String = "",
    val audioGroup: String = "",
    val subtitleGroup: String = ""
)

data class Segment(
    val index: Int,
    val uri: String,
    val durationSec: Double = 0.0,
    val keyUri: String? = null,
    val ivHex: String? = null,
    val isMap: Boolean = false
)

/** Traccia EXT-X-MEDIA (audio o sottotitoli) di una master playlist HLS. */
data class MediaTrack(
    val type: String = "AUDIO", // AUDIO | SUBTITLES
    val groupId: String = "",
    val name: String = "",
    val language: String = "",
    val uri: String = "",
    val isDefault: Boolean = false
)

data class Playlist(
    val isMaster: Boolean,
    val variants: List<StreamVariant> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val targetDuration: Int = 0,
    val isLive: Boolean = false
)

data class DownloadOptions(
    // Input / rete
    val url: String,
    val baseUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val customProxy: String = "",
    val httpTimeoutSec: Int = 100,
    val taskStartAt: String = "", // yyyyMMddHHmmss
    val appendUrlParams: Boolean = false,
    // Output
    val saveName: String = "video",
    val savePattern: String = "",
    val writeMetaJson: Boolean = true,
    val noLog: Boolean = false,
    val logLevel: String = "INFO",
    // Download / concorrenza
    val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(4),
    val retryCount: Int = 3,
    val concurrentDownload: Boolean = false, // -mt
    val maxSpeed: String = "", // es. 15M / 100K
    val skipDownload: Boolean = false,
    val skipMerge: Boolean = false,
    val checkSegmentsCount: Boolean = true,
    val binaryMerge: Boolean = false,
    val delAfterDone: Boolean = true,
    val customRange: String = "",
    val adKeyword: String = "",
    val allowHlsMultiExtMap: Boolean = false,
    // Selezione stream (come -sv/-sa/-ss/-dv/-da/-ds: regex su banda/ris/codec/lingua/nome/url)
    val autoSelectBest: Boolean = true,
    val selectVideo: String = "",
    val selectAudio: String = "",
    val selectSubtitle: String = "",
    val dropVideo: String = "",
    val dropAudio: String = "",
    val dropSubtitle: String = "",
    val subOnly: Boolean = false,
    // Sottotitoli
    val subFormat: String = "SRT", // SRT | VTT
    val autoSubtitleFix: Boolean = true,
    val liveFixVttByAudio: Boolean = false,
    // Decifratura (come --key / --custom-hls-*)
    val keyEntries: List<String> = emptyList(), // "KID:KEY" o "KEY", multipli
    val keyTextFileContent: String = "",
    val customHlsMethod: String = "", // AES_128|...|NONE (vuoto = da playlist)
    val customHlsKeyHex: String? = null,
    val customHlsIvHex: String? = null,
    val maxSpeedKbps: Int = 0, // legacy, se >0 vince su maxSpeed
    // Live
    val livePerformAsVod: Boolean = false,
    val liveRealTimeMerge: Boolean = false,
    val liveKeepSegments: Boolean = true,
    val liveRecordLimit: String = "", // HH:mm:ss
    val liveWaitTimeSec: Int = 0, // 0 = da playlist (target duration)
    val liveTakeCount: Int = 16,
    // Mux (-M --mux-after-done)
    val muxAfterDone: Boolean = true,
    val muxFormat: String = "mp4", // mp4 (mkv non supportato su Android -> fallback mp4)
    val muxKeepFiles: Boolean = false,
    val muxSkipSub: Boolean = false,
    val muxImportPath: String = "",
    val includeAudio: Boolean = true,
    val includeSubtitles: Boolean = true
)

fun defaultThreadCountForChromebookPlus(): Int {
    // Chromebook Plus: minimo 8GB RAM, CPU i3 12th+/Ryzen 7000/Kompanio 520.
    // Sfruttiamo tutti i core con un minimo aggressivo per download parallelo.
    return Runtime.getRuntime().availableProcessors().coerceAtLeast(8)
}
