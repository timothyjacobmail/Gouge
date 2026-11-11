package com.example.installlock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors

class PlayStoreDelayActivity : AppCompatActivity() {
    companion object {
        fun openIntent(c: Context) = Intent(c, PlayStoreDelayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var opening = false
    private val tick = object : Runnable {
        override fun run() {
            if (opening) return
            if (!PlayStoreDelayStore.enabled(this@PlayStoreDelayActivity)) { openStore(); return }
            val now = WatchdogStore.clock(this@PlayStoreDelayActivity)
            val remaining = PlayStoreDelayStore.start(this@PlayStoreDelayActivity, now).remaining(now)
            val seconds = (remaining + 999) / 1000
            status.text = "Play Store opens in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            if (remaining == 0L) openStore() else handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        status = UiStyle.label(this, "", 22f)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(UiStyle.label(this@PlayStoreDelayActivity, "Open Play Store", 26f))
            addView(status)
            addView(UiStyle.label(this@PlayStoreDelayActivity,
                "Each Play Store session starts with a ${PlayStoreDelayStore.minutes(this@PlayStoreDelayActivity)}-minute pause. Other app locks, curfew and focus restrictions still apply. Leaving Play Store ends the session."))
            addView(UiStyle.button(this@PlayStoreDelayActivity, "Cancel") {
                PlayStoreDelayStore.cancel(this@PlayStoreDelayActivity)
                finish()
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + 24.dp(), bars.top + 24.dp(), bars.right + 24.dp(), bars.bottom + 24.dp())
            insets
        }
        setContentView(content)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun openStore() {
        val reasons = Policy.lockStatuses(this).firstOrNull { it.packageName == PlayStoreDelayStore.PACKAGE }
        if (reasons?.needsAttention == true) {
            status.text = "The two-minute wait is complete.\n${reasons.explanation()}"
            handler.postDelayed(tick, 1000)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(PlayStoreDelayStore.PACKAGE)
        if (launch == null) { status.text = "Play Store is not installed or is currently restricted."; return }
        try {
            opening = true
            PlayStoreDelayStore.beginSession(this)
            startActivity(launch)
            finish()
        } catch (_: RuntimeException) {
            PlayStoreDelayStore.endSession()
            opening = false
            status.text = "Play Store could not open. Leave this screen and return to try again."
        }
    }
}
