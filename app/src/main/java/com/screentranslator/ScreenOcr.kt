package com.screentranslator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs

/**
 * Распознавание всего экрана через Tesseract:
 * снимок режется на полосы по пустым строкам пикселей, полосы обрабатываются параллельно,
 * найденные строки склеиваются в абзацы.
 */
object ScreenOcr {

    class Strip(val bitmap: Bitmap, val top: Int)

    class Paragraph(val lines: List<OcrLine>) {
        val bounds: Rect = Rect(lines[0].bounds).also { r -> lines.forEach { r.union(it.bounds) } }
        val lineHeight: Int = lines.map { it.bounds.height() }.sorted()[lines.size / 2]
        val rtl: Boolean = lines.any { l -> l.text.any { it in '֐'..'׿' } }
    }

    /** Режем снимок на полосы; границы — по однотонным строкам, чтобы не разрезать текст. */
    fun makeStrips(shot: Bitmap, count: Int, skipTop: Int): List<Strip> {
        val h = shot.height
        val start = skipTop.coerceIn(0, h - 1)
        val cuts = ArrayList<Int>()
        cuts.add(start)
        val span = (h - start) / count
        for (k in 1 until count) {
            val target = start + span * k
            cuts.add(findQuietRow(shot, target, span / 3))
        }
        cuts.add(h)
        val out = ArrayList<Strip>()
        for (i in 0 until cuts.size - 1) {
            val top = cuts[i]
            val bottom = cuts[i + 1]
            if (bottom - top < 16) continue
            out.add(Strip(prepare(shot, Rect(0, top, shot.width, bottom)), top))
        }
        return out
    }

    private fun findQuietRow(bmp: Bitmap, target: Int, radius: Int): Int {
        var best = target
        var bestScore = Int.MAX_VALUE
        val step = maxOf(1, bmp.width / 120)
        for (d in 0..radius) {
            for (y in intArrayOf(target - d, target + d)) {
                if (y <= 0 || y >= bmp.height - 1) continue
                var min = 255
                var max = 0
                var x = 0
                while (x < bmp.width) {
                    val l = lum(bmp.getPixel(x, y))
                    if (l < min) min = l
                    if (l > max) max = l
                    x += step
                }
                val score = max - min
                if (score < 10) return y
                if (score < bestScore) {
                    bestScore = score; best = y
                }
            }
        }
        return best
    }

    /** Полоса в оттенках серого; тёмная тема инвертируется (Tesseract лучше читает тёмное на светлом). */
    private fun prepare(src: Bitmap, r: Rect): Bitmap {
        var dark = 0
        var total = 0
        val step = maxOf(2, minOf(r.width(), r.height()) / 30)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                if (lum(src.getPixel(x, y)) < 110) dark++
                total++
                x += step
            }
            y += step
        }
        val invert = dark * 2 > total
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        if (invert) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, r, Rect(0, 0, r.width(), r.height()), paint)
        return out
    }

    /** Склеиваем соседние строки в абзацы (для перевода с контекстом). */
    fun group(lines: List<OcrLine>): List<Paragraph> {
        val sorted = lines.sortedBy { it.bounds.top }
        val paras = ArrayList<MutableList<OcrLine>>()
        for (line in sorted) {
            val target = paras.lastOrNull { p -> fits(p, line) }
            if (target != null) target.add(line) else paras.add(mutableListOf(line))
        }
        return paras.map { Paragraph(it) }
    }

    private fun fits(p: List<OcrLine>, line: OcrLine): Boolean {
        val prev = p.last()
        val a = prev.bounds
        val b = line.bounds
        val h = maxOf(a.height(), b.height()).toFloat()
        val gap = b.top - a.bottom
        if (gap < -h * 0.3f || gap > h * 0.8f) return false
        val ratio = b.height().toFloat() / a.height().coerceAtLeast(1)
        if (ratio < 0.7f || ratio > 1.4f) return false
        if (b.right < a.left || b.left > a.right) return false
        val rtl = prev.text.any { it in '֐'..'׿' }
        return if (rtl) abs(a.right - b.right) < h * 1.5f else abs(a.left - b.left) < h * 1.5f
    }

    /** Отбрасываем строки, похожие на мусор распознавания. */
    fun isGood(line: OcrLine): Boolean {
        val t = line.text
        var letters = 0
        var heb = 0
        var lat = 0
        for (c in t) {
            if (Character.isLetter(c)) letters++
            if (c in '֐'..'׿') heb++
            if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        if (letters < 2) return false
        if (heb * 2 >= letters) return line.conf >= 45f
        if (lat * 2 >= letters) return line.conf >= 70f || (line.conf >= 55f && OcrHelper.looksLikeWords(t))
        return false
    }

    private fun lum(c: Int) = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
}
