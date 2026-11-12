package com.example.installlock

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Enables the configured VPN only; never disables it or edits protection settings. */
class VpnStartupTimingTest {
    @Test fun measureColdAndAlreadyRunningStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        assertTrue(Policy.isOwner(c))
        val start = SystemClock.elapsedRealtimeNanos()
        var returned = start
        instrumentation.runOnMainSync {
            val result = Policy.enableGoogleFilter(c)
            returned = SystemClock.elapsedRealtimeNanos()
            assertFalse(result.startsWith("Error:"))
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!DnsFilterService.isReady(c) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue("VPN reader did not become ready", DnsFilterService.isReady(c))
        val ready = SystemClock.elapsedRealtimeNanos()
        val serviceField = DnsFilterService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        val readerField = DnsFilterService::class.java.getDeclaredField("reader").apply { isAccessible = true }
        val service = serviceField.get(null)
        val reader = readerField.get(service)
        val warm = SystemClock.elapsedRealtimeNanos()
        var warmEntered = warm
        var warmReturned = warm
        instrumentation.runOnMainSync {
            warmEntered = SystemClock.elapsedRealtimeNanos()
            assertFalse(Policy.enableGoogleFilter(c).startsWith("Error:"))
            warmReturned = SystemClock.elapsedRealtimeNanos()
        }
        assertSame("Repeated enable replaced the live service", service, serviceField.get(null))
        assertSame("Repeated enable replaced the live DNS reader", reader, readerField.get(service))
        Log.i("VpnStartupTiming", "enableCallMs=${(returned-start)/1_000_000.0} " +
            "readerReadyMs=${(ready-start)/1_000_000.0} alreadyRunningCallMs=${(warmReturned-warmEntered)/1_000_000.0} " +
            "alreadyRunningMainQueueMs=${(warmEntered-warm)/1_000_000.0}")
    }
}
