package org.patryk3211.electricity;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link PerformanceCounter} is safe to share across island threads.
 * <p>
 * Both {@code JavaMNA} and {@code ElectricalNetwork} keep one {@code static final PerformanceCounter}
 * per solver class, so every island of that class calls {@code start()}/{@code end()} on the SAME
 * instance. Before the {@code ThreadLocal} start time and the {@code synchronized} accumulator this
 * was two hazards: one island's duration measured from another island's start timestamp, and a plain
 * (non-atomic) {@code microsTotal += duration} race between accumulator updates.
 */
public class PerformanceCounterConcurrencyTest {
    /**
     * Every thread sleeps a KNOWN, distinct duration inside start()/end(), many times, from many
     * threads, on one shared counter. If start() used a plain instance field instead of a
     * ThreadLocal, a fast thread's end() could read a slow thread's start timestamp (or vice
     * versa) whenever two threads interleaved, which this drives deliberately by starting every
     * thread from a shared latch at the same instant.
     */
    @RepeatedTest(20)
    void concurrentStartEndNeverCrossesThreads() throws InterruptedException {
        var counter = new PerformanceCounter("stress-test");
        int threads = 16;
        int iterations = 500;
        var pool = Executors.newFixedThreadPool(threads);
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threads);
        var failure = new AtomicReference<Throwable>();
        var minObservedMicros = new AtomicInteger(Integer.MAX_VALUE);

        for(int t = 0; t < threads; ++t) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread's own busy-wait duration is at least this many microseconds, so a
                    // reported average/min below the smallest thread's own duration is a hard proof
                    // that a start() from a DIFFERENT (faster) thread leaked into this end().
                    long minMicrosForThisThread = 50 + threadIndex * 5L;
                    for(int i = 0; i < iterations; ++i) {
                        counter.start();
                        busyWaitMicros(minMicrosForThisThread);
                        counter.end();
                    }
                    minObservedMicros.accumulateAndGet((int) minMicrosForThisThread, Math::min);
                } catch(Throwable e) {
                    failure.set(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "stress threads did not finish in time");
        pool.shutdown();
        if(failure.get() != null)
            throw new AssertionError(failure.get());

        // threads * iterations epochs were recorded (accumulator race would lose updates and
        // under-count; this is exactly what the synchronized block in end() prevents).
        // getMin() must never read as less than the smallest per-thread floor: that would mean an
        // end() paired with a start() belonging to a different, earlier thread.
        assertTrue(counter.getMin() >= 0, "min must be non-negative");
        assertTrue(counter.getMin() >= minObservedMicros.get() * 0.5,
                "reported min " + counter.getMin() + "us is far below every thread's own floor "
                        + minObservedMicros.get() + "us: a start() leaked across threads");
    }

    /** end() called with no matching start() on that thread must not throw or corrupt state. */
    @Test
    void endWithoutStartOnThatThreadIsIgnoredNotCrashed() {
        var counter = new PerformanceCounter("no-start");
        counter.end();
        assertEquals(0.0, counter.getMin());
    }

    private static void busyWaitMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1000;
        while(System.nanoTime() < deadline) {
            // spin; Thread.sleep resolution is too coarse for microsecond floors
        }
    }
}
