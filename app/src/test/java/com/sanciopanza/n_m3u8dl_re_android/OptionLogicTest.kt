package com.sanciopanza.n_m3u8dl_re_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sanciopanza.n_m3u8dl_re_android.downloader.DownloadOptions
import com.sanciopanza.n_m3u8dl_re_android.downloader.OptionLogic
import com.sanciopanza.n_m3u8dl_re_android.downloader.Segment

class OptionLogicTest {

    private fun segs(n: Int): List<Segment> =
        (0 until n).map { Segment(it, "https://cdn.example.com/seg$it.ts", 6.0) }

    @Test
    fun parseMaxSpeed_units() {
        assertEquals(15_000_000L / 8, OptionLogic.parseMaxSpeedToBps("15M"))
        assertEquals(100_000L / 8, OptionLogic.parseMaxSpeedToBps("100K"))
        assertEquals(0L, OptionLogic.parseMaxSpeedToBps(""))
        assertEquals(0L, OptionLogic.parseMaxSpeedToBps("nope"))
    }

    @Test
    fun customRange_indexForms() {
        assertEquals(11, OptionLogic.applyCustomRange(segs(100), "0-10").size)
        assertEquals(90, OptionLogic.applyCustomRange(segs(100), "10-").size)
        assertEquals(100, OptionLogic.applyCustomRange(segs(100), "-99").size)
        assertEquals(100, OptionLogic.applyCustomRange(segs(100), "").size)
    }

    @Test
    fun customRange_timeForm() {
        // 6s per segmento: 00:12-00:30 => segmenti 2..4
        val out = OptionLogic.applyCustomRange(segs(10), "00:12-00:30")
        assertEquals(listOf(2, 3, 4), out.map { it.index })
    }

    @Test
    fun adKeyword_filters() {
        val list = listOf(
            Segment(0, "https://cdn.example.com/seg0.ts"),
            Segment(1, "https://ads.example.com/ad1.ts")
        )
        val out = OptionLogic.filterAdSegments(list, "ads\\.example")
        assertEquals(1, out.size)
        assertEquals(0, out[0].index)
    }

    @Test
    fun matchSelect_semantics() {
        assertNull(OptionLogic.matchSelect("", "anything"))
        assertNull(OptionLogic.matchSelect("best", "anything"))
        assertTrue(OptionLogic.matchSelect("all", "anything") == true)
        assertTrue(OptionLogic.matchSelect("hvc1", "5000 1920x1080 hvc1") == true)
        assertFalse(OptionLogic.matchSelect("hvc1", "5000 avc1") == true)
    }

    @Test
    fun savePattern_renders() {
        val out = OptionLogic.renderSavePattern(
            "<SaveName>_<Resolution>_<Bandwidth>",
            mapOf("SaveName" to "video", "Resolution" to "1920x1080", "Bandwidth" to "5000")
        )
        assertEquals("video_1920x1080_5000", out)
    }

    @Test
    fun recordLimit_parses() {
        assertEquals(90L, OptionLogic.parseRecordLimitToSec("00:01:30"))
        assertEquals(3723L, OptionLogic.parseRecordLimitToSec("01:02:03"))
        assertEquals(0L, OptionLogic.parseRecordLimitToSec(""))
    }

    @Test
    fun keyEntries_parse() {
        val m = OptionLogic.parseKeyEntries(
            listOf("KID1:KEY1", "PLAINKEY"),
            "kid2=key2"
        )
        assertEquals("KEY1", m["kid1"])
        assertEquals("PLAINKEY", m["__single"])
        assertEquals("key2", m["kid2"])
    }

    @Test
    fun cliCommand_containsFlags() {
        val cmd = OptionLogic.toCliCommand(
            DownloadOptions(
                url = "https://x.example/y.m3u8",
                threadCount = 8,
                customRange = "0-10",
                selectVideo = "hvc1",
                liveRecordLimit = "01:00:00",
                muxAfterDone = true
            )
        )
        assertTrue(cmd.contains("--thread-count 8"))
        assertTrue(cmd.contains("--custom-range 0-10"))
        assertTrue(cmd.contains("-sv \"hvc1\""))
        assertTrue(cmd.contains("--live-record-limit 01:00:00"))
        assertTrue(cmd.contains("-M format=mp4"))
    }
}
