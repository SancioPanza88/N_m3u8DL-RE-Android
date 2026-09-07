package com.sanciopanza.n_m3u8dl_re_android.downloader

import java.io.File

/**
 * Merge dei segmenti TS senza ricodifica (zero carico GPU/CPU):
 * - binaryMerge=true  => concat binaria (come --binary-merge di N_m3u8DL-RE)
 * - binaryMerge=false => concat binaria con header TS preservato (i .ts HLS si concatenano
 *   in modo valido nella maggior parte dei casi; la mux finale in MP4 è demandata a
 *   MediaMuxer/ExoPlayer a livello app, senza ffmpeg nativo per restare leggeri su ARCVM).
 */
object Muxer {

    fun mergeTs(files: List<File>, outFile: File, binaryMerge: Boolean = false): File {
        require(files.isNotEmpty()) { "No segments to merge" }
        outFile.parentFile?.mkdirs()
        val sorted = files.sortedBy { it.name }
        outFile.outputStream().buffered().use { out ->
            for (f in sorted) {
                f.inputStream().buffered().use { ins ->
                    if (binaryMerge) {
                        ins.copyTo(out)
                    } else {
                        // Concat TS: valido perché ogni segmento HLS è un Transport Stream autonomo.
                        ins.copyTo(out)
                    }
                }
            }
        }
        return outFile
    }
}
