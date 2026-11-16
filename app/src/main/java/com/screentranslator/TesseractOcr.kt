package com.screentranslator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Один экземпляр Tesseract OCR (иврит + английский) в своём потоке.
 * Tesseract не потокобезопасен, поэтому для скорости держим несколько экземпляров (TesseractPool).
 */
class TesseractOcr(private val ctx: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var api: TessBaseAPI? = null
    private var failed = false

    private fun ensure(): TessBaseAPI? {
        api?.let { return it }
        if (failed) return null
        return try {
            val dir = prepareData(ctx) ?: run { failed = true; return null }
            val a = TessBaseAPI()
            if (!a.init(dir.absolutePath, "heb+eng")) {
                a.recycle()
                failed = true
                null
            } else {
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
    suspend fun recognize(bmp: Bitmap, singleLine: Boolean): Pair<String, Int>? = withContext(dispatcher) {
        val a = ensure() ?: return@withContext null
        try {
            a.setPageSegMode(
                if (singleLine) TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            )
            a.setImage(bmp)
            val text = a.getUTF8Text() ?: ""
            val conf = a.meanConfidence()
            a.clear()
            Pair(text, conf)
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

        /** Копирует языковые данные из assets во внутреннюю память (один раз). */
        @Synchronized
        fun prepareData(ctx: Context): File? {
            return try {
                val dir = File(ctx.filesDir, "tesseract")
                val tessdata = File(dir, "tessdata")
                tessdata.mkdirs()
                val versionFile = File(tessdata, "version")
                val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
                val upToDate = versionFile.exists() && versionFile.readText() == version
                for (name in LANG_FILES) {
                    val f = File(tessdata, name)
                    if (!upToDate || !f.exists() || f.length() == 0L) {
                        val tmp = File(tessdata, "$name.tmp")
                        ctx.assets.open("tessdata/$name").use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        f.delete()
                        tmp.renameTo(f)
                    }
                }
                versionFile.writeText(version)
                dir
            } catch (e: Throwable) {
                null
            }
        }
    }
}

/** Несколько экземпляров Tesseract — строки распознаются параллельно. */
class TesseractPool(ctx: Context, size: Int) {

    private val workers = List(size) { TesseractOcr(ctx) }
    private val free = Channel<TesseractOcr>(size).also { ch -> workers.forEach { ch.trySend(it) } }

    suspend fun warmUp() = coroutineScope {
        workers.forEach { w -> launch { w.warmUp() } }
    }

    suspend fun recognize(bmp: Bitmap, singleLine: Boolean): Pair<String, Int>? {
        val w = free.receive()
        try {
            return w.recognize(bmp, singleLine)
        } finally {
            free.trySend(w)
        }
    }

    fun close() {
        workers.forEach { it.close() }
    }
}
