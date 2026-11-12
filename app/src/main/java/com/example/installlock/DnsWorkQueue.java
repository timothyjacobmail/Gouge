package com.example.installlock;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local denials never wait for sockets, upstream timeouts or space in the forward queue. */
final class DnsWorkQueue {
    private final ThreadPoolExecutor upstream = new ThreadPoolExecutor(
            8, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));

    DnsWorkQueue() {
        upstream.allowCoreThreadTimeOut(true);
    }

    void execute(boolean blocked, Runnable task) {
        if (blocked) {
            task.run();
        } else {
            try {
                upstream.execute(task);
            } catch (RejectedExecutionException ignored) {
                // Bound upstream work; clients can retry. Local denials cannot be dropped here.
            }
        }
    }

    void clear() { upstream.getQueue().clear(); }

    void shutdownNow() { upstream.shutdownNow(); }
}
