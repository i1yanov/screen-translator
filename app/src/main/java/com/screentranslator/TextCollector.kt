package com.screentranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Блок текста на экране.
 * @param bounds   где находится сам текст (по возможности — точно по буквам, без отступов)
 * @param lineHeightPx высота одной строки оригинала в пикселях (0 — неизвестно)
 * @param lineCount  сколько строк занимает оригинал (0 — неизвестно)
 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val lineHeightPx: Float = 0f,
    val lineCount: Int = 0
)

/** Собирает видимый текст и его координаты из всех окон на экране. */
object TextCollector {

    private const val MAX_CHARS_MEASURED = 400

    fun collect(service: AccessibilityService, ownPackage: String, screen: Rect): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (windows.isNotEmpty()) {
            // снизу вверх, чтобы верхние окна рисовались поверх
            for (w in windows.sortedBy { it.layer }) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        } else {
            service.rootInActiveWindow?.let { roots.add(it) }
        }

        for (root in roots) {
            if (root.packageName?.toString() == ownPackage) continue
            walk(service, root, out, screen, 0)
        }
        return dedupe(out)
    }

    private fun walk(service: AccessibilityService, node: AccessibilityNodeInfo?, out: MutableList<TextBlock>, screen: Rect, depth: Int) {
        if (node == null || depth > 80) return
        if (!node.isVisibleToUser) return

        // contentDescription (подписи иконок для незрячих) не берём — на экране этого текста нет
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }

        if (text != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 4 && r.height() > 4 && r.intersect(screen)) {
                out.add(measure(service, node, text, r) ?: TextBlock(text.trim(), r))
            }
        }
        for (i in 0 until node.childCount) {
            walk(service, node.getChild(i), out, screen, depth + 1)
        }
    }

    /**
     * Просим у приложения координаты каждой буквы — так узнаём точное место текста
     * и реальную высоту строки (то есть размер шрифта).
     */
    private fun measure(service: AccessibilityService, node: AccessibilityNodeInfo, text: String, nodeBounds: Rect): TextBlock? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            if (!node.availableExtraData.contains(key)) return null
            val n = minOf(text.length, MAX_CHARS_MEASURED)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, n)
            }
            if (!node.refreshWithExtraData(key, args)) return null
            @Suppress("DEPRECATION")
            val arr = node.extras.getParcelableArray(key) ?: return null
            val all = arr.mapNotNull { it as? RectF }.filter { it.width() > 0.5f && it.height() > 0.5f }
            if (all.isEmpty()) return null

            // буквы должны лежать внутри элемента (с запасом)
            val slackX = nodeBounds.width() * 0.2f
            val slackY = nodeBounds.height() * 0.2f
            val area = RectF(
                nodeBounds.left - slackX, nodeBounds.top - slackY,
                nodeBounds.right + slackX, nodeBounds.bottom + slackY
            )
            val rects = all.filter { area.contains(it) }
            if (rects.size * 2 < all.size) return null

            val heights = rects.map { it.height() }.sorted()
            val widths = rects.map { it.width() }.sorted()
            val lineH = heights[heights.size / 2]
            val charW = widths[widths.size / 2]
            val density = service.resources.displayMetrics.density
            // отсекаем явно неверные данные (например, «буква» размером с весь блок)
            if (lineH > nodeBounds.height() * 1.25f) return null
            if (lineH > 160 * density) return null
            if (rects.size >= 3 && lineH > charW * 4.5f) return null

            // число строк — по разным «верхам» букв
            val tops = ArrayList<Float>()
            for (rc in rects) {
                if (tops.none { abs(it - rc.top) < lineH * 0.5f }) tops.add(rc.top)
            }

            val u = RectF(rects[0])
            for (rc in rects) u.union(rc)
            val tight = Rect(u.left.toInt(), u.top.toInt(), u.right.toInt() + 1, u.bottom.toInt() + 1)
            if (text.length > n) {
                // длинный текст: дальше 400 букв не мерили — тянем до низа элемента
                tight.bottom = maxOf(tight.bottom, nodeBounds.bottom)
                tight.left = minOf(tight.left, nodeBounds.left)
                tight.right = maxOf(tight.right, nodeBounds.right)
            }
            if (!tight.intersect(nodeBounds)) return null
            if (tight.width() < 2 || tight.height() < 2) return null
            if (tops.size * lineH > tight.height() * 1.6f + lineH) return null

            TextBlock(text.trim(), tight, lineH, tops.size)
        } catch (e: Exception) {
            null
        }
    }

    /** Убираем дубли и вложенные блоки, текст которых уже есть в родительском. */
    private fun dedupe(blocks: List<TextBlock>): List<TextBlock> {
        val unique = LinkedHashMap<String, TextBlock>()
        for (b in blocks) unique.putIfAbsent(b.text + "|" + b.bounds.flattenToString(), b)
        val list = unique.values.toList()
        return list.filter { inner ->
            list.none { outer ->
                outer !== inner &&
                    outer.bounds.contains(inner.bounds) &&
                    outer.bounds != inner.bounds &&
                    outer.text.contains(inner.text)
            }
        }
    }
}
