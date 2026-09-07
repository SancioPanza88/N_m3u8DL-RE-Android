package com.sanciopanza.n_m3u8dl_re_android.ui

import android.graphics.Typeface
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
import com.sanciopanza.n_m3u8dl_re_android.downloader.OptionLogic
import com.sanciopanza.n_m3u8dl_re_android.downloader.SettingsStore
import com.sanciopanza.n_m3u8dl_re_android.downloader.defaultThreadCountForChromebookPlus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stesso programma, stesse opzioni dell'originale N_m3u8DL-RE (nilaoda, MIT):
 * ogni campo riporta la flag CLI corrispondente. Il pulsante "Comando CLI"
 * mostra il comando equivalente per PC a riprova della parità.
 * UI ottimizzata Chromebook Plus: scrollabile, tastiera/mouse, resize ARCVM.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var facade: DownloaderFacade
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar

    private val t = mutableMapOf<String, TextInputEditText>()
    private val c = mutableMapOf<String, CheckBox>()

    private fun field(key: String, hint: String, root: LinearLayout): TextInputEditText {
        val layout = TextInputLayout(this).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context)
        layout.addView(edit)
        root.addView(layout)
        t[key] = edit
        return edit
    }

    private fun check(key: String, label: String, root: LinearLayout, def: Boolean = false): CheckBox {
        val cb = CheckBox(this).apply { text = label; isChecked = def }
        root.addView(cb)
        c[key] = cb
        return cb
    }

    private fun section(title: String, root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })
    }

    private fun tv(key: String): String = t[key]?.text?.toString()?.trim().orEmpty()
    private fun cb(key: String): Boolean = c[key]?.isChecked == true
    private fun num(key: String, def: Int): Int = tv(key).toIntOrNull() ?: def

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)
        facade = DownloaderFacade(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // ---- Input / Rete ----
        section("Input / Rete", root)
        field("url", "<input> URL M3U8 / MPD", root).imeOptions = EditorInfo.IME_ACTION_DONE
        field("baseUrl", "--base-url", root)
        field("headers", "-H, --header (Cookie: x; una per riga o ;)", root)
        field("proxy", "--custom-proxy (http://127.0.0.1:8888)", root)
        field("timeout", "--http-request-timeout sec [100]", root)
        field("taskAt", "--task-start-at yyyyMMddHHmmss", root)
        check("appendQ", "--append-url-params", root)

        // ---- Output ----
        section("Output", root)
        field("save", "--save-name [video]", root)
        field("pattern", "--save-pattern (<SaveName>_<Resolution>_<Bandwidth>)", root)
        field("subFmt", "--sub-format SRT|VTT [SRT]", root)
        field("logLevel", "--log-level DEBUG|INFO|WARN|ERROR|OFF [INFO]", root)
        check("meta", "--write-meta-json [ON]", root, true)
        check("noLog", "--no-log", root)

        // ---- Download ----
        section("Download", root)
        field("threads", "--thread-count [core Chromebook]", root)
        field("retry", "--download-retry-count [3]", root)
        field("speed", "-R, --max-speed (15M / 100K)", root)
        field("range", "--custom-range (0-10 / 10- / 05:00-20:00)", root)
        field("ad", "--ad-keyword (regex anti-pubblicità)", root)
        check("mt", "-mt, --concurrent-download (video+audio assieme)", root)
        check("skipDl", "--skip-download (solo parse+meta)", root)
        check("skipMerge", "--skip-merge", root)
        check("check", "--check-segments-count [ON]", root, true)
        check("bin", "--binary-merge", root)
        check("del", "--del-after-done [ON]", root, true)
        check("multimap", "--allow-hls-multi-ext-map", root)

        // ---- Selezione stream ----
        section("Selezione stream (-sv/-sa/-ss/-dv/-da/-ds)", root)
        check("autosel", "--auto-select miglior traccia [ON]", root, true)
        field("sv", "-sv, --select-video (regex, all, best)", root)
        field("sa", "-sa, --select-audio (regex, all, best)", root)
        field("ss", "-ss, --select-subtitle (regex, all, best)", root)
        field("dv", "-dv, --drop-video (regex)", root)
        field("da", "-da, --drop-audio (regex)", root)
        field("ds", "-ds, --drop-subtitle (regex)", root)
        check("subOnly", "--sub-only (solo sottotitoli)", root)

        // ---- Sottotitoli ----
        section("Sottotitoli", root)
        check("subFix", "--auto-subtitle-fix [ON]", root, true)
        check("fixVtt", "--live-fix-vtt-by-audio", root)

        // ---- Decifratura ----
        section("Decifratura (--key / --custom-hls-*)", root)
        field("keys", "--key (uno per riga: KID:KEY o KEY)", root)
        field("keyFile", "--key-text-file (incolla contenuto KID:KEY)", root)
        field("hlsM", "--custom-hls-method (AES_128|...|NONE)", root)
        field("hlsK", "--custom-hls-key (HEX)", root)
        field("hlsI", "--custom-hls-iv (HEX)", root)

        // ---- Live ----
        section("Live", root)
        check("vod", "--live-perform-as-vod", root)
        check("rtMerge", "--live-real-time-merge", root)
        check("keepSeg", "--live-keep-segments [ON]", root, true)
        field("recLimit", "--live-record-limit HH:mm:ss", root)
        field("wait", "--live-wait-time sec [da playlist]", root)
        field("take", "--live-take-count [16]", root)

        // ---- Mux -M ----
        section("Mux -M, --mux-after-done", root)
        check("mux", "-M unisci video+audio+subs in un unico file [ON]", root, true)
        field("muxFmt", "-M format= (mp4)", root)
        check("muxKeep", "-M keep=true (conserva temporanei)", root)
        check("muxSkipSub", "-M skip_sub=true (ignora sottotitoli nel mux)", root)
        field("muxImport", "--mux-import path= (file esterno)", root)

        val btnDownload = MaterialButton(this).apply { text = "Scarica" }
        val btnCli = MaterialButton(this).apply { text = "Comando CLI" }
        val btnHw = MaterialButton(this).apply { text = "Verifica HW Chromebook" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        logView = TextView(this).apply {
            text = "N_m3u8DL-RE Android • stesso programma, stesse opzioni (nilaoda/N_m3u8DL-RE, MIT).\n" +
                "Thread default Chromebook: ${defaultThreadCountForChromebookPlus()}.\n"
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(btnDownload)
        root.addView(btnCli)
        root.addView(btnHw)
        root.addView(progress)
        root.addView(scroll)

        val page = ScrollView(this).apply { addView(root) }
        setContentView(page)

        intent?.data?.toString()?.let {
            if (it.startsWith("http")) t["url"]?.setText(it)
        }

        lifecycleScope.launch { fillFromStore() }

        btnHw.setOnClickListener {
            lifecycleScope.launch {
                appendLog("Rilevamento HW…\n")
                val report = withContext(Dispatchers.Default) { HwCapDetector.probe() }
                appendLog(HwCapDetector.humanReadable(report) + "\n")
            }
        }

        btnCli.setOnClickListener {
            appendLog("CLI equivalente:\n" + buildOpts().let { OptionLogic.toCliCommand(it) } + "\n")
        }

        btnDownload.setOnClickListener {
            val o = buildOpts()
            if (!o.url.startsWith("http")) {
                appendLog("ERRORE: incolla un URL http(s) valido.\n")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                store.saveAllOptions(o, tv("headers"))
                appendLog("Avvio: ${o.url}\n")
                progress.progress = 0
                when (val r = facade.run(o, { done, total ->
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

    private fun buildOpts(): DownloadOptions = DownloadOptions(
        url = tv("url"),
        baseUrl = tv("baseUrl"),
        headers = SettingsStore.parseHeaders(tv("headers")),
        customProxy = tv("proxy"),
        httpTimeoutSec = num("timeout", 100),
        taskStartAt = tv("taskAt"),
        appendUrlParams = cb("appendQ"),
        saveName = tv("save").ifBlank { "video" },
        savePattern = tv("pattern"),
        writeMetaJson = cb("meta"),
        noLog = cb("noLog"),
        logLevel = tv("logLevel").ifBlank { "INFO" }.uppercase(),
        threadCount = num("threads", defaultThreadCountForChromebookPlus()),
        retryCount = num("retry", 3),
        concurrentDownload = cb("mt"),
        maxSpeed = tv("speed"),
        skipDownload = cb("skipDl"),
        skipMerge = cb("skipMerge"),
        checkSegmentsCount = cb("check"),
        binaryMerge = cb("bin"),
        delAfterDone = cb("del"),
        customRange = tv("range"),
        adKeyword = tv("ad"),
        allowHlsMultiExtMap = cb("multimap"),
        autoSelectBest = cb("autosel"),
        selectVideo = tv("sv"),
        selectAudio = tv("sa"),
        selectSubtitle = tv("ss"),
        dropVideo = tv("dv"),
        dropAudio = tv("da"),
        dropSubtitle = tv("ds"),
        subOnly = cb("subOnly"),
        subFormat = tv("subFmt").ifBlank { "SRT" }.uppercase(),
        autoSubtitleFix = cb("subFix"),
        liveFixVttByAudio = cb("fixVtt"),
        keyEntries = tv("keys").lines().map { it.trim() }.filter { it.isNotEmpty() },
        keyTextFileContent = tv("keyFile"),
        customHlsMethod = tv("hlsM").uppercase(),
        customHlsKeyHex = tv("hlsK").ifEmpty { null },
        customHlsIvHex = tv("hlsI").ifEmpty { null },
        livePerformAsVod = cb("vod"),
        liveRealTimeMerge = cb("rtMerge"),
        liveKeepSegments = cb("keepSeg"),
        liveRecordLimit = tv("recLimit"),
        liveWaitTimeSec = num("wait", 0),
        liveTakeCount = num("take", 16),
        muxAfterDone = cb("mux"),
        muxFormat = tv("muxFmt").ifBlank { "mp4" }.lowercase(),
        muxKeepFiles = cb("muxKeep"),
        muxSkipSub = cb("muxSkipSub"),
        muxImportPath = tv("muxImport")
    )

    private suspend fun fillFromStore() {
        val o = store.load()
        fun set(k: String, v: String) { t[k]?.setText(v) }
        fun setC(k: String, v: Boolean) { c[k]?.isChecked = v }
        fun setN(k: String, v: Int, def: Int) { t[k]?.setText(if (v == def) "" else v.toString()) }
        set("baseUrl", o.baseUrl)
        set("proxy", o.customProxy)
        setN("timeout", o.httpTimeoutSec, 100)
        set("taskAt", o.taskStartAt)
        setC("appendQ", o.appendUrlParams)
        set("save", if (o.saveName == "video") "" else o.saveName)
        set("pattern", o.savePattern)
        set("subFmt", if (o.subFormat == "SRT") "" else o.subFormat)
        set("logLevel", if (o.logLevel == "INFO") "" else o.logLevel)
        setC("meta", o.writeMetaJson)
        setC("noLog", o.noLog)
        setN("threads", o.threadCount, defaultThreadCountForChromebookPlus())
        setN("retry", o.retryCount, 3)
        set("speed", o.maxSpeed)
        set("range", o.customRange)
        set("ad", o.adKeyword)
        setC("mt", o.concurrentDownload)
        setC("skipDl", o.skipDownload)
        setC("skipMerge", o.skipMerge)
        setC("check", o.checkSegmentsCount)
        setC("bin", o.binaryMerge)
        setC("del", o.delAfterDone)
        setC("multimap", o.allowHlsMultiExtMap)
        setC("autosel", o.autoSelectBest)
        set("sv", o.selectVideo)
        set("sa", o.selectAudio)
        set("ss", o.selectSubtitle)
        set("dv", o.dropVideo)
        set("da", o.dropAudio)
        set("ds", o.dropSubtitle)
        setC("subOnly", o.subOnly)
        setC("subFix", o.autoSubtitleFix)
        setC("fixVtt", o.liveFixVttByAudio)
        set("keys", o.keyEntries.joinToString("\n"))
        set("keyFile", o.keyTextFileContent)
        set("hlsM", o.customHlsMethod)
        set("hlsK", o.customHlsKeyHex ?: "")
        set("hlsI", o.customHlsIvHex ?: "")
        setC("vod", o.livePerformAsVod)
        setC("rtMerge", o.liveRealTimeMerge)
        setC("keepSeg", o.liveKeepSegments)
        set("recLimit", o.liveRecordLimit)
        setN("wait", o.liveWaitTimeSec, 0)
        setN("take", o.liveTakeCount, 16)
        setC("mux", o.muxAfterDone)
        set("muxFmt", if (o.muxFormat == "mp4") "" else o.muxFormat)
        setC("muxKeep", o.muxKeepFiles)
        setC("muxSkipSub", o.muxSkipSub)
        set("muxImport", o.muxImportPath)
    }

    private fun appendLog(s: String) {
        runOnUiThread { logView.append(s) }
    }
}
