package com.example.installlock

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import com.google.android.material.color.DynamicColors
import com.google.android.material.R as MaterialR

internal object LockWidgetStyle {
    fun views(context: Context, title: String, subtitle: String, description: String): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_focus_lock).apply {
            setTextViewText(R.id.lock_widget_title, title)
            setTextViewText(R.id.lock_widget_subtitle, subtitle)
            setContentDescription(R.id.focus_lock_widget, description)
            if (Build.VERSION.SDK_INT >= 31) {
                fun palette(mode: Int): Context {
                    val config = Configuration(context.resources.configuration)
                    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                    return DynamicColors.wrapContextIfAvailable(android.view.ContextThemeWrapper(
                        context.createConfigurationContext(config), R.style.Theme_Gouge))
                }
                val day = palette(Configuration.UI_MODE_NIGHT_NO)
                val night = palette(Configuration.UI_MODE_NIGHT_YES)
                fun tint(id: Int, method: String, attribute: Int) = setColorStateList(id, method,
                    ColorStateList.valueOf(UiStyle.color(day, attribute)), ColorStateList.valueOf(UiStyle.color(night, attribute)))
                tint(R.id.lock_widget_title, "setTextColor", MaterialR.attr.colorOnSurface)
                tint(R.id.lock_widget_subtitle, "setTextColor", MaterialR.attr.colorOnSurfaceVariant)
                tint(R.id.lock_widget_icon, "setImageTintList", MaterialR.attr.colorTertiary)
                tint(R.id.focus_lock_widget, "setBackgroundTintList", MaterialR.attr.colorSurfaceContainer)
                tint(R.id.lock_widget_icon, "setBackgroundTintList", MaterialR.attr.colorTertiaryContainer)
            }
        }
    }
}
