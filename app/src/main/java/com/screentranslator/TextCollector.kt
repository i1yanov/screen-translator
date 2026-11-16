package com.screentranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class TextBlock(val text: String, val bounds: Rect)

/** Собирает видимый текст и его координаты из всех окон на экране. */
object TextCollector {

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

        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: if (node.childCount == 0) node.contentDescription?.toString()?.takeIf { it.isNotBlank() } else null

        if (text != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 4 && r.height() > 4 && r.intersect(screen)) {
                out.add(TextBlock(text.trim(), r))
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, screen, depth + 1)
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
