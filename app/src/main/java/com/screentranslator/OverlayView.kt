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
    }

    private val items = ArrayList<Item>()
    var message: String? = null
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val dimPaint = Paint().apply { color = 0x40000000 }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF7FFFBE6.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0B000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
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
        layoutItem(item, translated)
        invalidate()
    }

    private fun layoutItem(item: Item, text: String) {
        val r = item.block.bounds
        val pad = (2 * dp).toInt()
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(r.right)
        var width = r.width() - 2 * pad
        // слишком узкие подписи (под иконками) немного расширяем
        val minWidth = (72 * dp).toInt()
        var left = r.left.toFloat()
        if (width < minWidth) {
            val extra = minWidth - width
            width = minWidth
            left = (r.left - extra / 2f).coerceIn(0f, (screenW - width - 2 * pad).toFloat().coerceAtLeast(0f))
        }
        val availH = (r.height() - 2 * pad).coerceAtLeast((10 * sp).toInt())

        val maxSize = (22 * sp).coerceAtMost(availH * 0.85f).coerceAtLeast(9 * sp)
        val minSize = 8 * sp
        var lo = minSize
        var hi = maxSize
        var best = build(text, width, minSize)
        var bestSize = minSize
        // бинарный поиск наибольшего размера, при котором текст влезает по высоте
        repeat(8) {
            val mid = (lo + hi) / 2
            val l = build(text, width, mid)
            if (l.height <= availH) {
                best = l; bestSize = mid; lo = mid
            } else {
                hi = mid
            }
        }
        val big = build(text, width, maxSize)
        if (big.height <= availH) { best = big; bestSize = maxSize }

        item.layout = best
        item.textSize = bestSize
        val h = maxOf(r.height().toFloat(), best.height + 2f * pad)
        item.drawRect.set(left, r.top.toFloat(), left + width + 2 * pad, r.top + h)
    }

    private fun build(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val pad = 2 * dp
        for (item in items) {
            val layout = item.layout ?: continue
            val rect = item.drawRect
            canvas.drawRoundRect(rect, 3 * dp, 3 * dp, bgPaint)
            canvas.drawRoundRect(rect, 3 * dp, 3 * dp, borderPaint)
            canvas.save()
            canvas.translate(rect.left + pad, rect.top + pad)
            textPaint.textSize = item.textSize
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

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
