package red.jackf.chesttracker.impl.cmsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single background lane for all CMSync network work.
 *
 * <p>Why a queue and not "just async":
 * <ul>
 *   <li>Minecraft's client (render) thread must never do HTTP or Gson encode — it causes freezes.</li>
 *   <li>Tick runs 20x/sec; without coalescing we'd pile up hundreds of uploads.</li>
 *   <li>This queue allows max 1 job running + 1 waiting. Extra ticks just mark dirty and return.</li>
 * </ul>
 *
 * <p>Usage from client thread:
 * <pre>
 *   if (!CMSyncQueue.tryClaim()) return; // busy — stay smooth, retry next interval
 *   CMSyncQueue.executor().execute(() -> {
 *       try { doHttp(); } finally {
 *           CMSyncQueue.release();
 *           client.execute(() -> applyToGame());
 *       }
 *   });
 * </pre>
 */
public final class CMSyncQueue {
    private static final AtomicInteger THREAD_N = new AtomicInteger();
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cmsync-net-" + THREAD_N.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    /** true while a job is running or queued. Extra ticks coalesce (drop) instead of stacking. */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicInteger DROPPED = new AtomicInteger();

    private CMSyncQueue() {
    }

    public static ExecutorService executor() {
        return NET;
    }

    /**
     * @return true if caller now owns the lane and must eventually call {@link #release()}.
     */
    public static boolean tryClaim() {
        boolean got = BUSY.compareAndSet(false, true);
        if (!got) DROPPED.incrementAndGet();
        return got;
    }

    public static void release() {
        BUSY.set(false);
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    public static int droppedCoalesced() {
        return DROPPED.get();
    }
}
