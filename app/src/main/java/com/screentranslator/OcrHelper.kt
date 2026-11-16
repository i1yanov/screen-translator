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
 * Текст со снимка экрана:
 *  1) ML Kit быстро находит все блоки текста (и сразу читает английский);
 *  2) блоки, где ML Kit не уверен (иврит и т.п.), читает Tesseract (иврит+английский).
 */
class OcrHelper(context: Context) {

    class Candidate(
        val bounds: Rect,
        val latinLines: List<String>,
        /** высота строки по рамке букв (ML Kit), px */
        val lineHeight: Int,
        val lineCount: Int,
        val confidentLatin: Boolean
    ) {
        val latinText: String get() = latinLines.joinToString(" ").trim()

        /** Блок для режима «по элементам». Строка текста примерно на 25% выше рамки букв. */
        fun toBlock(text: String) = TextBlock(text, bounds, lineHeight * 1.25f, lineCount)
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val tesseract = TesseractPool(context, 4)

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

    /** Найти блоки текста. */
    suspend fun detect(bitmap: Bitmap): List<Candidate> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<Candidate>()
        for (block in result.textBlocks) {
            val box: Rect = block.boundingBox ?: continue
            if (box.width() < 8 || box.height() < 8) continue
            val lines = block.lines
            if (lines.isEmpty()) continue
            val texts = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) continue
            val joined = texts.joinToString(" ")
            val heights = lines.mapNotNull { it.boundingBox?.height() }.sorted()
            val lineH = if (heights.isEmpty()) box.height() / lines.size else heights[heights.size / 2]
            val minConf = lines.minOf { it.confidence }
            val confident = minConf >= 0.75f &&
                TranslationEngine.sourceLanguage(joined) == TranslateLanguage.ENGLISH &&
                looksLikeWords(joined)
            out.add(Candidate(Rect(box), texts, lineH.coerceAtLeast(1), lines.size, confident))
        }
        return out
    }

    /** Прочитать блок через Tesseract. Возвращает строки или null. */
    suspend fun readWithTesseract(bitmap: Bitmap, c: Candidate): List<String>? {
        if (bitmap.isRecycled) return null
        val pad = (c.lineHeight * 0.35f).toInt().coerceAtLeast(4)
        val r = Rect(c.bounds)
        r.inset(-pad, -pad)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height)) return null
        if (r.width() < 4 || r.height() < 4) return null

        var crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
        val scale = (40f / c.lineHeight).coerceIn(1f, 3f)
        if (scale > 1.05f) {
            val scaled = Bitmap.createScaledBitmap(
                crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true
            )
            if (scaled !== crop && crop !== bitmap) crop.recycle()
            crop = scaled
        }
        val res = tesseract.recognize(crop, c.lineCount <= 1)
        if (crop !== bitmap) crop.recycle()
        if (res == null) return null
        val (raw, conf) = res
        val lines = raw.split('\n')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val clean = lines.joinToString(" ")
        if (clean.length < 2) return null

        var letters = 0
        var heb = 0
        for (ch in clean) {
            if (Character.isLetter(ch)) letters++
            if (ch in '֐'..'׿') heb++
        }
        if (letters == 0) return null
        if (heb * 2 >= letters && conf >= 35) return lines       // иврит
        if (heb == 0 && conf >= 60) return lines                 // английский, если уверен
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
