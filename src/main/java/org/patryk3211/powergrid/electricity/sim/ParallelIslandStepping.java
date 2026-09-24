package org.patryk3211.powergrid.electricity.sim;

import org.patryk3211.powergrid.PowerGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Steps one sub-tick round ({@code WorldNetworks.preTick}'s {@code i}) of every island that
 * participates in it, optionally spreading the independent islands over a thread pool.
 * <p>
 * <h2>Why this is safe to parallelize at all</h2>
 * Every {@link ElectricalNetwork} island is, by construction, a disjoint object graph: nodes,
 * wires and hooks are never shared between two islands except through a
 * {@code TransmissionLinePort} pair, which is why {@link ElectricalNetwork#requiresLockstep()}
 * exists. Two islands that do not share a port touch no common mutable state, so running their
 * {@code singleTick()} calls on different threads within one round changes nothing about the
 * result <em>as long as every island finishes this round (including {@code postUpperSolve()},
 * where a transmission line hands off its state) before any island starts the next round</em>.
 * That is the barrier this class provides: {@link #stepRound} does not return until every island
 * it was given has completed {@code singleTick()}.
 * <p>
 * <h2>Why lockstep islands are excluded from the parallel batch</h2>
 * {@code TransmissionLinePort.postUpperSolve()} exchanges state between two ports with a plain
 * (non-atomic, non-volatile) {@code solved} flag and relies on <em>one of the two running after
 * the other within the same round</em> to complete the handshake — it was written for the
 * sequential {@code for(network : subnetworks)} loop, where iteration order gives that ordering
 * for free. Running a linked pair on two threads at once is a genuine data race (lost updates,
 * or the exchange happening on neither side this round) regardless of the round barrier. Rather
 * than rewrite that handshake, every island for which {@code requiresLockstep()} is true is
 * stepped sequentially, in the same relative order as the plain loop, exactly as before. This is
 * the same conservative choice {@code WorldNetworks.preTick} already makes when it pulls every
 * lockstep island up to the fastest rate in the world (docs/AC.md 3.7): correct always, and it
 * only costs anything in worlds that actually run transmission lines.
 * <p>
 * <h2>Fallback</h2>
 * Below {@link #minParallelIslands} eligible islands, or when parallelism is disabled, every
 * island is stepped sequentially on the calling thread, in the original list order — bit-for-bit
 * the loop this replaces. That is the default: nothing changes for a small world.
 */
public final class ParallelIslandStepping {
    private ParallelIslandStepping() { }

    /** Opt-in switch. Off by default: see docs/perf/threading.md for the measurement behind that. */
    public static volatile boolean ENABLED = Boolean.getBoolean("powergrid.solver.parallelIslands");

    /**
     * Eligible (non-lockstep) islands stepping in a round below this count always run
     * sequentially, whatever {@link #ENABLED} says: the measured break-even is between one and a
     * few dozen islands depending on how expensive each is, and a fixed floor below the cheapest
     * measured break-even never regresses a small world. See docs/perf/threading.md.
     */
    public static volatile int minParallelIslands = Integer.getInteger("powergrid.solver.minParallelIslands", 4);

    /**
     * Worker threads. Default leaves two cores free for the server's main thread and everything
     * else competing with it (chunk generation, other mods, network IO); never fewer than one.
     */
    public static volatile int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

    private static volatile ExecutorService pool;
    private static volatile int poolThreads;

    /**
     * Shuts down and drops the cached pool so the next call to {@link #stepRound} builds a fresh
     * one honouring the current {@link #threads}. Tests and benchmarks that vary {@code threads}
     * between measurements must call this between them; production code does not need to, since
     * {@link #pool()} already rebuilds on its own when a live config change moves {@code threads}.
     */
    public static synchronized void resetPoolForTests() {
        var p = pool;
        if(p != null)
            p.shutdown();
        pool = null;
        poolThreads = 0;
    }

    private static ExecutorService pool() {
        var wanted = Math.max(1, threads);
        var p = pool;
        if(p != null && poolThreads == wanted)
            return p;
        synchronized(ParallelIslandStepping.class) {
            p = pool;
            if(p == null || poolThreads != wanted) {
                if(p != null)
                    // threads changed under a live config reload: let the old pool finish
                    // whatever it is running (shutdown, not shutdownNow) and start a differently
                    // sized one for the next round.
                    p.shutdown();
                var counter = new AtomicInteger();
                ThreadFactory factory = r -> {
                    var t = new Thread(r, "powergrid-island-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                };
                p = Executors.newFixedThreadPool(wanted, factory);
                pool = p;
                poolThreads = wanted;
            }
        }
        return p;
    }

    /**
     * Steps every island in {@code subnetworks} that participates in sub-tick {@code i} of
     * {@code maxSubTicks}, exactly the crossing test {@code WorldNetworks.preTick} uses. Returns
     * only once every one of them has finished. Equivalent to
     * {@link #stepRound(List, int, int, LongSupplier, long[])} with no per-island timing.
     */
    public static void stepRound(List<ElectricalNetwork> subnetworks, int i, int maxSubTicks) {
        stepRound(subnetworks, i, maxSubTicks, System::nanoTime, null);
    }

    /**
     * Steps every island in {@code subnetworks} that participates in sub-tick {@code i} of
     * {@code maxSubTicks}, exactly the crossing test {@code WorldNetworks.preTick} uses. Returns
     * only once every one of them has finished.
     * <p>
     * When {@code stepNanos} is non-null, each stepped island's own {@code singleTick()} wall time
     * (on whichever thread actually ran it) is added into {@code stepNanos[k]}, where {@code k} is
     * that island's index in {@code subnetworks} — the same array {@link SubTickScheduler#step}
     * accumulates into for its own sequential loop, so the tick-budget governor's per-island cost
     * model sees the same numbers whether or not this round actually used the pool. Writes from a
     * pool thread to {@code stepNanos[k]} are visible to the caller once this method returns,
     * because every {@link Future#get()} below happens-before that return. Times nothing when
     * {@code stepNanos} is null, or for an island at 1 sub-tick, matching
     * {@link SubTickScheduler#step}'s own {@code governing && subTicks > 1} gate.
     *
     * @param clock nanosecond clock to time with; the caller's, so a test can supply a fake one
     */
    public static void stepRound(List<ElectricalNetwork> subnetworks, int i, int maxSubTicks, LongSupplier clock, long[] stepNanos) {
        List<ElectricalNetwork> sequential = null;
        List<Integer> sequentialIndex = null;
        List<ElectricalNetwork> parallel = null;
        List<Integer> parallelIndex = null;
        for(int k = 0; k < subnetworks.size(); ++k) {
            var network = subnetworks.get(k);
            var subTicks = network.getSubTicks();
            if((i + 1) * subTicks / maxSubTicks <= i * subTicks / maxSubTicks)
                continue;
            if(network.requiresLockstep()) {
                if(sequential == null) {
                    sequential = new ArrayList<>();
                    sequentialIndex = new ArrayList<>();
                }
                sequential.add(network);
                sequentialIndex.add(k);
            } else {
                if(parallel == null) {
                    parallel = new ArrayList<>();
                    parallelIndex = new ArrayList<>();
                }
                parallel.add(network);
                parallelIndex.add(k);
            }
        }

        // Lockstep islands first and always sequential, in original order: preserves the
        // TransmissionLinePort handshake exactly as the plain loop did.
        if(sequential != null) {
            for(int j = 0; j < sequential.size(); ++j)
                stepTimed(sequential.get(j), sequentialIndex.get(j), clock, stepNanos);
        }

        if(parallel == null)
            return;
        if(!ENABLED || parallel.size() < minParallelIslands) {
            for(int j = 0; j < parallel.size(); ++j)
                stepTimed(parallel.get(j), parallelIndex.get(j), clock, stepNanos);
            return;
        }

        var pool = pool();
        var futures = new ArrayList<Future<?>>(parallel.size());
        // The calling thread does the last island itself instead of sitting idle waiting on the
        // pool for it, which is one fewer thread hand-off to pay for.
        for(int k = 0; k < parallel.size() - 1; ++k) {
            var network = parallel.get(k);
            var index = parallelIndex.get(k);
            futures.add(pool.submit(() -> stepTimed(network, index, clock, stepNanos)));
        }
        Throwable failure = null;
        try {
            stepTimed(parallel.get(parallel.size() - 1), parallelIndex.get(parallel.size() - 1), clock, stepNanos);
        } catch(Throwable t) {
            failure = t;
        }
        for(var future : futures) {
            try {
                future.get();
            } catch(Throwable t) {
                PowerGrid.LOGGER.error("Island solve failed on the parallel stepping pool", t);
                if(failure == null)
                    failure = t.getCause() != null ? t.getCause() : t;
            }
        }
        if(failure != null) {
            if(failure instanceof RuntimeException re)
                throw re;
            throw new RuntimeException(failure);
        }
    }

    private static void stepTimed(ElectricalNetwork network, int index, LongSupplier clock, long[] stepNanos) {
        if(stepNanos != null && network.getSubTicks() > 1) {
            var t0 = clock.getAsLong();
            network.singleTick();
            stepNanos[index] += clock.getAsLong() - t0;
        } else {
            network.singleTick();
        }
    }
}
