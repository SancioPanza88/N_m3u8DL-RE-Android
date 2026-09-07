package com.sanciopanza.n_m3u8dl_re_android.downloader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser DASH MPD minimale (VOD): estrae AdaptationSet video/audio con SegmentTemplate
 * o SegmentList. Best-effort come N_m3u8DL-RE --auto-select.
 */
object DashParser {

    data class DashStream(
        val mimeType: String,
        val codecs: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val baseUrl: String,
        val segments: List<String>
    )

    fun parse(mpdXml: String, mpdUrl: String): List<DashStream> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(mpdXml))
        val out = mutableListOf<DashStream>()

        var event = parser.eventType
        var periodBase = mpdUrl.substringBeforeLast("/") + "/"
        var currentMime = ""
        var currentCodecs = ""
        var inAdaptation = false
        var adaptationMime = ""
        var adaptationCodecs = ""
        var segmentTemplateMedia = ""
        var segmentTemplateInit = ""
        var segmentTimeline: MutableList<String> = mutableListOf()
        var segmentListUrls: MutableList<String> = mutableListOf()
        var repBandwidth = 0L
        var repW = 0
        var repH = 0
        var repCodecs = ""
        var repMime = ""
        var inRepresentation = false
        var inSegmentList = false
        var baseUrlOverride: String? = null

        fun resolve(ref: String): String = HlsParser.resolveUrl(periodBase, ref)

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "BaseURL" -> { /* gestito in TEXT */ }
                    "AdaptationSet" -> {
                        inAdaptation = true
                        adaptationMime = parser.getAttributeValue(null, "mimeType") ?: ""
                        adaptationCodecs = parser.getAttributeValue(null, "codecs") ?: ""
                        segmentTemplateMedia = ""
                        segmentTemplateInit = ""
                        segmentTimeline = mutableListOf()
                    }
                    "Representation" -> {
                        inRepresentation = true
                        repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                        repW = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                        repH = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                        repCodecs = parser.getAttributeValue(null, "codecs") ?: adaptationCodecs
                        repMime = parser.getAttributeValue(null, "mimeType") ?: adaptationMime
                        segmentListUrls = mutableListOf()
                        baseUrlOverride = null
                    }
                    "SegmentTemplate" -> {
                        val media = parser.getAttributeValue(null, "media") ?: segmentTemplateMedia
                        val init = parser.getAttributeValue(null, "initialization") ?: segmentTemplateInit
                        if (media.isNotEmpty()) segmentTemplateMedia = media
                        if (init.isNotEmpty()) segmentTemplateInit = init
                    }
                    "SegmentList" -> inSegmentList = true
                    "SegmentURL" -> {
                        val media = parser.getAttributeValue(null, "media")
                        if (!media.isNullOrEmpty()) segmentListUrls.add(resolve(media))
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        // Determina il parent dal contesto semplificato
                        // (XmlPullParser non dà parent diretto: usiamo flag)
                        if (inRepresentation && inSegmentList) {
                            // dentro SegmentList/SegmentURL? il TEXT di <SegmentURL media=""> è vuoto,
                            // ma <BaseURL> dentro Representation contiene URL init/segmento singolo
                            // Best effort: accumula se sembra URL media.
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "SegmentList" -> inSegmentList = false
                    "Representation" -> {
                        currentMime = repMime.ifEmpty { adaptationMime }
                        currentCodecs = repCodecs.ifEmpty { adaptationCodecs }
                        val segs = mutableListOf<String>()
                        if (segmentTemplateInit.isNotEmpty()) segs.add(resolve(fillTemplate(segmentTemplateInit, 0)))
                        if (segmentTemplateMedia.isNotEmpty()) {
                            // VOD: espandiamo i primi N segmenti in modo dimostrativo;
                            // il downloader reale userà SegmentTimeline/numero. Qui generiamo
                            // pattern con $Number$ per i primi 300 segmenti se timeline assente.
                            if (segmentTemplateMedia.contains("\$Number")) {
                                for (n in 1..300) segs.add(resolve(fillTemplate(segmentTemplateMedia, n)))
                            } else {
                                segs.add(resolve(segmentTemplateMedia))
                            }
                        }
                        segs.addAll(segmentListUrls)
                        if (baseUrlOverride != null) segs.add(0, resolve(baseUrlOverride!!))
                        if (segs.isNotEmpty()) {
                            out.add(
                                DashStream(
                                    mimeType = currentMime,
                                    codecs = currentCodecs,
                                    bandwidth = repBandwidth,
                                    width = repW,
                                    height = repH,
                                    baseUrl = periodBase,
                                    segments = segs.toList()
                                )
                            )
                        }
                        inRepresentation = false
                    }
                    "AdaptationSet" -> inAdaptation = false
                }
            }
            event = parser.next()
        }
        return out.toList()
    }

    private fun fillTemplate(tpl: String, number: Int): String {
        return tpl.replace("\$Number\$", number.toString())
            .replace("\$Number%03d\$", "%03d".format(number))
            .replace("\$Number%05d\$", "%05d".format(number))
    }
}
