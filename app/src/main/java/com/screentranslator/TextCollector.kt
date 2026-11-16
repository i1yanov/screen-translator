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
            walk(root, out, screen, 0)
        }
        return dedupe(out)
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<TextBlock>, screen: Rect, depth: Int) {
        if (node == null || depth > 80) return
        if (!node.isVisibleToUser) return

        val realText = node.text?.toString()?.takeIf { it.isNotBlank() }
        val text = realText
            ?: if (node.childCount == 0) node.contentDescription?.toString()?.takeIf { it.isNotBlank() } else null

        if (text != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 4 && r.height() > 4 && r.intersect(screen)) {
                val measured = if (realText != null) measure(node, realText, r) else null
                out.add(
                    measured ?: TextBlock(text.trim(), r)
                )
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, screen, depth + 1)
        }
    }

    /**
     * Просим у приложения координаты каждой буквы — так узнаём точное место текста
     * и реальную высоту строки (то есть размер шрифта).
     */
    private fun measure(node: AccessibilityNodeInfo, text: String, nodeBounds: Rect): TextBlock? {
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
            val rects = arr.mapNotNull { it as? RectF }.filter { it.width() > 0.5f && it.height() > 0.5f }
            if (rects.isEmpty()) return null

            val heights = rects.map { it.height() }.sorted()
            val lineH = heights[heights.size / 2]

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
