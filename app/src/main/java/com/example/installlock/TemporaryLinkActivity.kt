package com.example.installlock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import java.text.DateFormat
import java.util.Date

/** Internal notification destination: an ID can open only a completed, persisted approval. */
class TemporaryLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val id = intent.getStringExtra("link_id").orEmpty()
        val link = TemporaryLinks.all(this).firstOrNull { it.id == id }
        val message = UiStyle.label(this, "")
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UiStyle.dp(this@TemporaryLinkActivity, 24)
            setPadding(pad, pad, pad, pad)
            addView(UiStyle.label(this@TemporaryLinkActivity, "Temporary link", 26f))
            if (link == null) addView(UiStyle.label(this@TemporaryLinkActivity, "This link has expired or was removed. Request it again to start a new whitelist delay."))
            else {
                addView(UiStyle.label(this@TemporaryLinkActivity, link.url))
                addView(UiStyle.label(this@TemporaryLinkActivity, if (link.ready)
                    "Your whitelist delay is over. Opening starts 1 day of access to this link."
                    else "Available until ${DateFormat.getDateTimeInstance().format(Date(link.expiresWall))}. Reopening does not extend access."))
                addView(UiStyle.label(this@TemporaryLinkActivity,
                    "Opens in Chrome. Other pages need their own approval. App locks, blocked words, extra site locks and curfew still apply."))
                addView(message)
                addView(UiStyle.button(this@TemporaryLinkActivity, if (link.ready) "Open in Chrome — start 1 day" else "Open in Chrome", primary = true) {
                    val problem = when {
                        !Policy.isOwner(this@TemporaryLinkActivity) -> "Device-owner setup is required."
                        !Store.chromeLock(this@TemporaryLinkActivity) -> "Turn on the Chrome whitelist before opening temporary links."
                        !BrowserKeywordService.isReady(this@TemporaryLinkActivity) -> "Connect Install Lock's browser accessibility service to open this link."
                        else -> null
                    }
                    if (problem != null) { message.text = problem; return@button }
                    Policy.sweep(this@TemporaryLinkActivity)
                    val chrome = Policy.lockStatuses(this@TemporaryLinkActivity).firstOrNull { it.packageName == Policy.CHROME }
                    if (chrome == null || chrome.needsAttention) {
                        message.text = chrome?.explanation() ?: "Install Chrome to view this link."
                        return@button
                    }
                    val block = ChromeNavigationFilter(ChromeNavigationConfig(true, emptyList(),
                        Store.extraLockedSites(this@TemporaryLinkActivity).map { it.domain }.toSet(),
                        Store.domainRules(this@TemporaryLinkActivity), temporaryUrls = listOf(link.url))).block(link.url)
                    if (block != null) {
                        message.text = "An extra site lock or ordered Block rule prevents access to this link."
                        return@button
                    }
                    val opened = TemporaryLinks.open(this@TemporaryLinkActivity, id)
                    if (opened == null) { render(); return@button }
                    Policy.syncChrome(this@TemporaryLinkActivity)
                    ScheduledTasks.reschedule(this@TemporaryLinkActivity)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TemporaryLinkRules.openUrl(opened.url))).setPackage(Policy.CHROME))
                        finish()
                    } catch (_: android.content.ActivityNotFoundException) { message.text = "Chrome could not open this link." }
                })
                addView(UiStyle.button(this@TemporaryLinkActivity, "Remove temporary link") {
                    TemporaryLinks.remove(this@TemporaryLinkActivity, id)
                    Policy.syncChrome(this@TemporaryLinkActivity)
                    ScheduledTasks.reschedule(this@TemporaryLinkActivity)
                    finish()
                })
            }
            addView(UiStyle.button(this@TemporaryLinkActivity, "Done") { finish() })
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
}
