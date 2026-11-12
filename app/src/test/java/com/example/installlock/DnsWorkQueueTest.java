package com.example.installlock;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DnsWorkQueueTest {
    @Test(timeout = 5000) public void denialsBypassBusyWorkersAndFullForwardQueue() throws Exception {
        DnsWorkQueue work = new DnsWorkQueue();
        CountDownLatch started = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger forwarded = new AtomicInteger();
        try {
            for (int i = 0; i < 8; i++) work.execute(false, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 128; i++) work.execute(false, forwarded::incrementAndGet);
            AtomicInteger denied = new AtomicInteger();
            for (int i = 0; i < 1000; i++) work.execute(true, denied::incrementAndGet);
            assertEquals(1000, denied.get());
            assertEquals(0, forwarded.get());
            work.clear();
            work.execute(true, denied::incrementAndGet);
            assertEquals(1001, denied.get());
        } finally { work.shutdownNow(); release.countDown(); }
    }

    @Test(timeout = 5000) public void allowedLookupsStillRunOffTheReaderThread() throws Exception {
        DnsWorkQueue work = new DnsWorkQueue();
        Thread reader = Thread.currentThread();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            work.execute(false, () -> { worker.set(Thread.currentThread()); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNotSame(reader, worker.get());
        } finally { work.shutdownNow(); }
    }
}
