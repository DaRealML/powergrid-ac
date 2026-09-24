package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.utility.SyncBatching;

import java.util.List;

/**
 * {@link SyncBatching} is the pure part of WorldNetworks's per-tick sync loop: the lazy packet
 * build (WN-M1) and the full-sync dedup (WN-M2) a review pass found unguarded by any test, since
 * postTick() and fullSyncTargets() both need a live ServerLevel/ServerPlayer this repo's headless
 * suite cannot supply.
 */
public class SyncBatchingTest {
    // --- Lazy<T>: WN-M1 (packet rebuilt on every write instead of once) ---

    @Test
    void getOrCreateBuildsOnlyOnceAndKeepsReturningThatInstance() {
        var lazy = new SyncBatching.Lazy<Object>();
        var calls = new int[]{ 0 };
        var first = lazy.getOrCreate(() -> { ++calls[0]; return new Object(); });
        var second = lazy.getOrCreate(() -> { ++calls[0]; return new Object(); });
        var third = lazy.getOrCreate(() -> { ++calls[0]; return new Object(); });
        Assertions.assertSame(first, second, "second call rebuilt instead of reusing");
        Assertions.assertSame(first, third, "third call rebuilt instead of reusing");
        Assertions.assertEquals(1, calls[0], "factory ran more than once");
    }

    @Test
    void currentIsNullUntilTheFirstGetOrCreate() {
        var lazy = new SyncBatching.Lazy<String>();
        Assertions.assertNull(lazy.current());
        lazy.getOrCreate(() -> "x");
        Assertions.assertEquals("x", lazy.current());
    }

    @Test
    void resetLetsTheNextGetOrCreateBuildAFreshInstance() {
        var lazy = new SyncBatching.Lazy<Object>();
        var first = lazy.getOrCreate(Object::new);
        lazy.reset();
        Assertions.assertNull(lazy.current());
        var second = lazy.getOrCreate(Object::new);
        Assertions.assertNotSame(first, second);
    }

    // --- firstOccurrencePerKey: WN-M2 (dedup dropped, every terminal resent) ---

    @Test
    void firstOccurrencePerKeyKeepsOnlyTheFirstItemForEachKey() {
        // A block with three terminals contributes the same position three times.
        var items = List.of("a@1", "b@2", "a@1", "c@3", "a@1", "b@2");
        var kept = SyncBatching.firstOccurrencePerKey(items, s -> s.charAt(s.length() - 1));
        Assertions.assertEquals(List.of("a@1", "b@2", "c@3"), kept);
    }

    @Test
    void firstOccurrencePerKeyPassesThroughAlreadyUniqueItems() {
        var items = List.of(10L, 20L, 30L);
        var kept = SyncBatching.firstOccurrencePerKey(items, x -> x);
        Assertions.assertEquals(items, kept);
    }

    @Test
    void firstOccurrencePerKeyOnEmptyInputIsEmpty() {
        List<Long> empty = List.of();
        Assertions.assertTrue(SyncBatching.firstOccurrencePerKey(empty, x -> x).isEmpty());
    }
}
