package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.schedule.RateLadder;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;

/** The rungs, the rounding they cause, and the hysteresis between them. */
public class RateLadderTest {
    @Test
    void oneRungPerOctaveIsExactlyTheOriginalPowerOfTwoRule() {
        // The ladder in its original mode must reproduce AcSampling.subTicksFor for every frequency,
        // resolution and ceiling, including a ceiling that is not a power of two.
        for(int ceiling : new int[]{ 1, 4, 16, 32, 64, 100, 128 }) {
            var ladder = RateLadder.of(1, ceiling);
            for(int spc : new int[]{ 4, 8, 16, 32, 64 }) {
                for(double f = 0; f <= 220; f += 0.37) {
                    var expected = AcSampling.subTicksFor(f, spc, ceiling);
                    var actual = ladder.roundUp(AcSampling.exactSubTicksFor(f, spc, ceiling));
                    Assertions.assertEquals(expected, actual, "f=" + f + " spc=" + spc + " ceiling=" + ceiling);
                }
            }
        }
    }

    @Test
    void theFineLadderHasTheRungsAPlayerWouldExpect() {
        var ladder = RateLadder.of(4, 128);
        var levels = ladder.levels();
        for(int i = 1; i < levels.length; ++i)
            Assertions.assertTrue(levels[i] > levels[i - 1], "not strictly increasing at " + i);
        for(int rung : new int[]{ 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128 })
            Assertions.assertEquals(rung, ladder.roundUp(rung), rung + " should be a rung");
        Assertions.assertEquals(96, ladder.roundUp(81), "80 Hz at 32 samples per cycle is 80 sub-ticks, 81 rounds to 96");
        Assertions.assertEquals(128, ladder.top());
        Assertions.assertEquals(128, ladder.roundUp(500));
        // A ceiling that is not a power of two is a rung itself in the fine ladder, but not in the original.
        Assertions.assertEquals(100, RateLadder.of(4, 100).top());
        Assertions.assertEquals(64, RateLadder.of(1, 100).top());
        Assertions.assertEquals(96, RateLadder.of(2, 100).below(100));
    }

    @Test
    void roundingUpWastesAtMostAQuarterAboveTheSmallRungs() {
        var fine = RateLadder.of(4, 128);
        var coarse = RateLadder.of(1, 128);
        double worstFine = 0, worstCoarse = 0;
        for(int needed = 8; needed <= 128; ++needed) {
            worstFine = Math.max(worstFine, fine.roundUp(needed) / (double) needed);
            worstCoarse = Math.max(worstCoarse, coarse.roundUp(needed) / (double) needed);
        }
        Assertions.assertTrue(worstFine <= 1.25 + 1e-9, "fine ladder wastes " + worstFine);
        Assertions.assertTrue(worstCoarse > 1.9, "the original rule wastes almost 2x just above a power of two: " + worstCoarse);
    }

    @Test
    void neighboursAreConsistent() {
        var ladder = RateLadder.of(4, 128);
        Assertions.assertEquals(64, ladder.below(80));
        Assertions.assertEquals(80, ladder.above(64));
        Assertions.assertEquals(64, ladder.below(70), "below a non-rung is the rung under it");
        Assertions.assertEquals(80, ladder.above(70));
        Assertions.assertEquals(1, ladder.below(1));
        Assertions.assertEquals(128, ladder.above(128));
    }

    // ------------------------------------------------------------------ hysteresis

    @Test
    void aRateRisesAtOnceAndFallsOnlyWellBelowTheRungUnderneath() {
        var ladder = RateLadder.of(4, 128);
        Assertions.assertEquals(96, ladder.settle(80, 81), "rising is immediate");
        Assertions.assertEquals(96, ladder.settle(96, 80), "80 fits the rung below, but not by a margin");
        Assertions.assertEquals(96, ladder.settle(96, 73));
        Assertions.assertEquals(80, ladder.settle(96, 72), "at 90% of the rung below it steps down");
        Assertions.assertEquals(64, ladder.settle(96, 60));
        Assertions.assertEquals(80, ladder.settle(0, 80), "an island seen for the first time takes the plain rung");
    }

    @Test
    void theSmallRungsFallByAWholeStepAndAStoppedMachineFallsToOne() {
        var ladder = RateLadder.of(4, 128);
        Assertions.assertEquals(2, ladder.settle(2, 2));
        Assertions.assertEquals(1, ladder.settle(2, 1), "nothing wants sub-stepping any more");
        Assertions.assertEquals(4, ladder.settle(4, 3), "one whole step of margin on a small rung");
        Assertions.assertEquals(2, ladder.settle(4, 2));
        Assertions.assertEquals(1, ladder.settle(1, 1));
    }

    @Test
    void aDemandHoveringOnARungBoundaryDoesNotFlap() {
        var ladder = RateLadder.of(4, 128);
        var rate = 0;
        var changes = 0;
        // 80.4 would round up to 96 and 79.6 to 80: a demand wobbling across the boundary.
        for(int t = 0; t < 10_000; ++t) {
            var demand = (int) Math.ceil(80 + 0.6 * Math.sin(t * 0.7));
            var next = ladder.settle(rate, demand);
            if(next != rate)
                ++changes;
            rate = next;
        }
        Assertions.assertTrue(changes <= 2, "rate changed " + changes + " times");
    }

    @Test
    void aRateAboveALoweredCeilingIsNotHeld() {
        var ladder = RateLadder.of(4, 64);
        Assertions.assertEquals(64, ladder.settle(128, 64));
    }

    @Test
    void invalidRungCountsAreRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> RateLadder.of(3, 128));
    }

    @Test
    void exactSubTicksIsTheSmallestWholeNumberOfSamplesAndSurvivesFloatingPointError() {
        Assertions.assertEquals(80, AcSampling.exactSubTicksFor(50, 32, 128));
        // A frequency derived from a shaft speed is 115.00000000000001 for a machine meant to run at
        // 115 Hz, which asks for 184.00000000000003 samples: it must not be rounded up to 185.
        var omega = 115 * AcSampling.TWO_PI / 3;
        var derived = Math.abs(omega) * 3 / AcSampling.TWO_PI;
        Assertions.assertTrue(derived > 115, "the premise: the derived frequency overshoots (" + derived + ")");
        Assertions.assertEquals(184, AcSampling.exactSubTicksFor(derived, 32, 256));
        Assertions.assertEquals(81, AcSampling.exactSubTicksFor(50.1, 32, 128));
        Assertions.assertEquals(1, AcSampling.exactSubTicksFor(0, 32, 128));
        Assertions.assertEquals(1, AcSampling.exactSubTicksFor(-3, 32, 128));
        Assertions.assertEquals(100, AcSampling.exactSubTicksFor(500, 32, 100), "clamped to the ceiling as given");
        Assertions.assertEquals(8, AcSampling.exactSubTicksFor(4.53, 32, 64), "7.25 rounds up to 8");
    }
}
