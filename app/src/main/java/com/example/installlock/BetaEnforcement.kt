package com.example.installlock

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One coalescing current-state worker, not a queue of stale policy snapshots. */
internal object BetaEnforcement {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Gouge-beta-policy") }
    private val pending = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    fun execute(action: () -> Unit) { executor.execute { try { action() } catch (e: RuntimeException) { android.util.Log.e("GougeBeta", "Beta work failed; stable enforcement continues", e) } } }
    fun request(context: Context) {
        val c = context.applicationContext
        dirty.set(true)
        if (!pending.compareAndSet(false, true)) return
        execute {
            try { do { dirty.set(false); Policy.sweepCurrent(c) } while (dirty.get()) }
            finally { pending.set(false); if (dirty.get()) request(c) }
        }
    }
    fun recover(vararg steps: Pair<String, () -> Unit>) {
        steps.forEach { (name, action) -> try { action() } catch (e: RuntimeException) { android.util.Log.e("GougeBeta", "$name failed; continuing recovery", e) } }
    }
}
