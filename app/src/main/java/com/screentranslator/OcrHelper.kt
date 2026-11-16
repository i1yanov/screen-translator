package com.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Текст на картинках:
 *  1) скриншот экрана;
 *  2) ML Kit быстро находит все области с текстом (и сразу читает английский);
 *  3) области, где ML Kit не уверен (иврит и т.п.), дочитывает Tesseract (иврит+английский).
 */
class OcrHelper(context: Context) {

    class Candidate(
        val bounds: Rect,
        val latinText: String,
        val lineHeight: Int,
        val lineCount: Int,
        val confidentLatin: Boolean
    ) {
        /** Блок для оверлея. Рамка ML Kit облегает буквы, строка текста примерно на 25% выше. */
        fun toBlock(text: String) = TextBlock(text, bounds, lineHeight * 1.25f, lineCount)
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val tesseract = TesseractOcr(context)

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hb = result.hardwareBuffer
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                null
                            } finally {
                                hb.close()
                            }
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Шаг 2: найти области текста. */
    suspend fun detect(bitmap: Bitmap): List<Candidate> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<Candidate>()
        for (block in result.textBlocks) {
            val box: Rect = block.boundingBox ?: continue
            if (box.width() < 8 || box.height() < 8) continue
            val lines = block.lines
            if (lines.isEmpty()) continue
            val text = lines.joinToString(" ") { it.text }.trim()
            val heights = lines.mapNotNull { it.boundingBox?.height() }
            val lineH = if (heights.isEmpty()) box.height() / lines.size else heights.sorted()[heights.size / 2]
            val minConf = lines.minOf { it.confidence }
            val confident = minConf >= 0.75f &&
                TranslationEngine.sourceLanguage(text) == TranslateLanguage.ENGLISH &&
                looksLikeWords(text)
            out.add(Candidate(Rect(box), text, lineH.coerceAtLeast(1), lines.size, confident))
        }
        return out
    }

    /** Шаг 3: прочитать область через Tesseract. Возвращает текст или null. */
    suspend fun readWithTesseract(bitmap: Bitmap, c: Candidate): String? {
        if (bitmap.isRecycled) return null
        val pad = (c.lineHeight * 0.3f).toInt().coerceAtLeast(4)
        val r = Rect(c.bounds)
        r.inset(-pad, -pad)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height)) return null
        if (r.width() < 4 || r.height() < 4) return null

        var crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
        val scale = (42f / c.lineHeight).coerceIn(1f, 3f)
        if (scale > 1.05f) {
            val scaled = Bitmap.createScaledBitmap(
                crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true
            )
            if (scaled !== crop && crop !== bitmap) crop.recycle()
            crop = scaled
        }
        val res = tesseract.recognize(crop)
        if (crop !== bitmap) crop.recycle()
        if (res == null) return null
        val (text, conf) = res
        val clean = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return null

        var letters = 0
        var heb = 0
        for (ch in clean) {
            if (Character.isLetter(ch)) letters++
            if (ch in '֐'..'׿') heb++
        }
        if (letters == 0) return null
        // иврит — берём результат Tesseract
        if (heb * 2 >= letters && conf >= 35) return clean
        // английский, если Tesseract уверен
        if (heb == 0 && conf >= 60) return clean
        return null
    }

    fun close() {
        recognizer.close()
        tesseract.close()
    }

    companion object {
        /** Отсекаем «мусор», который ML Kit выдаёт на нелатинских надписях. */
        fun looksLikeWords(text: String): Boolean {
            val words = text.split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return false
            var good = 0
            for (w in words) {
                val letters = w.count { it in 'a'..'z' || it in 'A'..'Z' }
                if (letters >= 2 && letters * 10 >= w.length * 6) good++
            }
            return good * 10 >= words.size * 6
        }
    }
}
