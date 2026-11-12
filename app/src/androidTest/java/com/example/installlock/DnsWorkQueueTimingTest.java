package com.example.installlock;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Synthetic queue saturation only: no browsing, domain history or policy changes. */
@RunWith(AndroidJUnit4.class)
public class DnsWorkQueueTimingTest {
    @Test public void compareBlockedReplySchedulingUnderUpstreamLoad() throws Exception {
        ThreadPoolExecutor old = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128));
        DnsWorkQueue fast = new DnsWorkQueue();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(16);
        Runnable waitForNetwork = () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try {
            for (int i = 0; i < 8; i++) { old.execute(waitForNetwork); fast.execute(false, waitForNetwork); }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CountDownLatch fastReply = new CountDownLatch(1);
            long before = System.nanoTime();
            fast.execute(true, fastReply::countDown);
            long fastNs = System.nanoTime() - before;
            assertEquals(0, fastReply.getCount()); // Upstream is still deliberately blocked.
            CountDownLatch oldReply = new CountDownLatch(1);
            timer.schedule(release::countDown, 250, TimeUnit.MILLISECONDS);
            before = System.nanoTime();
            old.execute(oldReply::countDown);
            assertTrue(oldReply.await(5, TimeUnit.SECONDS));
            long oldNs = System.nanoTime() - before;
            Log.i("DnsQueueTiming", "Synthetic 8 busy lookups: old=" + oldNs / 1_000_000.0
                    + "ms immediate=" + fastNs / 1_000_000.0 + "ms");
        } finally { release.countDown(); old.shutdownNow(); fast.shutdownNow(); timer.shutdownNow(); }
    }
}
