package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * WorkManager per download in background (sopravvive a rotazione finestra su Chromebook).
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val saveName = inputData.getString(KEY_SAVE) ?: "video"
        val threads = inputData.getInt(KEY_THREADS, defaultThreadCountForChromebookPlus())
        val headersRaw = inputData.getString(KEY_HEADERS) ?: ""
        val facade = DownloaderFacade(applicationContext)
        val opts = DownloadOptions(
            url = url,
            saveName = saveName.ifBlank { "video" },
            threadCount = threads,
            headers = SettingsStore.parseHeaders(headersRaw)
        )
        return when (val r = facade.run(opts, { done, total ->
            setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        })) {
            is DownloaderFacade.Result.Ok -> Result.success(
                workDataOf(KEY_OUT to r.file.absolutePath, KEY_TOTAL to r.segments)
            )
            is DownloaderFacade.Result.Err -> Result.failure(workDataOf(KEY_ERR to r.message))
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_SAVE = "save"
        const val KEY_THREADS = "threads"
        const val KEY_HEADERS = "headers"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_OUT = "out"
        const val KEY_ERR = "err"
    }
}
