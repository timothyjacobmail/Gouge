package com.example.installlock

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run only on an isolated emulator provisioned as device owner. Never changes saved rules. */
class EnforcementRecoveryDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun stoppedServiceRestartsFromReceiverWithoutOpeningAnActivity() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.MODEL.contains("sdk") && Policy.isOwner(c))
        val service = Intent(c, LockService::class.java)
        val manager = c.getSystemService(ActivityManager::class.java)
        fun running() = manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LockService::class.java.name && it.foreground }
        c.stopService(service)
        val stopBy = SystemClock.elapsedRealtime() + 5000
        while (running() && SystemClock.elapsedRealtime() < stopBy) SystemClock.sleep(50)
        assertFalse("Service must actually stop before recovery is exercised", running())
        c.sendBroadcast(Intent(c, EnforcementRecoveryReceiver::class.java).setAction(EnforcementRecovery.ACTION))
        val restartBy = SystemClock.elapsedRealtime() + 10_000
        while (!running() && SystemClock.elapsedRealtime() < restartBy) SystemClock.sleep(50)
        assertTrue("Receiver must restore foreground enforcement with no activity launch", running())
        val job = c.getSystemService(JobScheduler::class.java).getPendingJob(EnforcementRecovery.JOB_ID)
        assertNotNull("Recovery must also exist outside the app process", job)
        assertTrue(job!!.isPersisted)
    }
}
