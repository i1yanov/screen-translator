package com.screentranslator

/**
 * Уточняет распознанный текст: если строка с картинки похожа на настоящую строку
 * из приложения (через службу специальных возможностей), берём точный текст приложения.
 */
object TextMatcher {

    class Segment(val text: String, val grams: Map<String, Int>, val size: Int)

    fun segments(blocks: List<TextBlock>): List<Segment> {
        val seen = HashSet<String>()
        val out = ArrayList<Segment>()
        for (b in blocks) {
            for (part in b.text.split('\n')) {
                val t = part.trim()
                if (t.length < 2 || !seen.add(t)) continue
                val g = bigrams(t)
                if (g.isNotEmpty()) out.add(Segment(t, g, g.values.sum()))
            }
        }
        return out
    }

    fun correct(ocrLine: String, segments: List<Segment>): String {
        if (segments.isEmpty()) return ocrLine
        val g = bigrams(ocrLine)
        val n = g.values.sum()
        if (n < 2) return ocrLine
        var best: Segment? = null
        var bestScore = 0f
        for (s in segments) {
            if (s.size > n * 3 || n > s.size * 3) continue
            var common = 0
            for ((k, v) in g) {
                val o = s.grams[k] ?: continue
                common += minOf(v, o)
            }
            val score = 2f * common / (n + s.size)
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        return if (best != null && bestScore >= 0.6f) best.text else ocrLine
    }

    private fun bigrams(text: String): Map<String, Int> {
        val norm = StringBuilder()
        for (c in text.lowercase()) if (Character.isLetterOrDigit(c)) norm.append(c)
        val map = HashMap<String, Int>()
        for (i in 0 until norm.length - 1) {
            val k = norm.substring(i, i + 2)
            map[k] = (map[k] ?: 0) + 1
        }
        return map
    }
}
