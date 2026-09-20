package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.utility.SpreadOverTicks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class SpreadOverTicksTest {
    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    /** Runs {@code rounds} whole rounds and returns how many elements each tick handled. */
    private static int[] handledPerTick(int size, int interval, int rounds, Map<Integer, Integer> seen) {
        var schedule = new SpreadOverTicks<Integer>();
        var perTick = new int[interval * rounds];
        for(int t = 0; t < perTick.length; ++t) {
            var before = new int[]{ 0 };
            schedule.tick(interval, () -> range(size), element -> {
                seen.merge(element, 1, Integer::sum);
                ++before[0];
            });
            perTick[t] = before[0];
        }
        return perTick;
    }

    @Test
    void everyElementIsHandledExactlyOncePerRound() {
        for(var size : new int[]{ 0, 1, 7, 99, 100, 101, 250, 2000 }) {
            for(var interval : new int[]{ 1, 2, 7, 100 }) {
                var seen = new HashMap<Integer, Integer>();
                handledPerTick(size, interval, 3, seen);
                Assertions.assertEquals(size, seen.size(), "size " + size + " interval " + interval);
                for(var count : seen.values())
                    Assertions.assertEquals(3, count, "size " + size + " interval " + interval + " (three rounds)");
            }
        }
    }

    @Test
    void noTickHandlesMoreThanItsShare() {
        for(var size : new int[]{ 1, 7, 99, 100, 101, 250, 2000 }) {
            for(var interval : new int[]{ 1, 2, 7, 100 }) {
                var perTick = handledPerTick(size, interval, 2, new HashMap<>());
                var most = IntStream.of(perTick).max().orElse(0);
                Assertions.assertTrue(most <= (size + interval - 1) / interval,
                        "size " + size + " interval " + interval + ": one tick handled " + most);
            }
        }
    }

    @Test
    void theBurstItReplacesWasAllOnOneTick() {
        // 2000 elements every 100 ticks: one tick of 2000 became a hundred ticks of at most 20.
        var perTick = handledPerTick(2000, 100, 1, new HashMap<>());
        Assertions.assertEquals(2000, IntStream.of(perTick).sum());
        Assertions.assertEquals(20, IntStream.of(perTick).max().getAsInt());
    }

    @Test
    void aRoundEndsOnItsLastTickAndTheNextOneStartsOnTheFollowingTick() {
        var schedule = new SpreadOverTicks<Integer>();
        var snapshots = new ArrayList<Integer>();
        for(int t = 0; t < 30; ++t) {
            var tick = t;
            schedule.tick(10, () -> {
                snapshots.add(tick);
                return range(25);
            }, element -> { });
        }
        Assertions.assertEquals(List.of(0, 10, 20), snapshots);
    }

    @Test
    void elementsAddedDuringARoundWaitForTheNextOne() {
        var schedule = new SpreadOverTicks<Integer>();
        var source = new ArrayList<>(range(10));
        var handled = new ArrayList<Integer>();
        schedule.tick(5, () -> new ArrayList<>(source), handled::add);
        source.add(99);
        for(int t = 1; t < 5; ++t)
            schedule.tick(5, () -> new ArrayList<>(source), handled::add);
        Assertions.assertEquals(range(10), handled);
        for(int t = 0; t < 5; ++t)
            schedule.tick(5, () -> new ArrayList<>(source), handled::add);
        Assertions.assertTrue(handled.contains(99));
    }

    @Test
    void aDisabledScheduleDoesNothingAndForgetsItsRound() {
        var schedule = new SpreadOverTicks<Integer>();
        var handled = new ArrayList<Integer>();
        schedule.tick(10, () -> range(50), handled::add);
        Assertions.assertFalse(handled.isEmpty());
        handled.clear();
        schedule.tick(0, () -> { throw new AssertionError("no snapshot while disabled"); }, handled::add);
        schedule.tick(-3, () -> { throw new AssertionError("no snapshot while disabled"); }, handled::add);
        Assertions.assertTrue(handled.isEmpty());
        // Re-enabled, it begins a fresh round rather than finishing the stale one.
        var snapshots = new int[]{ 0 };
        schedule.tick(10, () -> { ++snapshots[0]; return range(50); }, handled::add);
        Assertions.assertEquals(1, snapshots[0]);
    }

    @Test
    void shorteningTheIntervalMidRoundFinishesTheRoundWithinTheNewOne() {
        var schedule = new SpreadOverTicks<Integer>();
        var handled = new ArrayList<Integer>();
        schedule.tick(100, () -> range(300), handled::add);
        // Nine ticks into a 100-tick round the interval drops to 10: the round now has 10 ticks
        // counted from here, and must still hand every element out exactly once.
        for(int t = 1; t < 10; ++t)
            schedule.tick(100, () -> range(300), handled::add);
        var snapshots = new int[]{ 0 };
        for(int t = 0; t < 10; ++t)
            schedule.tick(10, () -> { ++snapshots[0]; return range(300); }, handled::add);
        Assertions.assertEquals(0, snapshots[0], "the running round is finished, not restarted");
        Assertions.assertEquals(300, handled.size());
        Assertions.assertEquals(300, handled.stream().distinct().count());
    }
}
