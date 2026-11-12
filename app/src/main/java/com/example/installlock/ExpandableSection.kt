package com.example.installlock

import android.content.Context
import android.content.res.ColorStateList
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.R as MaterialR

/** A native, keyboard-accessible dropdown; collapsed controls stay out of focus navigation. */
internal class ExpandableSection(
    context: Context,
    title: String,
    summary: String,
    initiallyExpanded: Boolean,
    private val onExpanded: (Boolean) -> Unit,
    vararg children: View
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val chevron = ImageView(context).apply {
        setImageResource(R.drawable.ic_il_chevron)
        imageTintList = ColorStateList.valueOf(UiStyle.color(context, MaterialR.attr.colorOnSurfaceVariant))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val header = UiStyle.sectionHeader(context, title, summary, chevron)
    private val body = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
    }
    private var expanded = initiallyExpanded

    init {
        orientation = VERTICAL
        background = UiStyle.shape(context, UiStyle.color(context, MaterialR.attr.colorSurfaceContainer))
        clipToOutline = true
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        children.forEach { body.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }) }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        header.setOnClickListener { changeExpansion(!expanded) }
        update()
    }

    fun expand() = changeExpansion(true)

    private fun changeExpansion(value: Boolean) {
        if (value == expanded) return
        if (isLaidOut) TransitionManager.beginDelayedTransition(this, AutoTransition().apply { duration = 180 })
        expanded = value
        update()
        onExpanded(value)
    }

    private fun update() {
        header.stateDescription = if (expanded) "Expanded" else "Collapsed"
        chevron.rotation = if (expanded) 180f else 0f
        body.visibility = if (expanded) View.VISIBLE else View.GONE
    }
}
