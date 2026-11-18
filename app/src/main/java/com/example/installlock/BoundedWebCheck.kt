package com.example.installlock

import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Includes DNS, redirects and body reads in one wall-clock budget. Never queues behind an old check. */
internal object BoundedWebCheck {
    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(task, "public-website-check").apply { isDaemon = true }
    }

    fun <T> run(timeoutMillis: Long, check: () -> T): T? {
        val job = try { workers.submit(Callable { check() }) } catch (_: Exception) { return null }
        return try { job.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        catch (_: Exception) { null }
        finally { job.cancel(true) }
    }
}
