package com.example.installlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JudgeActivity : AppCompatActivity() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        internal fun openIntent(c: Context, address: String = "") = Intent(c, JudgeActivity::class.java)
            .putExtra("website", address.take(4096))
    }
    private var job: Future<*>? = null
    private lateinit var address: EditText
    private lateinit var result: TextView
    private lateinit var grants: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        address = EditText(this).apply {
            hint = "https://www.edcad.ae/en"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(intent.getStringExtra("website").orEmpty())
        }
        result = UiStyle.label(this, "")
        grants = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inspect = UiStyle.button(this, "Judge this website", primary = true) {}
        val open = UiStyle.button(this, "Open approved page in Chrome") {}
        open.visibility = View.GONE
        inspect.setOnClickListener {
            val input = address.text.toString()
            inspect.isEnabled = false
            address.isEnabled = false
            open.visibility = View.GONE
            result.text = "Reading the public page and checking its destination…"
            job = executor.submit {
                val judgement = try {
                    val policy = JudgeStore.snapshot(applicationContext)
                    WebsiteJudge(leisureCatalog = policy.leisureCatalog, preferences = policy).judge(input)
                }
                    catch (_: Exception) { Judgement(JudgeDecision.UNVERIFIED, "Judge could not verify this page. The normal delay still applies.") }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    inspect.isEnabled = true
                    address.isEnabled = true
                    result.text = judgement.reason
                    if (judgement.decision == JudgeDecision.IMMEDIATE) {
                        if (!Store.chromeLock(this)) {
                            result.text = "Turn on the Chrome whitelist before using Judge."
                        } else if (JudgeStore.grant(this, judgement)) {
                            Policy.syncChrome(this)
                            result.text = judgement.reason + "\nImmediate access for ${judgement.hosts.joinToString()}. Other protections still apply."
                            open.visibility = View.VISIBLE
                            open.setOnClickListener {
                                if (judgement.hosts.all { it in JudgeStore.active(this) }) {
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(judgement.address)).setPackage(Policy.CHROME)) }
                                        .onFailure { result.text = "Chrome could not open. Check that it is installed and unlocked." }
                                } else result.text = "This Judge approval is inactive. Check accessibility and your other locks."
                            }
                            renderGrants()
                        } else {
                            result.text = "Immediate access was not granted. " + (JudgeStore.rejectionReason(this, judgement)
                                ?: "The approval could not be saved. Please try again.")
                        }
                    }
                }
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = UiStyle.dp(this@JudgeActivity, 20)
            setPadding(padding, padding, padding, padding)
            addView(UiStyle.label(this@JudgeActivity, "Judge", 26f))
            addView(UiStyle.label(this@JudgeActivity, "Known leisure sites always keep the normal whitelist delay, including educational pages on social or entertainment platforms."))
            addView(UiStyle.label(this@JudgeActivity, "Judge uses your current whitelist, blocked words, exceptions, domain rules and extra locks automatically. Completed whole-site approvals can teach it exact hosts; broad suffix rules and path-only approvals cannot. Readable useful content is still required. Unknown or leisure sites keep their delay."))
            val policy = JudgeStore.snapshot(this@JudgeActivity)
            addView(UiStyle.label(this@JudgeActivity, "Local tuning: ${policy.learnedHosts.size} eligible hosts from completed whole-site approvals, ${policy.blockedWords.size} blocked words, ${policy.extraLocks.size} extra locked sites. Rule changes deactivate old Judge grants until an automatic recheck succeeds; you can also check manually."))
            addView(UiStyle.label(this@JudgeActivity, "Bundled reviewed services: ${JudgeRules.reviewedServiceNames.joinToString("; ")}. Each host must pass its own content check; other subdomains and links are not approved."))
            addView(address); addView(inspect); addView(result); addView(open)
            addView(UiStyle.button(this@JudgeActivity, "Use normal delayed whitelist") {
                startActivity(BlockedSiteActivity.whitelistIntent(this@JudgeActivity, address.text.toString()))
            })
            addView(UiStyle.label(this@JudgeActivity, "Judge approvals", 22f)); addView(grants)
            addView(UiStyle.button(this@JudgeActivity, "Back") { finish() })
        }
        setContentView(ScrollView(this).apply { addView(column) })
        renderGrants()
    }

    private fun renderGrants() {
        grants.removeAllViews()
        val hosts = JudgeStore.saved(this).sorted()
        if (hosts.isEmpty()) grants.addView(UiStyle.label(this, "No immediate approvals."))
        val active = JudgeStore.active(this)
        hosts.forEach { host ->
            if (host !in active) grants.addView(UiStyle.label(this, "$host — waiting for a fresh successful check and active browser monitoring"))
            grants.addView(UiStyle.button(this, "Remove $host") { JudgeStore.remove(this, host); renderGrants() })
        }
    }

    override fun onDestroy() { job?.cancel(true); super.onDestroy() }
}
