package com.screentranslator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Tesseract OCR (иврит + английский). Данные языков лежат в assets/tessdata
 * и при первом запуске копируются во внутреннюю память.
 * Tesseract не потокобезопасен — всё выполняется в одном потоке.
 */
class TesseractOcr(private val ctx: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var api: TessBaseAPI? = null
    private var failed = false

    private fun ensure(): TessBaseAPI? {
        api?.let { return it }
        if (failed) return null
        return try {
            val dir = File(ctx.filesDir, "tesseract")
            val tessdata = File(dir, "tessdata")
            tessdata.mkdirs()
            for (name in LANG_FILES) {
                val f = File(tessdata, name)
                if (!f.exists() || f.length() == 0L) {
                    val tmp = File(tessdata, "$name.tmp")
                    ctx.assets.open("tessdata/$name").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.renameTo(f)
                }
            }
            val a = TessBaseAPI()
            if (!a.init(dir.absolutePath, "heb+eng")) {
                a.recycle()
                failed = true
                null
            } else {
                a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                api = a
                a
            }
        } catch (e: Throwable) {
            failed = true
            null
        }
    }

    suspend fun warmUp() {
        withContext(dispatcher) { ensure() }
    }

    /** Возвращает (текст, средняя уверенность 0..100) или null. */
    suspend fun recognize(bmp: Bitmap): Pair<String, Int>? = withContext(dispatcher) {
        val a = ensure() ?: return@withContext null
        try {
            a.setImage(bmp)
            val text = a.getUTF8Text() ?: ""
            val conf = a.meanConfidence()
            a.clear()
            Pair(text.trim(), conf)
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try {
            dispatcher.executor.execute {
                api?.recycle()
                api = null
            }
        } catch (_: Throwable) {
        }
        dispatcher.close()
    }

    companion object {
        private val LANG_FILES = listOf("heb.traineddata", "eng.traineddata")
    }
}
