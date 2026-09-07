package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Rilevamento accelerazione HW su Chromebook Plus (MediaCodec HW).
 * Chromebook Plus = GPU + decoder HW H.264/HEVC/VP9/AV1 su quasi tutti i modelli
 * (Intel 12th+/AMD 7000+/Kompanio 520+ via ARCVM).
 */
object HwCapDetector {

    data class HwReport(
        val hasHwAvcDecoder: Boolean,
        val hasHwHevcDecoder: Boolean,
        val hasHwVp9Decoder: Boolean,
        val hasHwAv1Decoder: Boolean,
        val hasHwAvcEncoder: Boolean,
        val cpuCores: Int,
        val recommendedThreads: Int,
        val details: String
    )

    fun probe(): HwReport {
        var avcD = false
        var hevcD = false
        var vp9D = false
        var av1D = false
        var avcE = false
        val sb = StringBuilder()
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                val hw = isHardwareAcceleratedCompat(info)
                for (type in info.supportedTypes) {
                    val dec = !info.isEncoder
                    when (type.lowercase()) {
                        "video/avc" -> if (dec && hw) avcD = true else if (!dec && hw) avcE = true
                        "video/hevc" -> if (dec && hw) hevcD = true
                        "video/x-vnd.on2.vp9", "video/vp9" -> if (dec && hw) vp9D = true
                        "video/av01", "video/av1" -> if (dec && hw) av1D = true
                    }
                }
            }
            sb.append("MediaCodec scan OK. ")
        } catch (t: Throwable) {
            sb.append("MediaCodec scan failed: ${t.message}. ")
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val rec = defaultThreadCountForChromebookPlus()
        sb.append("CPU cores=$cores, recommendedThreads=$rec, SDK=${Build.VERSION.SDK_INT}.")
        return HwReport(avcD, hevcD, vp9D, av1D, avcE, cores, rec, sb.toString())
    }

    private fun isHardwareAcceleratedCompat(info: MediaCodecInfo): Boolean {
        return try {
            // isHardwareAccelerated() esiste da API 29. Su API 26-28 fallback euristico sul nome.
            if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated
            } else {
                val name = info.name.lowercase()
                // I codec Google software si chiamano OMX.google.* / c2.android.*
                !(name.startsWith("omx.google.") || name.startsWith("c2.android."))
            }
        } catch (_: Throwable) {
            true
        }
    }

    fun humanReadable(r: HwReport): String {
        return buildString {
            appendLine("HW Chromebook Plus:")
            appendLine("• AVC/H.264 decoder HW: ${if (r.hasHwAvcDecoder) "SI" else "non rilevato"}")
            appendLine("• HEVC/H.265 decoder HW: ${if (r.hasHwHevcDecoder) "SI" else "non rilevato"}")
            appendLine("• VP9 decoder HW: ${if (r.hasHwVp9Decoder) "SI" else "non rilevato"}")
            appendLine("• AV1 decoder HW: ${if (r.hasHwAv1Decoder) "SI" else "non rilevato"}")
            appendLine("• AVC encoder HW: ${if (r.hasHwAvcEncoder) "SI" else "non rilevato"}")
            appendLine("• CPU: ${r.cpuCores} core → thread download consigliati: ${r.recommendedThreads}")
            appendLine("• Preview ExoPlayer con decoder HW prioritario (Media3).")
            appendLine("• Merge/mux via MediaMuxer (zero ricodifica = nessun carico GPU).")
            append(r.details)
        }
    }
}
