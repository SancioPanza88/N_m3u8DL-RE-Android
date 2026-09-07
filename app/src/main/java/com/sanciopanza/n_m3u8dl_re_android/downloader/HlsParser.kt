package com.sanciopanza.n_m3u8dl_re_android.downloader

/**
 * Parser HLS M3U8 minimale, fedele alla semantica di N_m3u8DL-RE:
 * - master playlist con #EXT-X-STREAM-INF + #EXT-X-MEDIA (audio/sottotitoli)
 * - media playlist con #EXTINF + #EXT-X-KEY (AES-128) + #EXT-X-MAP + #EXT-X-ENDLIST
 * Pure Kotlin (testabile in JVM unit test).
 */
object HlsParser {

    fun parse(content: String, baseUrl: String): Playlist {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.none { it.startsWith("#EXTM3U") }) {
            throw IllegalArgumentException("Not a valid M3U8 playlist (missing #EXTM3U)")
        }
        val variants = mutableListOf<StreamVariant>()
        val segments = mutableListOf<Segment>()
        val audioTracks = mutableListOf<MediaTrack>()
        val subtitleTracks = mutableListOf<MediaTrack>()
        var isMaster = false
        var targetDuration = 0
        var hasEndList = false

        var pendingBandwidth = 0L
        var pendingResolution = ""
        var pendingCodecs = ""
        var pendingAudio = ""
        var pendingSubs = ""

        var pendingDuration = 0.0
        var pendingKeyUri: String? = null
        var pendingIv: String? = null
        var segmentIndex = 0

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA") -> {
                    val type = extractAttr(line, "TYPE").uppercase().ifEmpty { "AUDIO" }
                    val track = MediaTrack(
                        type = type,
                        groupId = extractAttr(line, "GROUP-ID"),
                        name = extractAttr(line, "NAME"),
                        language = extractAttr(line, "LANGUAGE").lowercase(),
                        uri = extractAttr(line, "URI").takeIf { it.isNotEmpty() }
                            ?.let { resolveUrl(baseUrl, it) } ?: "",
                        isDefault = extractAttr(line, "DEFAULT").equals("YES", ignoreCase = true)
                    )
                    if (track.uri.isNotEmpty()) {
                        if (type == "SUBTITLES") subtitleTracks.add(track) else audioTracks.add(track)
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    isMaster = true
                    pendingBandwidth = extractAttrLong(line, "BANDWIDTH")
                    pendingResolution = extractAttr(line, "RESOLUTION")
                    pendingCodecs = extractAttr(line, "CODECS")
                    pendingAudio = extractAttr(line, "AUDIO")
                    pendingSubs = extractAttr(line, "SUBTITLES")
                }
                line.startsWith("#EXT-X-TARGETDURATION") -> {
                    targetDuration = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line.startsWith("#EXT-X-ENDLIST") -> hasEndList = true
                line.startsWith("#EXT-X-KEY") -> {
                    val method = extractAttr(line, "METHOD")
                    if (method.equals("NONE", ignoreCase = true)) {
                        pendingKeyUri = null
                        pendingIv = null
                    } else {
                        val rawUri = extractAttr(line, "URI")
                        pendingKeyUri = rawUri.takeIf { it.isNotEmpty() }?.let { resolveUrl(baseUrl, it) }
                        pendingIv = extractAttr(line, "IV").takeIf { it.isNotEmpty() }
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val rawUri = extractAttr(line, "URI")
                    if (rawUri.isNotEmpty()) {
                        segments.add(
                            Segment(
                                index = segmentIndex++,
                                uri = resolveUrl(baseUrl, rawUri),
                                durationSec = 0.0,
                                keyUri = pendingKeyUri,
                                ivHex = pendingIv,
                                isMap = true
                            )
                        )
                    }
                }
                line.startsWith("#EXTINF") -> {
                    pendingDuration = line.substringAfter(":").substringBefore(",").trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#") -> { /* tag ignorato: trattiamo come N_m3u8DL-RE (best effort) */ }
                else -> {
                    val uri = resolveUrl(baseUrl, line)
                    if (isMaster || pendingBandwidth > 0L || variants.isNotEmpty() && segments.isEmpty() && pendingDuration == 0.0) {
                        // Riga URI dopo STREAM-INF => variante master
                        if (isMaster && (pendingBandwidth > 0L || variants.isEmpty() || line.endsWith(".m3u8"))) {
                            // Euristica: se siamo in master, ogni URI non-commento dopo STREAM-INF è una variante.
                            // Se il file contiene sia varianti che segmenti, la presenza di EXTINF decide.
                            if (segments.isEmpty() && pendingDuration == 0.0) {
                                variants.add(
                                    StreamVariant(
                                        bandwidth = pendingBandwidth,
                                        resolution = pendingResolution,
                                        codecs = pendingCodecs,
                                        uri = uri,
                                        audioGroup = pendingAudio,
                                        subtitleGroup = pendingSubs
                                    )
                                )
                                pendingBandwidth = 0L
                                pendingResolution = ""
                                pendingCodecs = ""
                                pendingAudio = ""
                                pendingSubs = ""
                            } else {
                                segments.add(Segment(segmentIndex++, uri, pendingDuration, pendingKeyUri, pendingIv))
                                pendingDuration = 0.0
                            }
                        } else {
                            segments.add(Segment(segmentIndex++, uri, pendingDuration, pendingKeyUri, pendingIv))
                            pendingDuration = 0.0
                        }
                    } else {
                        segments.add(Segment(segmentIndex++, uri, pendingDuration, pendingKeyUri, pendingIv))
                        pendingDuration = 0.0
                    }
                }
            }
            i++
        }

        // Se non ci sono varianti ma ci sono segmenti => media playlist
        if (variants.isEmpty()) {
            isMaster = false
        }
        return Playlist(
            isMaster = isMaster && variants.isNotEmpty(),
            variants = variants.toList(),
            segments = segments.toList(),
            audioTracks = audioTracks.toList(),
            subtitleTracks = subtitleTracks.toList(),
            targetDuration = targetDuration,
            isLive = !hasEndList && segments.isNotEmpty()
        )
    }

