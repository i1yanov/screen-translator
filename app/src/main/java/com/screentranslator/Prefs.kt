package com.screentranslator

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "settings"

    const val KEY_CORNER = "corner"          // 0 верх-лево, 1 верх-право, 2 низ-лево, 3 низ-право
    const val KEY_SIZE_DP = "size_dp"
    const val KEY_SHOW_MARK = "show_mark"
    const val KEY_OCR = "ocr"
    const val KEY_ONLINE = "online"
    const val KEY_MODE = "mode"              // 0 — по снимку экрана (как Google Объектив), 1 — по элементам

    const val DEFAULT_CORNER = 0
    const val DEFAULT_SIZE_DP = 56
    const val DEFAULT_SHOW_MARK = true
    const val DEFAULT_OCR = true
    const val DEFAULT_ONLINE = true
    const val DEFAULT_MODE = 0

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun corner(p: SharedPreferences) = p.getInt(KEY_CORNER, DEFAULT_CORNER)
    fun sizeDp(p: SharedPreferences) = p.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
    fun showMark(p: SharedPreferences) = p.getBoolean(KEY_SHOW_MARK, DEFAULT_SHOW_MARK)
    fun ocr(p: SharedPreferences) = p.getBoolean(KEY_OCR, DEFAULT_OCR)
    fun online(p: SharedPreferences) = p.getBoolean(KEY_ONLINE, DEFAULT_ONLINE)
    fun mode(p: SharedPreferences) = p.getInt(KEY_MODE, DEFAULT_MODE)
}
