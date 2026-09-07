package com.sanciopanza.n_m3u8dl_re_android.downloader

/**
 * Logica pura delle opzioni CLI di N_m3u8DL-RE (stessa semantica dell'originale C#).
 * Pure Kotlin: coperta da unit test JVM.
 */
object OptionLogic {

    /** -R/--max-speed: "15M"/"100K"/"500" -> byte al secondo. 0 = nessun limite. */
    fun parseMaxSpeedToBps(raw: String): Long {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return 0
        return try {
            when {
                s.endsWith("MBPS") -> s.dropLast(4).toDouble() * 1_000_000 / 8
                s.endsWith("KBPS") -> s.dropLast(4).toDouble() * 1_000 / 8
                s.endsWith("M") -> s.dropLast(1).toDouble() * 1_000_000 / 8
                s.endsWith("K") -> s.dropLast(1).toDouble() * 1_000 / 8
                else -> s.toDouble() / 8
            }.toLong().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * --custom-range: "0-10" / "10-" / "-99" (indici) oppure "MM:SS-MM:SS"
     * (frazioni orarie sul cumulato delle durate, come l'originale).
     */
    fun applyCustomRange(segments: List<Segment>, range: String): List<Segment> {
        val r = range.trim()
        if (r.isEmpty() || segments.isEmpty()) return segments
        return try {
            if (r.contains(":")) {
                val parts = r.split("-", limit = 2)
                val from = parseHms(parts[0]) ?: return segments
                val to = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { parseHms(it) }
                var t = 0.0
                segments.filter { seg ->
                    val start = t
                    t += seg.durationSec
                    start >= from && (to == null || start < to)
                }
            } else {
                val parts = r.split("-", limit = 2)
                val from = parts[0].takeIf { it.isNotEmpty() }?.toIntOrNull()
                val to = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                segments.filterIndexed { i, _ ->
                    (from == null || i >= from) && (to == null || i <= to)
                }
            }
        } catch (_: Exception) {
            segments
        }
    }

    private fun parseHms(s: String): Double? {
        val p = s.trim().split(":")
        return try {
            when (p.size) {
                3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
                2 -> p[0].toDouble() * 60 + p[1].toDouble()
                1 -> p[0].toDouble()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** --ad-keyword: scarta i segmenti il cui URL matcha la regex. */
    fun filterAdSegments(segments: List<Segment>, adKeyword: String): List<Segment> {
        val kw = adKeyword.trim()
        if (kw.isEmpty()) return segments
        return try {
            val re = Regex(kw)
            segments.filter { !re.containsMatchIn(it.uri) }
        } catch (_: Exception) {
            segments
        }
    }

    /**
     * -sv/-sa/-ss/-dv/-da/-ds: regex su testo descrittivo dello stream.
     * "" o "all" = tutti; "best" = delegato all'auto-select (ritorna null = nessun filtro).
     */
    fun matchSelect(option: String, haystack: String): Boolean? {
        val o = option.trim()
        if (o.isEmpty() || o.equals("best", ignoreCase = true)) return null
        if (o.equals("all", ignoreCase = true)) return true
        return try {
            Regex(o, RegexOption.IGNORE_CASE).containsMatchIn(haystack)
        } catch (_: Exception) {
            haystack.contains(o, ignoreCase = true)
        }
    }

    fun variantText(v: StreamVariant): String =
        "${v.bandwidth} ${v.resolution} ${v.codecs} ${v.uri} ${v.audioGroup} ${v.subtitleGroup}"

    fun trackText(t: MediaTrack): String =
        "${t.language} ${t.name} ${t.groupId} ${t.uri}"

    /** --live-record-limit "HH:mm:ss" -> secondi. 0 = nessun limite. */
    fun parseRecordLimitToSec(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        return (parseHms(s) ?: 0.0).toLong().coerceAtLeast(0)
    }

    /** --task-start-at "yyyyMMddHHmmss" -> epoch millis. 0 = subito. */
    fun parseTaskStartAt(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        return try {
            val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getDefault()
            f.parse(s)?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * --save-pattern: sostituisce <SaveName> <Resolution> <Bandwidth> <Codecs>
     * <Language> <MediaType> <GroupId> <Ext>.
     */
    fun renderSavePattern(pattern: String, vars: Map<String, String>): String {
        var out = pattern
        for ((k, v) in vars) out = out.replace("<$k>", v)
        return out.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /** --key: righe "KID:KEY" o "KEY" (+ contenuto --key-text-file "KID=KEY"/"KID:KEY"). */
    fun parseKeyEntries(entries: List<String>, keyFileContent: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = entries + keyFileContent.lines()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val sep = l.indexOfFirst { it == ':' || it == '=' }
            if (sep > 0) out[l.substring(0, sep).trim().lowercase()] = l.substring(sep + 1).trim()
            else out["__single"] = l
        }
        return out
    }

    /**
     * Genera il comando CLI dell'originale equivalente alle opzioni impostate
     * (prova che è lo stesso programma, stesse flag).
     */
    fun toCliCommand(o: DownloadOptions): String {
        val b = StringBuilder("N_m3u8DL-RE \"${o.url}\"")
        fun flag(n: String, v: String) { if (v.isNotEmpty()) b.append(" $n \"$v\"") }
        fun bool(n: String, v: Boolean) { if (v) b.append(" $n") }
        flag("--base-url", o.baseUrl)
        flag("--save-name", o.saveName)
        flag("--save-pattern", o.savePattern)
        b.append(" --thread-count ${o.threadCount}")
        b.append(" --download-retry-count ${o.retryCount}")
        b.append(" --http-request-timeout ${o.httpTimeoutSec}")
        for ((k, v) in o.headers) b.append(" -H \"$k: $v\"")
        bool("--auto-select", o.autoSelectBest)
        bool("--skip-merge", o.skipMerge)
        bool("--skip-download", o.skipDownload)
        if (!o.checkSegmentsCount) b.append(" --check-segments-count false")
        bool("--binary-merge", o.binaryMerge)
        if (!o.delAfterDone) b.append(" --del-after-done false")
        if (!o.writeMetaJson) b.append(" --write-meta-json false")
        bool("--append-url-params", o.appendUrlParams)
        bool("-mt", o.concurrentDownload)
        bool("--sub-only", o.subOnly)
        b.append(" --sub-format ${o.subFormat}")
        if (!o.autoSubtitleFix) b.append(" --auto-subtitle-fix false")
        for (k in o.keyEntries) b.append(" --key \"$k\"")
        if (o.keyTextFileContent.isNotEmpty()) b.append(" --key-text-file <file>")
        if (o.customHlsMethod.isNotEmpty()) b.append(" --custom-hls-method ${o.customHlsMethod}")
        o.customHlsKeyHex?.let { b.append(" --custom-hls-key $it") }
        o.customHlsIvHex?.let { b.append(" --custom-hls-iv $it") }
        if (o.maxSpeed.isNotEmpty()) b.append(" -R ${o.maxSpeed}")
        if (o.muxAfterDone) b.append(" -M format=${o.muxFormat.lowercase()}")
        if (o.customRange.isNotEmpty()) b.append(" --custom-range ${o.customRange}")
        if (o.taskStartAt.isNotEmpty()) b.append(" --task-start-at ${o.taskStartAt}")
        bool("--live-perform-as-vod", o.livePerformAsVod)
        bool("--live-real-time-merge", o.liveRealTimeMerge)
        if (!o.liveKeepSegments) b.append(" --live-keep-segments false")
        if (o.liveRecordLimit.isNotEmpty()) b.append(" --live-record-limit ${o.liveRecordLimit}")
        if (o.liveWaitTimeSec > 0) b.append(" --live-wait-time ${o.liveWaitTimeSec}")
        if (o.liveTakeCount != 16) b.append(" --live-take-count ${o.liveTakeCount}")
        bool("--live-fix-vtt-by-audio", o.liveFixVttByAudio)
        if (o.muxImportPath.isNotEmpty()) b.append(" --mux-import path=${o.muxImportPath}")
        flag("-sv", o.selectVideo); flag("-sa", o.selectAudio); flag("-ss", o.selectSubtitle)
        flag("-dv", o.dropVideo); flag("-da", o.dropAudio); flag("-ds", o.dropSubtitle)
        flag("--ad-keyword", o.adKeyword)
        bool("--allow-hls-multi-ext-map", o.allowHlsMultiExtMap)
        if (o.customProxy.isNotEmpty()) b.append(" --custom-proxy ${o.customProxy}")
        b.append(" --log-level ${o.logLevel}")
        if (o.noLog) b.append(" --no-log")
        return b.toString()
    }
}
