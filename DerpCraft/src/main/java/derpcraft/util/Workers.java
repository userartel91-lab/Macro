package derpcraft.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared background worker pool with priorities (lower value runs first). */
public final class Workers {
    private static final AtomicLong SEQ = new AtomicLong();
    private final ThreadPoolExecutor pool;

    private static final class Task implements Runnable, Comparable<Task> {
        final long prio, seq;
        final Runnable r;
        Task(long prio, Runnable r) { this.prio = prio; this.r = r; this.seq = SEQ.incrementAndGet(); }
        public void run() {
            try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
        }
        public int compareTo(Task o) { return prio != o.prio ? Long.compare(prio, o.prio) : Long.compare(seq, o.seq); }
    }

    public Workers(String name, int threads) {
        pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);
            return t;
        });
    }

    public static int defaultThreads() { return Math.max(2, Runtime.getRuntime().availableProcessors() - 2); }

    public void execute(long priority, Runnable r) { pool.execute(new Task(priority, r)); }

    public void shutdown() {
        pool.shutdownNow();
        try { pool.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
