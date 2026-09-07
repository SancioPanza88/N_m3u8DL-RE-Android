package com.sanciopanza.n_m3u8dl_re_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sanciopanza.n_m3u8dl_re_android.downloader.SubtitleUtils

class SubtitleUtilsTest {

    @Test
    fun vttToSrt_convertsTimestampsAndNumbersCues() {
        val vtt = """
            WEBVTT

            00:00:01.500 --> 00:00:03.000
            Ciao mondo

            00:01:10.250 --> 00:01:12.000
            Seconda riga
        """.trimIndent()
        val srt = SubtitleUtils.vttToSrt(vtt)
        assertTrue(srt.contains("1\n00:00:01,500 --> 00:00:03,000\nCiao mondo"))
        assertTrue(srt.contains("2\n00:01:10,250 --> 00:01:12,000\nSeconda riga"))
    }

    @Test
    fun mergeVttSegments_numbersContinuously() {
        val seg1 = "00:00.500 --> 00:02.000\nPrimo"
        val seg2 = "00:00.500 --> 00:02.000\nSecondo"
        val srt = SubtitleUtils.mergeVttSegmentsToSrt(listOf(seg1, seg2))
        assertTrue(srt.startsWith("1\n00:00:00,500"))
        assertTrue(srt.contains("\n2\n00:00:00,500 --> 00:00:02,000\nSecondo"))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", SubtitleUtils.vttToSrt("WEBVTT\n"))
    }
}
