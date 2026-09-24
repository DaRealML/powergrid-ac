package org.patryk3211.powergrid.utility;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Two small accumulation patterns {@code WorldNetworks}'s per-tick sync loop relies on, pulled out
 * so a plain JUnit test can drive them directly: {@code postTick()} itself needs a live
 * {@code ServerLevel} and a tracked {@code ServerPlayer} and cannot run in any headless test in this
 * repo, the same boundary {@code WireThermal} documents for wire temperature.
 */
public final class SyncBatching {
    private SyncBatching() { }

    /**
     * Builds a value lazily on the first {@link #getOrCreate} call, then keeps returning that same
     * instance until {@link #reset}.
     * <p>
     * A mutation that rebuilds the value on every call instead of reusing it silently discards
     * whatever the caller already wrote into the earlier instance -- that is the exact shape of bug
     * this pins: {@link #getOrCreate} always returns the SAME reference within one lazy lifetime.
     */
    public static final class Lazy<T> {
        private T value;

        public T getOrCreate(Supplier<T> factory) {
            if(value == null)
                value = factory.get();
            return value;
        }

        public T current() {
            return value;
        }

        public void reset() {
            value = null;
        }
    }

    /**
     * Keeps only the first occurrence of each key, in {@code items}' own iteration order.
     * <p>
     * A block with several terminals visits this once per terminal; only the first should survive so
     * Create's own block-update coalescing does not have to undo duplicate sends within one tick.
     */
    public static <T> List<T> firstOccurrencePerKey(Iterable<T> items, ToLongFunction<T> keyOf) {
        var seen = new LongOpenHashSet();
        var out = new ArrayList<T>();
        for(var item : items) {
            if(seen.add(keyOf.applyAsLong(item)))
                out.add(item);
        }
        return out;
    }
}
