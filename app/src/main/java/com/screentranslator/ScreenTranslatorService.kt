package com.screentranslator

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ScreenTranslatorService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: TranslationEngine
    private lateinit var ocr: OcrHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var hotspot: View? = null
    private var overlay: OverlayView? = null
    private var overlayShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        engine = TranslationEngine()
        ocr = OcrHelper(this)
        scope.launch { ocr.tesseract.warmUp() }
        scope.launch {
            try {
                if (!engine.checkModelsDownloaded()) engine.ensureModels()
            } catch (_: Exception) {
            }
        }
        addHotspot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        removeHotspot()
        scope.cancel()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (::engine.isInitialized) engine.close()
        if (::ocr.isInitialized) ocr.close()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        removeHotspot()
        addHotspot()
    }

    // ---------- Зона-кнопка в углу ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addHotspot() {
        if (hotspot != null) return
        val density = resources.displayMetrics.density
        val sizePx = (Prefs.sizeDp(prefs) * density).toInt()

        val v = View(this)
        if (Prefs.showMark(prefs)) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 30, 91, 216))
                setStroke((1 * density).toInt(), Color.argb(90, 30, 91, 216))
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onPress()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease()
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = when (Prefs.corner(prefs)) {
            1 -> Gravity.TOP or Gravity.RIGHT
            2 -> Gravity.BOTTOM or Gravity.LEFT
            3 -> Gravity.BOTTOM or Gravity.RIGHT
            else -> Gravity.TOP or Gravity.LEFT
        }
        lp.x = 0
        lp.y = 0
        setCutoutMode(lp)
        try {
            wm.addView(v, lp)
            hotspot = v
        } catch (_: Exception) {
        }
    }

    private fun removeHotspot() {
        hotspot?.let { runCatching { wm.removeView(it) } }
        hotspot = null
    }

    private fun setCutoutMode(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // ---------- Перевод ----------

    private fun onPress() {
        job?.cancel()
        val screen = screenRect()
        val blocks = try {
            TextCollector.collect(this, packageName, screen)
        } catch (_: Exception) {
            emptyList()
        }
        val useOcr = Prefs.ocr(prefs) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val online = Prefs.online(prefs)

        if (Prefs.mode(prefs) == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lensPress(screen, blocks, online)
            return
        }

        job = scope.launch {
            overlay?.dim = true
            // скриншот делаем до показа слоя, чтобы перевод не попал на картинку
            var shot: Bitmap? = null
            if (useOcr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                shot = withTimeoutOrNull(600) { ocr.screenshot(this@ScreenTranslatorService) }
            }

            val ov = showOverlay(screen)
            ov.clear()
            if (!online && !engine.ready) {
                ov.message = "Офлайн-модели ещё не загружены.\nОткройте приложение и нажмите «Скачать модели» или включите онлайн-перевод."
            } else if (blocks.isEmpty() && shot == null) {
                ov.message = "Текст на экране не найден"
            }

            fun translateItems(items: List<OverlayView.Item>) {
                for (item in items) {
                    launch {
                        val t = engine.translate(item.block.text, online)
                        if (t != null) ov.setTranslation(item, t)
                    }
                }
            }

            // невидимые элементы (прозрачные, скрытые) отсеиваем по снимку экрана:
            // там, где на самом деле нет текста, картинка однотонная
            val visibleBlocks = shot?.let { bmp -> blocks.filter { hasVisibleContent(bmp, it.bounds) } } ?: blocks
            translateItems(ov.addBlocks(visibleBlocks))

            val bmp = shot ?: return@launch
            launch {
                try {
                    val candidates = try {
                        ocr.detect(bmp)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val taken = ov.occupied()
                    val fresh = candidates.filter { c -> taken.none { overlapRatio(c.bounds, it) > 0.3f } }

                    // английский, который ML Kit прочитал уверенно, — сразу
                    val (sure, unsure) = fresh.partition { it.confidentLatin }
                    translateItems(ov.addBlocks(sure.map { it.toBlock(it.latinText) }))

                    // остальное (иврит и сомнительное) — через Tesseract, по одному
                    var foundAny = sure.isNotEmpty()
                    for (c in unsure.sortedBy { it.bounds.top }) {
                        if (!isActive) break
                        val text = ocr.readWithTesseract(bmp, c)?.joinToString(" ")
                            ?: c.latinText.takeIf {
                                TranslationEngine.sourceLanguage(it) == TranslateLanguage.ENGLISH &&
                                    OcrHelper.looksLikeWords(it)
                            }
                            ?: continue
                        foundAny = true
                        translateItems(ov.addBlocks(listOf(c.toBlock(text))))
                    }
                    if (visibleBlocks.isEmpty() && !foundAny) {
                        ov.message = "Текст на экране не найден"
                    }
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /**
     * Режим «как Google Объектив»: весь текст берётся со снимка экрана,
     * перевод рисуется поверх каждого блока цветом фона и цветом текста оригинала.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun lensPress(screen: Rect, nodeBlocks: List<TextBlock>, online: Boolean) {
        job = scope.launch {
            val bmp = withTimeoutOrNull(800) { ocr.screenshot(this@ScreenTranslatorService) }
            val ov = showOverlay(screen)
            ov.clear()
            ov.dim = false
            if (bmp == null) {
                ov.message = "Не удалось сделать снимок экрана.\nОтпустите и нажмите ещё раз."
                return@launch
            }
            if (!online && !engine.ready) {
                ov.message = "Офлайн-модели ещё не загружены.\nОткройте приложение и нажмите «Скачать модели» или включите онлайн-перевод."
            }
            ov.busy = true
            val segments = TextMatcher.segments(nodeBlocks)
            try {
                val candidates = try {
                    ocr.detect(bmp)
                } catch (_: Exception) {
                    emptyList()
                }
                coroutineScope {
                    for (c in candidates.sortedBy { it.bounds.top }) {
                        launch {
                            val lines: List<String> = if (c.confidentLatin) {
                                c.latinLines
                            } else {
                                ocr.readWithTesseract(bmp, c)
                                    ?: c.latinLines.takeIf {
                                        val t = c.latinText
                                        TranslationEngine.sourceLanguage(t) == TranslateLanguage.ENGLISH &&
                                            OcrHelper.looksLikeWords(t)
                                    }
                                    ?: return@launch
                            }
                            val text = lines.joinToString(" ") { TextMatcher.correct(it, segments) }.trim()
                            if (!TranslationEngine.needsTranslation(text)) return@launch
                            val (bg, fg) = ColorSampler.sample(bmp, c.bounds)
                            val rtl = text.any { it in '\u0590'..'\u05FF' }
                            val block = TextBlock(
                                text, c.bounds, c.lineHeight.toFloat(), c.lineCount,
                                lens = true, bgColor = bg, fgColor = fg, rtl = rtl
                            )
                            val item = ov.addBlocks(listOf(block)).first()
                            val t = engine.translate(text, online)
                            if (t != null) ov.setTranslation(item, t)
                        }
                    }
                }
                if (ov.isEmpty() && ov.message == null) ov.message = "Текст на экране не найден"
            } finally {
                ov.busy = false
                bmp.recycle()
            }
        }
    }

    private fun onRelease() {
        job?.cancel()
        job = null
        hideOverlay()
    }

    private fun showOverlay(screen: Rect): OverlayView {
        val ov = overlay ?: OverlayView(this).also { overlay = it }
        if (!overlayShown) {
            val lp = WindowManager.LayoutParams(
                screen.width(), screen.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = 0
            lp.y = 0
            setCutoutMode(lp)
            try {
                wm.addView(ov, lp)
                overlayShown = true
            } catch (_: Exception) {
            }
        }
        return ov
    }

    private fun hideOverlay() {
        val ov = overlay ?: return
        if (overlayShown) {
            runCatching { wm.removeView(ov) }
            overlayShown = false
        }
        ov.clear()
    }

    private fun screenRect(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.maximumWindowMetrics.bounds)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    /** Есть ли в области что-то кроме однотонного фона. */
    private fun hasVisibleContent(bmp: Bitmap, r: Rect): Boolean {
        val box = Rect(r)
        if (!box.intersect(0, 0, bmp.width, bmp.height)) return false
        if (box.width() < 3 || box.height() < 3) return false
        val step = maxOf(1, minOf(box.width(), box.height()) / 12)
        val lum = ArrayList<Int>()
        var y = box.top
        while (y < box.bottom) {
            var x = box.left
            while (x < box.right) {
                val c = bmp.getPixel(x, y)
                lum.add((Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100)
                x += step
            }
            y += step
        }
        if (lum.size < 4) return true
        val sorted = lum.sorted()
        val median = sorted[sorted.size / 2]
        val differing = lum.count { kotlin.math.abs(it - median) > 40 }
        return differing >= 3 && differing * 100 >= lum.size * 2
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val area = (a.width() * a.height()).coerceAtLeast(1)
        return i.width() * i.height() / area.toFloat()
    }
}
