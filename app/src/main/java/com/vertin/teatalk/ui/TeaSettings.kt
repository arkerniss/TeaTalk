package com.vertin.teatalk.ui

import android.content.Context
import android.content.res.Configuration

/**
 * App-wide appearance preference.
 *
 * The user picks one of three modes on the home screen's settings sheet; every
 * Activity resolves it on create and feeds the resulting boolean into
 * [TeaTalkTheme]. Nothing observes the preference live — changing it happens in
 * one place (MainActivity, which re-runs its own composition) and every other
 * screen is created afterwards, so reading on create is enough.
 */
enum class TeaThemeMode(val key: String, val label: String, val hint: String) {
    SYSTEM("system", "跟随系统", "跟着手机的深色模式走"),
    LIGHT("light", "浅色", "一直是暖纸色"),
    DARK("dark", "深色", "一直是墨色"),
}

object TeaSettings {
    private const val PREFS = "teatalk.settings"
    private const val KEY_THEME = "theme_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun themeMode(context: Context): TeaThemeMode {
        val raw = prefs(context).getString(KEY_THEME, null) ?: return TeaThemeMode.SYSTEM
        return TeaThemeMode.entries.firstOrNull { it.key == raw } ?: TeaThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: TeaThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.key).apply()
    }

    /** True when the resolved appearance is dark. */
    fun isDark(context: Context): Boolean = when (themeMode(context)) {
        TeaThemeMode.DARK -> true
        TeaThemeMode.LIGHT -> false
        TeaThemeMode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Value for the chat page's `theme` query parameter, or null when the page
     * should keep using its own `prefers-color-scheme` media query.
     */
    fun webThemeParam(context: Context): String? = when (themeMode(context)) {
        TeaThemeMode.SYSTEM -> null
        TeaThemeMode.LIGHT -> "light"
        TeaThemeMode.DARK -> "dark"
    }
}
