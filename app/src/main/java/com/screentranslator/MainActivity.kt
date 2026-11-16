package com.screentranslator

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var engine: TranslationEngine

    private lateinit var serviceStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var downloadBtn: Button

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = TranslationEngine()
        val prefs = Prefs.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }

        root.addView(bigTitle("Переводчик экрана"))
        root.addView(text("Держите палец в углу экрана — поверх появится перевод всего текста на русский (с английского и иврита). Отпустите — перевод исчезнет."))

        // --- 1. Служба
        root.addView(header("1. Включить службу"))
        serviceStatus = text("")
        root.addView(serviceStatus)
        root.addView(button("Открыть «Специальные возможности»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(small(
            "В списке найдите «Переводчик экрана» (на HyperOS: Расширенные настройки → Специальные возможности → Скачанные приложения) и включите.\n\n" +
                "Если переключатель серый («Настройка заблокирована»): откройте «О приложении» ниже → ⋮ в правом верхнем углу → «Разрешить ограниченные настройки», затем включите службу снова."
        ))
        root.addView(button("О приложении (разрешения, автозапуск)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(small("Чтобы HyperOS не выключала службу: в «О приложении» включите «Автозапуск», а в «Контроль активности» выберите «Нет ограничений»."))

        // --- 2. Модели
        root.addView(header("2. Офлайн-перевод (по желанию)"))
        root.addView(small("Нужен, только если хотите переводить без интернета. При включённом онлайн-переводе используется как запасной вариант."))
        modelStatus = text("Проверяю…")
        root.addView(modelStatus)
        downloadBtn = button("Скачать модели (≈60 МБ, один раз)") { downloadModels() }
        root.addView(downloadBtn)

        // --- 3. Настройки
        root.addView(header("3. Настройки"))
        root.addView(text("Режим:"))
        val modes = listOf(
            "По снимку экрана — как Google Объектив (рекомендуется)",
            "По тексту элементов приложения (быстрее)"
        )
        val modeGroup = RadioGroup(this)
        modes.forEachIndexed { i, name ->
            modeGroup.addView(RadioButton(this).apply { id = 2000 + i; text = name; textSize = 16f })
        }
        modeGroup.check(2000 + Prefs.mode(prefs))
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt(Prefs.KEY_MODE, checkedId - 2000).apply()
        }
        root.addView(modeGroup)
        root.addView(small("«Как Google Объектив»: переводит всё, что видно на экране, включая картинки, и рисует перевод поверх каждой строки в цветах оригинала. Перевод появляется за 1–2 секунды."))

        root.addView(text("Угол экрана для нажатия:"))
        val corners = listOf("Левый верхний (у часов)", "Правый верхний", "Левый нижний", "Правый нижний")
        val group = RadioGroup(this)
        corners.forEachIndexed { i, name ->
            group.addView(RadioButton(this).apply { id = 1000 + i; text = name; textSize = 16f })
        }
        group.check(1000 + Prefs.corner(prefs))
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt(Prefs.KEY_CORNER, checkedId - 1000).apply()
        }
        root.addView(group)

        val sizeLabel = text("")
        fun updateSizeLabel(v: Int) { sizeLabel.text = "Размер зоны нажатия: $v dp" }
        updateSizeLabel(Prefs.sizeDp(prefs))
        root.addView(sizeLabel)
        root.addView(SeekBar(this).apply {
            max = 100 - 32
            progress = Prefs.sizeDp(prefs) - 32
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateSizeLabel(p + 32)
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(Prefs.KEY_SIZE_DP, progress + 32).apply()
                }
            })
        })

        root.addView(CheckBox(this).apply {
            text = "Показывать полупрозрачную метку зоны"
            textSize = 16f
            isChecked = Prefs.showMark(prefs)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.KEY_SHOW_MARK, c).apply() }
        })
        root.addView(CheckBox(this).apply {
            text = "Режим элементов: также распознавать картинки"
            textSize = 16f
            isChecked = Prefs.ocr(prefs)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.KEY_OCR, c).apply() }
        })
        root.addView(CheckBox(this).apply {
            text = "Онлайн-перевод через Google (точнее, нужен интернет)"
            textSize = 16f
            isChecked = Prefs.online(prefs)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.KEY_ONLINE, c).apply() }
        })

        root.addView(header("Как это работает"))
        root.addView(small(
            "• Обычный текст берётся напрямую из приложений через службу специальных возможностей — быстро и точно.\n" +
                "• Текст на картинках распознаётся по снимку экрана: английский — Google ML Kit, иврит — Tesseract. Картинки появляются чуть позже (до пары секунд).\n" +
                "• Онлайн-перевод отправляет найденный текст в Google Translate. Без интернета используется перевод на телефоне.\n" +
                "• Зона в углу перехватывает касания: шторку уведомлений тяните из другого места."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        serviceStatus.text = if (on) "✅ Служба включена" else "❌ Служба выключена"
        serviceStatus.setTextColor(if (on) 0xFF1B7F2A.toInt() else 0xFFC62828.toInt())
        scope.launch {
            val ok = try { engine.checkModelsDownloaded() } catch (_: Exception) { false }
            setModelStatus(ok)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        super.onDestroy()
    }

    private fun setModelStatus(ok: Boolean) {
        modelStatus.text = if (ok) "✅ Модели английский→русский и иврит→русский загружены" else "❌ Модели не загружены"
        modelStatus.setTextColor(if (ok) 0xFF1B7F2A.toInt() else 0xFFC62828.toInt())
        downloadBtn.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun downloadModels() {
        downloadBtn.isEnabled = false
        modelStatus.text = "⏳ Скачиваю… это может занять минуту"
        modelStatus.setTextColor(0xFF555555.toInt())
        scope.launch {
            try {
                engine.ensureModels()
                setModelStatus(true)
            } catch (e: Exception) {
                modelStatus.text = "Ошибка загрузки: ${e.message ?: "проверьте интернет"}"
                modelStatus.setTextColor(0xFFC62828.toInt())
            } finally {
                downloadBtn.isEnabled = true
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ScreenTranslatorService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            val cn = ComponentName.unflattenFromString(s) ?: continue
            if (cn == me) return true
        }
        return false
    }

    // --- маленькие помощники для UI ---

    private fun bigTitle(s: String) = TextView(this).apply {
        text = s; textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, (8 * dp).toInt())
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun text(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(0xFF666666.toInt())
        setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