    fun selectBestVariant(variants: List<StreamVariant>): StreamVariant? {
        if (variants.isEmpty()) return null
        // Come --auto-select di N_m3u8DL-RE: miglior bandwidth, a parità risoluzione maggiore.
        return variants.sortedWith(
            compareBy<StreamVariant> { it.bandwidth }
                .thenBy { parseResolutionArea(it.resolution) }
        ).lastOrNull()
    }

    /**
     * Scelta traccia audio: gruppo della variante video -> default -> prima disponibile.
     * Equivalente a -sa/--auto-select dell'originale.
     */
    fun selectAudioTrack(tracks: List<MediaTrack>, groupId: String): MediaTrack? {
        if (tracks.isEmpty()) return null
        val inGroup = tracks.filter { it.groupId == groupId || groupId.isEmpty() }
        val pool = if (inGroup.isNotEmpty()) inGroup else tracks
        return pool.firstOrNull { it.isDefault } ?: pool.firstOrNull()
    }

    /**
     * Scelta sottotitoli: preferisce la lingua richiesta (default "it", come i flussi
     * vixcloud lang=it), poi default, poi prima disponibile.
     */
    fun selectSubtitleTrack(
        tracks: List<MediaTrack>,
        groupId: String,
        preferLang: String = "it"
    ): MediaTrack? {
        if (tracks.isEmpty()) return null
        val inGroup = tracks.filter { it.groupId == groupId || groupId.isEmpty() }
        val pool = if (inGroup.isNotEmpty()) inGroup else tracks
        return pool.firstOrNull { it.language == preferLang }
            ?: pool.firstOrNull { it.isDefault }
            ?: pool.firstOrNull()
    }

    fun parseResolutionArea(res: String): Long {
        // "1920x1080" -> area
        return try {
            val parts = res.lowercase().split("x")
            if (parts.size == 2) parts[0].toLong() * parts[1].toLong() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URL(java.net.URL(base), ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private fun extractAttr(line: String, name: String): String {
        // Cerca NAME="value" oppure NAME=value
        val quoted = Regex("$name\\s*=\\s*\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted
        return Regex("$name\\s*=\\s*([^,\\s]+)").find(line)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractAttrLong(line: String, name: String): Long {
        return extractAttr(line, name).toLongOrNull() ?: 0L
    }
}
