package com.iskezis.myst.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Window
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.iskezis.myst.MystApp
import com.iskezis.myst.R

object MystTheme {

    /**
     * Resolve um atributo de tema (por exemplo `?attr/mystOnSurface`) para uma
     * cor concreta. Necessário para textos/ícones alterados em código, que não
     * podem usar `@color/...` fixo se o app tem tema claro e escuro.
     */
    fun color(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return Color.WHITE
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    fun isDark(context: Context): Boolean {
        val app = context.applicationContext as? MystApp
        if (app != null) return app.appearance.isDarkEffective(context)
        val mode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Aplica o overlay AMOLED no tema da Activity. Deve ser chamado antes de
     * `super.onCreate` e de `setContentView` para não haver um frame com o
     * fundo escuro "normal".
     */
    fun applyAmoledOverlay(activity: Activity) {
        val app = activity.application as? MystApp ?: return
        if (!app.appearance.isAmoledActive(activity)) return
        activity.theme.applyStyle(R.style.ThemeOverlay_MystMusic_Amoled, true)
    }

    /**
     * Ícones de status bar/navigation bar claros no tema escuro e escuros no
     * tema claro. Chamado no início e novamente depois de configurar
     * edge-to-edge, quando a Activity sobrescreve o comportamento.
     */
    fun applySystemBars(activity: Activity) {
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        val dark = isDark(activity)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    fun applySystemBars(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val context = window.context
        val dark = isDark(context)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}
