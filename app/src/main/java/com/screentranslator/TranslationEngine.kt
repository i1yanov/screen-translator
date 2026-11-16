package com.screentranslator

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Перевод на русский.
 *  • Онлайн: Google Translate (точнее, особенно для иврита).
 *  • Офлайн (и запасной вариант): ML Kit на устройстве, английский/иврит → русский.
 */
class TranslationEngine {

    private val enRu: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )
    private val heRu: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.HEBREW)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    private val cache = LruCache<String, String>(3000)

    @Volatile
    var ready = false
        private set

    suspend fun ensureModels() {
        val conditions = DownloadConditions.Builder().build()
        enRu.downloadModelIfNeeded(conditions).await()
        heRu.downloadModelIfNeeded(conditions).await()
        ready = true
    }

    suspend fun checkModelsDownloaded(): Boolean {
        val models = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java).await()
        val langs = models.map { it.language }.toSet()
        val ok = TranslateLanguage.HEBREW in langs && TranslateLanguage.RUSSIAN in langs
        if (ok) ready = true
        return ok
    }

    private val onlineLimit = Semaphore(6)

    /** null — переводить не нужно (уже русский, только цифры и т.п.) или ошибка. */
    suspend fun translate(raw: String, online: Boolean): String? {
        val text = raw.trim().take(MAX_LEN)
        if (text.isEmpty()) return null
        cache.get(text)?.let { return it }
        if (!needsTranslation(text)) return null
        if (online) {
            translateOnline(text)?.let {
                cache.put(text, it)
                return it
            }
        }
        val lang = sourceLanguage(text) ?: return null
        val translator = if (lang == TranslateLanguage.HEBREW) heRu else enRu
        return try {
            val result = translator.translate(text).await()
            cache.put(text, result)
            result
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateOnline(text: String): String? = withContext(Dispatchers.IO) {
        onlineLimit.withPermit {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=" +
                        URLEncoder.encode(text, "UTF-8")
                )
                val c = url.openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 4000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (c.responseCode != 200) {
                    null
                } else {
                    val body = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val segments = JSONArray(body).getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        val seg = segments.optJSONArray(i) ?: continue
                        if (!seg.isNull(0)) sb.append(seg.optString(0, ""))
                    }
                    sb.toString().trim().takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun close() {
        enRu.close()
        heRu.close()
    }

    companion object {
        private const val MAX_LEN = 2000

        /** Есть буквы и текст не преимущественно русский. */
        fun needsTranslation(text: String): Boolean {
            var letters = 0
            var cyr = 0
            for (c in text) {
                if (Character.isLetter(c)) letters++
                if (c in '\u0400'..'\u04FF') cyr++
            }
            return letters > 0 && cyr * 2 < letters
        }

        fun sourceLanguage(text: String): String? {
            var heb = 0
            var lat = 0
            var cyr = 0
            for (c in text) {
                when (c) {
                    in '֐'..'׿', in 'יִ'..'ﭏ' -> heb++
                    in 'A'..'Z', in 'a'..'z' -> lat++
                    in 'Ѐ'..'ӿ' -> cyr++
                }
            }
            if (heb == 0 && lat == 0) return null
            if (cyr > heb + lat) return null
            return if (heb >= lat) TranslateLanguage.HEBREW else TranslateLanguage.ENGLISH
        }
    }
}
