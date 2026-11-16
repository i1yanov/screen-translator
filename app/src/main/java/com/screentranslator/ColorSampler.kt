package com.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

/** Определяет цвет фона и цвет текста в области снимка экрана (как Google Объектив). */
object ColorSampler {

    /** Возвращает (фон, текст). */
    fun sample(bmp: Bitmap, r: Rect): Pair<Int, Int> {
        if (bmp.isRecycled) return Pair(Color.WHITE, Color.BLACK)
        val outer = Rect(r)
        outer.inset(-3, -3)
        if (!outer.intersect(0, 0, bmp.width, bmp.height)) return Pair(Color.WHITE, Color.BLACK)

        // фон — пиксели по контуру чуть снаружи блока
        val ring = ArrayList<Int>()
        val stepX = maxOf(1, outer.width() / 40)
        val stepY = maxOf(1, outer.height() / 20)
        var x = outer.left
        while (x < outer.right) {
            ring.add(bmp.getPixel(x, outer.top))
            ring.add(bmp.getPixel(x, outer.bottom - 1))
            x += stepX
        }
        var y = outer.top
        while (y < outer.bottom) {
            ring.add(bmp.getPixel(outer.left, y))
            ring.add(bmp.getPixel(outer.right - 1, y))
            y += stepY
        }
        val bg = medianColor(ring)

        // текст — пиксели внутри, заметно отличающиеся от фона
        val inner = Rect(r)
        if (!inner.intersect(0, 0, bmp.width, bmp.height)) return Pair(bg, contrastFor(bg))
        val far = ArrayList<Pair<Int, Int>>()
        val sx = maxOf(1, inner.width() / 80)
        val sy = maxOf(1, inner.height() / 24)
        y = inner.top
        while (y < inner.bottom) {
            x = inner.left
            while (x < inner.right) {
                val c = bmp.getPixel(x, y)
                val d = dist(c, bg)
                if (d > 90) far.add(Pair(d, c))
                x += sx
            }
            y += sy
        }
        var fg = if (far.size >= 3) {
            // берём самые «далёкие» от фона пиксели — это середина букв, без сглаживания
            val sorted = far.sortedByDescending { it.first }
            medianColor(sorted.take(maxOf(3, sorted.size / 3)).map { it.second })
        } else {
            contrastFor(bg)
        }
        if (dist(fg, bg) < 150) fg = contrastFor(bg)
        return Pair(bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt())
    }

    private fun contrastFor(bg: Int): Int =
        if (luminance(bg) > 140) Color.BLACK else Color.WHITE

    private fun luminance(c: Int) = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100

    private fun dist(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun medianColor(list: List<Int>): Int {
        if (list.isEmpty()) return Color.WHITE
        val r = list.map { Color.red(it) }.sorted()[list.size / 2]
        val g = list.map { Color.green(it) }.sorted()[list.size / 2]
        val b = list.map { Color.blue(it) }.sorted()[list.size / 2]
        return Color.rgb(r, g, b)
    }
}
