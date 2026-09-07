package com.sanciopanza.n_m3u8dl_re_android.downloader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nm3u8dl_settings")

/** Persistenza leggera delle preferenze (thread count, headers, save name). */
class SettingsStore(private val context: Context) {

    private val KEY_THREADS = intPreferencesKey("thread_count")
    private val KEY_SAVE_NAME = stringPreferencesKey("save_name")
    private val KEY_HEADERS = stringPreferencesKey("headers")

    suspend fun loadThreads(): Int =
        context.dataStore.data.map { it[KEY_THREADS] ?: defaultThreadCountForChromebookPlus() }.first()

    suspend fun saveThreads(v: Int) {
        context.dataStore.edit { it[KEY_THREADS] = v }
    }

    suspend fun loadSaveName(): String =
        context.dataStore.data.map { it[KEY_SAVE_NAME] ?: "video" }.first()

    suspend fun saveAll(threads: Int, saveName: String, headersRaw: String) {
        context.dataStore.edit {
            it[KEY_THREADS] = threads
            it[KEY_SAVE_NAME] = saveName
            it[KEY_HEADERS] = headersRaw
        }
    }

    companion object {
        /** "Cookie: x; User-Agent: y" oppure righe "K: V" => mappa headers. */
        fun parseHeaders(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val lines = raw.split("\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
            for (l in lines) {
                val idx = l.indexOf(":")
                if (idx > 0) {
                    out[l.substring(0, idx).trim()] = l.substring(idx + 1).trim()
                }
            }
            return out
        }
    }
}
