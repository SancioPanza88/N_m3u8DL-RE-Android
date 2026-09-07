package com.sanciopanza.n_m3u8dl_re_android.downloader

/**
 * Utilità sottotitoli (pure Kotlin, testabile in JVM unit test).
 * Le playlist HLS di tipo vixcloud espongono i subs come segmenti WebVTT:
 * qui li uniamo in un unico .srt pronto da affiancare all'mp4 finale.
 */
object SubtitleUtils {

    /** Unisce più segmenti VTT in un unico documento SRT con numerazione continua. */
    fun mergeVttSegmentsToSrt(vttTexts: List<String>): String {
        val out = StringBuilder()
        var counter = 1
        for (text in vttTexts) {
            for ((ts, line) in parseVttCues(text)) {
                out.append(counter++).append('\n')
                out.append(ts).append('\n')
                out.append(line.trim()).append("\n\n")
            }
        }
        return out.toString()
    }

    /** Converte un singolo documento VTT in SRT (usato anche per i test). */
    fun vttToSrt(vtt: String): String = mergeVttSegmentsToSrt(listOf(vtt))

    private fun parseVttCues(vtt: String): List<Pair<String, String>> {
        val cues = mutableListOf<Pair<String, String>>()
        // I segmenti HLS partono quasi sempre senza header WEBVTT: gestiamo entrambi i casi.
        val blocks = vtt.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n\\s*\n"))
        val tsRegex = Regex("""(\d{2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(\d{2}:)?\d{2}:\d{2}\.\d{3}.*""")
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")) continue
            // La riga timestamp può essere preceduta da un cue-id numerico.
            val tsIndex = lines.indexOfFirst { tsRegex.matches(it) }
            if (tsIndex < 0) continue
            val ts = lines[tsIndex].substringBefore(" ").let { fixTs(it) } +
                " --> " + lines[tsIndex].substringAfter("-->").trim().substringBefore(" ").let { fixTs(it) }
            val text = lines.drop(tsIndex + 1).joinToString("\n")
            if (text.isNotEmpty()) cues.add(ts to text)
        }
        return cues
    }

    private fun fixTs(ts: String): String {
        // "00:01.500" -> "00:00:01,500" ; "00:00:01.500" -> "00:00:01,500"
        val withComma = ts.replace(".", ",")
        return if (withComma.count { it == ':' } == 1) "00:$withComma" else withComma
    }
}
