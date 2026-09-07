package com.sanciopanza.n_m3u8dl_re_android.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sanciopanza.n_m3u8dl_re_android.downloader.DownloadOptions
import com.sanciopanza.n_m3u8dl_re_android.downloader.DownloaderFacade
import com.sanciopanza.n_m3u8dl_re_android.downloader.HwCapDetector
import com.sanciopanza.n_m3u8dl_re_android.downloader.SettingsStore
import com.sanciopanza.n_m3u8dl_re_android.downloader.defaultThreadCountForChromebookPlus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI ottimizzata Chromebook Plus: layout single-pane che si allarga su schermi larghi,
 * supporto tastiera (IME action Done), mouse e resize finestra ARCVM.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var saveInput: TextInputEditText
    private lateinit var threadsInput: TextInputEditText
    private lateinit var headersInput: TextInputEditText
    private lateinit var muxCheck: CheckBox
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var store: SettingsStore
    private lateinit var facade: DownloaderFacade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)
        facade = DownloaderFacade(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun addField(hint: String): TextInputEditText {
            val layout = TextInputLayout(this).apply { this.hint = hint }
            val edit = TextInputEditText(layout.context)
            layout.addView(edit)
            root.addView(layout)
            return edit
        }

        urlInput = addField("URL M3U8 / MPD (https://…)")
        urlInput.imeOptions = EditorInfo.IME_ACTION_DONE
        saveInput = addField("Nome file (default: video)")
        threadsInput = addField("Thread (default: core Chromebook)")
        headersInput = addField("Headers opzionali  (es. Cookie: x)")

        muxCheck = CheckBox(this).apply {
            text = "Unisci automaticamente video + audio + sottotitoli in un unico file (consigliato)"
            isChecked = true
        }
        root.addView(muxCheck)

        val btnDownload = MaterialButton(this).apply { text = "Scarica" }
        val btnHw = MaterialButton(this).apply { text = "Verifica HW Chromebook" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        logView = TextView(this).apply {
            text = "N_m3u8DL-RE Android • port del progetto nilaoda/N_m3u8DL-RE (MIT).\n" +
                "Ottimizzato Chromebook Plus: thread = ${defaultThreadCountForChromebookPlus()}, merge senza ricodifica, preview HW ExoPlayer.\n"
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(btnDownload)
        root.addView(btnHw)
        root.addView(progress)
        root.addView(scroll)
        setContentView(root)

        // Intent VIEW (apri link da Chrome su Chromebook)
        intent?.data?.toString()?.let {
            if (it.startsWith("http")) urlInput.setText(it)
        }

        lifecycleScope.launch {
            val t = store.loadThreads()
            val s = store.loadSaveName()
            threadsInput.setText(t.toString())
            saveInput.setText(s)
            muxCheck.isChecked = store.loadMux()
        }

        btnHw.setOnClickListener {
            lifecycleScope.launch {
                appendLog("Rilevamento HW…\n")
                val report = withContext(Dispatchers.Default) { HwCapDetector.probe() }
                appendLog(HwCapDetector.humanReadable(report) + "\n")
            }
        }

        btnDownload.setOnClickListener {
            val url = urlInput.text?.toString()?.trim().orEmpty()
            if (!url.startsWith("http")) {
                appendLog("ERRORE: incolla un URL http(s) valido.\n")
                return@setOnClickListener
            }
            val save = saveInput.text?.toString()?.trim().orEmpty().ifBlank { "video" }
            val threads = threadsInput.text?.toString()?.trim()?.toIntOrNull()
                ?: defaultThreadCountForChromebookPlus()
            val headers = SettingsStore.parseHeaders(headersInput.text?.toString().orEmpty())
            val mux = muxCheck.isChecked
            lifecycleScope.launch {
                store.saveAll(threads, save, headersInput.text?.toString().orEmpty(), mux)
                appendLog("Avvio: $url\nThread=$threads save=$save mux=${if (mux) "ON (unico file)" else "OFF (separati)"}\n")
                progress.progress = 0
                val opts = DownloadOptions(
                    url = url,
                    saveName = save,
                    threadCount = threads,
                    headers = headers,
                    muxAfterDone = mux
                )
                when (val r = facade.run(opts, { done, total ->
                    runOnUiThread {
                        progress.max = total.coerceAtLeast(1)
                        progress.progress = done
                    }
                })) {
                    is DownloaderFacade.Result.Ok -> {
                        val subs = r.subsFile?.let { "\n+Sottotitoli: ${it.absolutePath}" } ?: ""
                        appendLog("OK: ${r.segments} segmenti → ${r.file.absolutePath}$subs\n${r.log}\n")
                    }
                    is DownloaderFacade.Result.Err ->
                        appendLog("FALLITO: ${r.message}\n")
                }
            }
        }
    }

    private fun appendLog(s: String) {
        runOnUiThread { logView.append(s) }
    }
}
