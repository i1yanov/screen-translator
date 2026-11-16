package com.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/** Полноэкранный прозрачный слой, рисует переводы поверх исходного текста. */
class OverlayView(context: Context) : View(context) {

    class Item(val block: TextBlock) {
        var layout: StaticLayout? = null
        var textSize = 0f
        val drawRect = RectF()
        var textX = 0f
        var textY = 0f
    }

    private val items = ArrayList<Item>()
    var message: String? = null
        set(value) { field = value; invalidate() }
    /** затемнять экран под переводом */
    var dim = true
        set(value) { field = value; invalidate() }
    /** показывать «Распознаю текст…» */
    var busy = false
        set(value) { field = value; invalidate() }

    fun isEmpty() = items.isEmpty()

    fun remove(item: Item) {
        if (items.remove(item)) invalidate()
    }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val dimPaint = Paint().apply { color = 0x40000000 }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF7FFFBE6.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0B000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val lensBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val busyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * sp }
    private val msgBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val msgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * sp }

    private val loc = IntArray(2)

    fun clear() {
        items.clear()
        message = null
        invalidate()
    }

    fun addBlocks(blocks: List<TextBlock>): List<Item> {
        val created = blocks.map { Item(it) }
        items.addAll(created)
        return created
    }

    /** Блоки, уже занятые текстом (чтобы OCR не дублировал). */
    fun occupied(): List<Rect> = items.map { it.block.bounds }

    fun setTranslation(item: Item, translated: String) {
        if (item.block.lens) layoutLens(item, translated) else layoutItem(item, translated)
        invalidate()
    }

    /** Как Google Объектив: перевод на месте блока, размер по высоте букв, иврит — справа. */
    private fun layoutLens(item: Item, text: String) {
        val b = item.block
        val r = b.bounds
        val pad = 3 * dp
        val screenW = maxOf(width, resources.displayMetrics.widthPixels, r.right)
        val w0 = r.width().coerceAtLeast(1)
        val h0 = r.height().toFloat()
        val align = if (b.rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        // высота букв оригинала ≈ 0.8 размера шрифта
        val s0 = (b.lineHeightPx * 1.2f).coerceIn(8 * sp, screenW / 8f)

        var layout: StaticLayout? = null
        var size = s0
        var boxW = w0
        var left = r.left.toFloat()

        for (f in floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.62f, 0.55f)) {
            val l = build(text, w0, s0 * f, align)
            if (l.height <= h0 * 1.15f + 1) {
                layout = l; size = s0 * f
                break
            }
        }
        if (layout == null && b.lineCount <= 1) {
            val s = s0 * 0.8f
            textPaint.textSize = s
            val want = (textPaint.measureText(text) + 2).toInt()
            val newW = want.coerceIn(w0, (screenW - 2 * pad).toInt().coerceAtLeast(w0))
            val l = build(text, newW, s, align)
            if (l.height <= h0 * 1.4f + 1) {
                layout = l; size = s; boxW = newW
                left = if (b.rtl) (r.right - newW).toFloat() else r.left.toFloat()
                left = left.coerceIn(pad, (screenW - pad - newW).coerceAtLeast(pad))
            }
        }
        if (layout == null) {
            size = s0 * 0.55f
            layout = build(text, w0, size, align)
        }
        val l = layout!!
        val hShown = maxOf(h0, l.height.toFloat())
        item.layout = l
        item.textSize = size
        item.drawRect.set(left - pad, r.top - pad, left + boxW + pad, r.top + hShown + pad)
        item.textX = left
        item.textY = r.top + (hShown - l.height) / 2f
    }

    /** Отношение высоты строки к размеру шрифта (для Roboto ≈ 1.17). */
    private val lineRatio: Float by lazy {
        val p = TextPaint(textPaint).apply { textSize = 100f }
        val fm = p.fontMetrics
        ((fm.descent - fm.ascent) / 100f).coerceIn(1f, 1.6f)
    }

    /** Размер шрифта оригинала в пикселях. */
    private fun originalTextSize(b: TextBlock): Float {
        val screenW = maxOf(width, resources.displayMetrics.widthPixels)
        // шрифт не может быть выше своей строки/рамки и не больше ~1/10 ширины экрана
        val cap = minOf(screenW / 10f, b.bounds.height() / lineRatio * 1.1f).coerceAtLeast(7 * sp)
        if (b.lineHeightPx > 0f) {
            return (b.lineHeightPx / lineRatio).coerceIn(7 * sp, cap)
        }
        // Нет данных о буквах: подбираем размер, при котором оригинал занимает свою рамку
        val w = b.bounds.width().coerceAtLeast(1)
        val h = b.bounds.height()
        var lo = 7 * sp
        var hi = (h / lineRatio).coerceAtLeast(lo)
        repeat(10) {
            val mid = (lo + hi) / 2
            if (build(b.text, w, mid).height <= h) lo = mid else hi = mid
        }
        return lo.coerceAtMost(minOf(22 * sp, cap))
    }

    private fun layoutItem(item: Item, text: String) {
        val b = item.block
        val r = b.bounds
        val pad = 2 * dp
        val screenW = maxOf(width, resources.displayMetrics.widthPixels, r.right)
        val w0 = r.width().coerceAtLeast(1)
        val h0 = r.height().toFloat()
        val s0 = originalTextSize(b)

        var layout: StaticLayout? = null
        var size = s0
        var boxW = w0
        var left = r.left.toFloat()

        // 1) тот же размер и та же рамка; если не влезает — чуть уменьшаем (до 78%)
        for (f in floatArrayOf(1f, 0.92f, 0.85f, 0.78f)) {
            val l = build(text, w0, s0 * f)
            if (l.height <= h0 * 1.12f + 1) {
                layout = l; size = s0 * f
                break
            }
        }

        // 2) оригинал в одну строку — лучше расширить поле вбок, чем переносить
        if (layout == null) {
            val singleLine = b.lineCount == 1 || (b.lineCount == 0 && h0 < 2.2f * s0 * lineRatio)
            if (singleLine) {
                val s = s0 * 0.85f
                textPaint.textSize = s
                val want = (textPaint.measureText(text) + 2).toInt()
                val maxW = (screenW - 2 * pad).toInt().coerceAtLeast(w0)
                val newW = want.coerceIn(w0, maxW)
                val l = build(text, newW, s)
                if (l.height <= h0 * 1.35f + 1) {
                    layout = l; size = s; boxW = newW
                    left = r.left.toFloat()
                    if (left + newW > screenW - pad) left = (screenW - pad - newW).coerceAtLeast(pad)
                }
            }
        }

        // 3) иначе — переносим строки и растягиваем поле вниз
        if (layout == null) {
            size = s0 * 0.78f
            layout = build(text, w0, size)
        }

        var l = layout!!
        var top = r.top.toFloat()
        var contentH = maxOf(h0, l.height.toFloat())

        // Точного места букв нет (рамка = весь элемент, бывает с карточку):
        // ужимаем плашку до самого текста — по центру по высоте, для иврита справа
        if (b.lineHeightPx <= 0f) {
            var textW = 0f
            for (i in 0 until l.lineCount) textW = maxOf(textW, l.getLineWidth(i))
            val tw = (textW + 2).toInt().coerceAtMost(boxW)
            if (tw < boxW * 0.9f) {
                val rtl = TranslationEngine.sourceLanguage(b.text) == com.google.mlkit.nl.translate.TranslateLanguage.HEBREW
                if (rtl) left += (boxW - tw)
                boxW = tw
                l = build(text, boxW, size)
            }
            if (h0 > l.height * 1.5f) {
                top += (h0 - l.height) / 2f
                contentH = l.height.toFloat()
            }
        }

        val hShown = maxOf(contentH, l.height.toFloat())
        item.layout = l
        item.textSize = size
        item.drawRect.set(left - pad, top - pad, left + boxW + pad, top + hShown + pad)
        item.textX = left
        // если перевод ниже оригинала — центрируем по вертикали в его рамке
        item.textY = top + ((hShown - l.height) / 2f).coerceAtLeast(0f)
    }

    private fun build(
        text: String, width: Int, size: Float,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        if (dim) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (item in items) {
            val layout = item.layout ?: continue
            val rect = item.drawRect
            if (item.block.bgColor != 0) {
                lensBgPaint.color = item.block.bgColor
                canvas.drawRoundRect(rect, 4 * dp, 4 * dp, lensBgPaint)
                textPaint.color = item.block.fgColor
            } else {
                canvas.drawRoundRect(rect, 3 * dp, 3 * dp, bgPaint)
                canvas.drawRoundRect(rect, 3 * dp, 3 * dp, borderPaint)
                textPaint.color = Color.BLACK
            }
            canvas.save()
            canvas.translate(item.textX, item.textY)
            textPaint.textSize = item.textSize
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        if (busy) {
            val label = "Распознаю текст…"
            val tw = busyPaint.measureText(label)
            val cx = width / 2f
            val cy = height * 0.9f
            val p = 10 * dp
            canvas.drawRoundRect(cx - tw / 2 - p, cy - 18 * dp, cx + tw / 2 + p, cy + 10 * dp, 14 * dp, 14 * dp, msgBgPaint)
            canvas.drawText(label, cx - tw / 2, cy, busyPaint)
        }

        message?.let { msg ->
            val w = (width * 0.9f).toInt()
            val l = StaticLayout.Builder.obtain(msg, 0, msg.length, msgPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            val top = height * 0.42f
            val p = 12 * dp
            val left = (width - w) / 2f
            canvas.drawRoundRect(left - p, top - p, left + w + p, top + l.height + p, 12 * dp, 12 * dp, msgBgPaint)
            canvas.save()
            canvas.translate(left, top)
            l.draw(canvas)
            canvas.restore()
        }
    }
}
