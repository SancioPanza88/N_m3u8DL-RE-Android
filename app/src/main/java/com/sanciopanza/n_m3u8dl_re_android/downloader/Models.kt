package com.sanciopanza.n_m3u8dl_re_android.downloader

/**
 * Port Kotlin dei modelli core di N_m3u8DL-RE (originale C# MIT by nilaoda).
 * HLS (M3U8) / DASH (MPD) / ISM.
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
    val url: String,
    val saveName: String = "video",
    val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(4),
    val retryCount: Int = 3,
    val headers: Map<String, String> = emptyMap(),
    val autoSelectBest: Boolean = true,
    val skipMerge: Boolean = false,
    val binaryMerge: Boolean = false,
    val customHlsKeyHex: String? = null,
    val customHlsIvHex: String? = null,
    val maxSpeedKbps: Int = 0,
    // Mux automatico (come -M di N_m3u8DL-RE): unisce video+audio+subs in un unico file.
    val muxAfterDone: Boolean = true,
    val includeAudio: Boolean = true,
    val includeSubtitles: Boolean = true
)

fun defaultThreadCountForChromebookPlus(): Int {
    // Chromebook Plus: minimo 8GB RAM, CPU i3 12th+/Ryzen 7000/Kompanio 520.
    // Sfruttiamo tutti i core con un minimo aggressivo per download parallelo.
    return Runtime.getRuntime().availableProcessors().coerceAtLeast(8)
}
