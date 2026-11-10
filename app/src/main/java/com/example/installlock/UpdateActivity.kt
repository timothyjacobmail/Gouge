package com.example.installlock

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var install: Button
    private lateinit var confirm: Button
    private lateinit var progress: ProgressBar
    private val observer: (UpdateState) -> Unit = { state ->
        status.text = state.message
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        check.isEnabled = !state.busy
        install.visibility = if (!state.busy && state.update != null) View.VISIBLE else View.GONE
        confirm.visibility = if (state.confirmation != null) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val version = GitHubUpdates.installed(this)
        status = UiStyle.label(this, "")
        progress = ProgressBar(this)
        check = UiStyle.button(this, "Check for updates") { GitHubUpdates.check(this) }
        install = UiStyle.button(this, "Download and install", primary = true) {
            val update = GitHubUpdates.state.update ?: return@button
            MaterialAlertDialogBuilder(this).setTitle("Install version ${update.versionName}?")
                .setMessage("Download the update from GitHub and install it over this app. Your settings and device-owner setup are kept. The app may close briefly during installation.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install") { _, _ -> GitHubUpdates.install(this, update) }.show()
        }
        confirm = UiStyle.button(this, "Continue Android installation") {
            GitHubUpdates.state.confirmation?.let { intent ->
                runCatching { startActivity(intent) }.onFailure {
                    android.widget.Toast.makeText(this, "Could not open Android installation. Check for updates to retry.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiStyle.dp(this@UpdateActivity, 20), UiStyle.dp(this@UpdateActivity, 24), UiStyle.dp(this@UpdateActivity, 20), UiStyle.dp(this@UpdateActivity, 24))
            addView(UiStyle.label(this@UpdateActivity, "App updates", 26f))
            addView(UiStyle.label(this@UpdateActivity, "Installed: ${version.versionName} (${version.longVersionCode})"))
            addView(UiStyle.label(this@UpdateActivity, "Updates come from Install Lock’s GitHub releases, including debug releases. Your existing protections stay active."))
            addView(status); addView(progress); addView(check); addView(install); addView(confirm)
            addView(UiStyle.button(this@UpdateActivity, "Back") { finish() })
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onStart() {
        super.onStart()
        GitHubUpdates.restore(this)
        GitHubUpdates.observe(observer)
    }

    override fun onStop() { GitHubUpdates.remove(observer); super.onStop() }
}
