/*
 * Copyright 2025 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.equipment.multimeter.ProbeSampler;
import org.patryk3211.powergrid.equipment.multimeter.TraceReconstruction;
import org.patryk3211.powergrid.equipment.multimeter.TraceReconstruction.Samples;

/**
 * What the multimeter plot draws between its samples.
 *
 * <h2>Why this exists</h2>
 * A sine sampled 12.9 times a cycle and drawn across the plot came out as a staircase. Each pixel
 * column took "the samples inside it", a column narrower than a sample holds none, and the rule
 * "at least one" made every column repeat the nearest sample: flat runs and vertical jumps, which
 * reads as a square wave. {@link TraceReconstruction} now draws a curve through the samples.
 *
 * <h2>How these tests are built</h2>
 * Each property is a method that takes a {@link Renderer} and returns a number: the worst error,
 * the worst overshoot, the worst gap between neighbouring columns. A test asserts the new code
 * keeps that number small, and a sibling asserts that {@link #OLD_HOLD}, a copy of the loop the
 * screen used to run, does not. A bound the old code also satisfied would prove nothing, so the
 * second half is what shows the bound bites. Each test was in addition run against a deliberately
 * broken {@link TraceReconstruction}; the commit that added them says which break failed which.
 * <p>
 * The exception is overshoot, which a hold cannot violate: it never leaves the sample values.
 * That property guards against fixing the staircase with an interpolator that rings, so its
 * counter-example is a Catmull-Rom curve, which is what a first attempt at this fix would use.
 * <p>
 * The screen that consumes all this needs Minecraft and cannot be reached from here. What is
 * covered is the arithmetic that decides what every column shows; that the rectangles are then
 * drawn where it says is not.
 */
public class TraceReconstructionTest {
    /** Plot width in GUI pixels: the 320 wide panel less 8 of padding each side. */
    private static final int WIDTH = 304;

    /** One waveform as a function of position on the sample axis, where sample i sits at i. */
    interface Wave {
        double at(double position);
    }

    /** Anything that can turn a window of samples into per-column extremes. */
    interface Renderer {
        int columns(Samples samples, int visible, int target, int width, float[] low, float[] high);
    }

    private static final Renderer RECONSTRUCTION = TraceReconstruction::columns;

    /**
     * The loop {@code MultimeterScreen.drawTrace} ran before this change, in value space.
     * <p>
     * Kept line for line, including {@code if(to <= from) to = from + 1} that turns a column
     * narrower than a sample into a hold and the join to the previous column's last sample.
     */
    private static final Renderer OLD_HOLD = (samples, visible, target, width, low, high) -> {
        var first = (target - visible) * width / target;
        var havePrevious = false;
        var previous = 0f;
        for(int px = 0; px < width; ++px)
            low[px] = high[px] = Float.NaN;
        for(int px = first; px < width; ++px) {
            var from = (px * target / width) - (target - visible);
            var to = (((px + 1) * target) / width) - (target - visible);
            if(to <= from)
                to = from + 1;
            if(from < 0)
                from = 0;
            if(to > visible)
                to = visible;
            if(from >= to)
                continue;
            var min = Float.POSITIVE_INFINITY;
            var max = Float.NEGATIVE_INFINITY;
            for(int i = from; i < to; ++i) {
                min = Math.min(min, samples.get(i));
                max = Math.max(max, samples.get(i));
            }
            if(havePrevious) {
                max = Math.max(max, previous);
                min = Math.min(min, previous);
            }
            low[px] = min;
            high[px] = max;
            previous = samples.get(to - 1);
            havePrevious = true;
        }
        return first;
    };

    /**
     * The old mapping from a pixel column to a sample, extracted so it can be measured on its own.
     * <p>
     * A column narrower than a sample took the sample whose cell it started in ({@code from}), and
     * a column that straddled a cell boundary still drew only the earlier sample.
     */
    private static int oldSampleForColumn(int px, int visible, int target, int width) {
        var from = (px * target / width) - (target - visible);
        return Math.max(0, Math.min(visible - 1, from));
    }

    /**
     * Largest distance between what the old mapping drew in a column and the true sine anywhere in
     * that column, as a fraction of the amplitude.
     * <p>
     * {@code centred} says where a sample was taken. False puts it at the start of its cell, the
     * way a sample-and-hold reads. True puts it in the middle, which credits the old drawing with a
     * half-sample shift that nothing on the plot could reveal, and so is the figure that flatters
     * it most.
     */
    private static double holdDeviation(double samplesPerCycle, int visible, int target,
                                        int width, boolean centred) {
        var worst = 0.0;
        var per = (double) target / width;
        var blank = target - visible;
        for(var phase : PHASES) {
            var wave = sine(samplesPerCycle, phase);
            for(int px = (int) ((long) blank * width / target); px < width; ++px) {
                var i = oldSampleForColumn(px, visible, target, width);
                if(i < 3 || i > visible - 4)
                    continue;
                var shown = wave.at(i);
                for(int q = 0; q <= 8; ++q) {
                    var u = px * per - blank + per * q / 8 - (centred ? 0.5 : 0);
                    worst = Math.max(worst, Math.abs(shown - wave.at(u)));
                }
            }
        }
        return worst;
    }

    /** A curve through the samples, evaluated at a position of the sample axis. */
    private interface Curve {
        double at(float[] y, double position);
    }

    private static double sampleOf(float[] y, int i) {
        return y[Math.max(0, Math.min(y.length - 1, i))];
    }

    private static final Curve LINEAR = (y, u) -> {
        var i = (int) Math.floor(u);
        var t = u - i;
        return sampleOf(y, i) * (1 - t) + sampleOf(y, i + 1) * t;
    };

    /** Catmull-Rom: the plain mean of the two chords either side is the slope at a sample. */
    private static final Curve CATMULL_ROM = (y, u) -> {
        var i = (int) Math.floor(u);
        var t = u - i;
        var p0 = sampleOf(y, i);
        var p1 = sampleOf(y, i + 1);
        var m0 = (sampleOf(y, i + 1) - sampleOf(y, i - 1)) / 2;
        var m1 = (sampleOf(y, i + 2) - sampleOf(y, i)) / 2;
        var t2 = t * t;
        var t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0
                + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1;
    };

    /** A renderer that probes a curve at many points per column, for comparison. */
    private static Renderer probing(Curve curve) {
        return (samples, visible, target, width, low, high) -> {
            var window = new float[visible];
            for(int i = 0; i < visible; ++i)
                window[i] = samples.get(i);
            var blank = target - visible;
            var first = (int) ((long) blank * width / target);
            var per = (double) target / width;
            for(int px = 0; px < width; ++px)
                low[px] = high[px] = Float.NaN;
            for(int px = first; px < width; ++px) {
                var a = clampTo(px * per - blank - 0.5, visible);
                var b = clampTo((px + 1) * per - blank - 0.5, visible);
                var min = Double.POSITIVE_INFINITY;
                var max = Double.NEGATIVE_INFINITY;
                for(int q = 0; q <= 32; ++q) {
                    var v = curve.at(window, a + (b - a) * q / 32);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                low[px] = (float) min;
                high[px] = (float) max;
            }
            return first;
        };
    }

    private static double clampTo(double position, int visible) {
        return Math.max(0, Math.min(visible - 1, position));
    }

    // -- waveforms ------------------------------------------------------------------------------

    private static Wave sine(double samplesPerCycle, double phase) {
        return u -> Math.sin(2 * Math.PI * u / samplesPerCycle + phase);
    }

    /** Edges fall between samples for every phase used here, never exactly on one. */
    private static Wave square(double samplesPerCycle, double phase) {
        return u -> Math.sin(2 * Math.PI * u / samplesPerCycle + phase) >= 0 ? 1 : -1;
    }

    private static float[] sampled(Wave wave, int count) {
        var y = new float[count];
        for(int i = 0; i < count; ++i)
            y[i] = (float) wave.at(i);
        return y;
    }

    /** Phases that put the sample grid in different places against the wave. */
    private static final double[] PHASES = {
            0.13, 0.55, 0.97, 1.39, 1.81, 2.23, 2.65, 3.07, 3.49, 3.91, 4.33, 4.75, 5.17, 5.59, 6.01
    };

    // -- properties -----------------------------------------------------------------------------

    /**
     * Where a column sits relative to the samples behind it. The curve is only as good as the
     * samples on each side of it, so the three are measured, and bounded, separately.
     */
    private enum Zone {
        /** Three or more samples from either end: the curve has neighbours on both sides. */
        INTERIOR,
        /** Inside the data but within three samples of an end, where one side of it is missing. */
        END,
        /** Reaches past the centre of the outermost sample: nothing to reconstruct, so a hold. */
        STUB
    }

    private static Zone zoneOf(double a, double b, int visible) {
        if(a < 0 || b > visible - 1)
            return Zone.STUB;
        return a < 3 || b > visible - 4 ? Zone.END : Zone.INTERIOR;
    }

    /**
     * Worst distance between what a column draws and what the true wave does inside it, as a
     * fraction of the amplitude, over the interior columns: those with three or more samples to
     * either side.
     */
    private static double worstError(Renderer renderer, double samplesPerCycle,
                                     int visible, int target, int width) {
        return worstError(renderer, samplesPerCycle, visible, target, width, Zone.INTERIOR, 1);
    }

    /**
     * The same, over the columns of one {@link Zone}, for a sine of the given amplitude. The error
     * is taken as a fraction of that amplitude, so the result does not depend on it if the curve
     * is scale invariant.
     */
    private static double worstError(Renderer renderer, double samplesPerCycle, int visible,
                                     int target, int width, Zone zone, double amplitude) {
        var worst = 0.0;
        for(var phase : PHASES) {
            Wave wave = u -> amplitude * sine(samplesPerCycle, phase).at(u);
            var y = sampled(wave, visible);
            var low = new float[width];
            var high = new float[width];
            var first = renderer.columns(i -> y[i], visible, target, width, low, high);
            var blank = target - visible;
            var per = (double) target / width;
            for(int px = first; px < width; ++px) {
                var a = px * per - blank - 0.5;
                var b = (px + 1) * per - blank - 0.5;
                if(Float.isNaN(low[px]) || zoneOf(a, b, visible) != zone)
                    continue;
                // The first column of a part-filled window starts a fraction of a column before
                // the first cell does; nothing was sampled there, so it is not held to account.
                var from = Math.max(a, -0.5);
                var to = Math.min(b, visible - 0.5);
                var trueLow = Double.POSITIVE_INFINITY;
                var trueHigh = Double.NEGATIVE_INFINITY;
                for(int q = 0; q <= 64; ++q) {
                    var v = wave.at(from + (to - from) * q / 64);
                    trueLow = Math.min(trueLow, v);
                    trueHigh = Math.max(trueHigh, v);
                }
                worst = Math.max(worst, Math.max(Math.abs(low[px] - trueLow),
                        Math.abs(high[px] - trueHigh)) / amplitude);
            }
        }
        return worst;
    }

    /**
     * How much taller the tallest column is than the true wave's own travel across it, as a
     * ratio. A staircase makes it the width of a sample in columns; a curve makes it about one.
     */
    private static double worstSpanRatio(Renderer renderer, double samplesPerCycle,
                                         int visible, int target, int width) {
        var worst = 0.0;
        // Steepest a unit sine gets, per sample, times the samples one column covers.
        var travel = 2 * Math.PI / samplesPerCycle * target / width;
        for(var phase : PHASES) {
            var y = sampled(sine(samplesPerCycle, phase), visible);
            var low = new float[width];
            var high = new float[width];
            var first = renderer.columns(i -> y[i], visible, target, width, low, high);
            var blank = target - visible;
            var per = (double) target / width;
            for(int px = first; px < width; ++px) {
                var a = px * per - blank - 0.5;
                var b = (px + 1) * per - blank - 0.5;
                if(Float.isNaN(low[px]) || a < 3 || b > visible - 4)
                    continue;
                worst = Math.max(worst, (high[px] - low[px]) / travel);
            }
        }
        return worst;
    }

    /** Largest vertical gap between a column and the next, zero when every pair touches. */
    private static double worstGap(Renderer renderer, double samplesPerCycle,
                                   int visible, int target, int width) {
        var worst = 0.0;
        for(var phase : PHASES) {
            var y = sampled(sine(samplesPerCycle, phase), visible);
            var low = new float[width];
            var high = new float[width];
            var first = renderer.columns(i -> y[i], visible, target, width, low, high);
            for(int px = first; px + 1 < width; ++px) {
                if(Float.isNaN(low[px]) || Float.isNaN(low[px + 1]))
                    continue;
                worst = Math.max(worst, Math.max(low[px + 1] - high[px], low[px] - high[px + 1]));
            }
        }
        return worst;
    }

    /** Largest excursion of any column beyond +-1, or of a plateau column away from +-1. */
    private static double worstSquareLie(Renderer renderer, double samplesPerCycle,
                                         int visible, int target, int width) {
        var worst = 0.0;
        for(var phase : PHASES) {
            var wave = square(samplesPerCycle, phase);
            var y = sampled(wave, visible);
            var low = new float[width];
            var high = new float[width];
            var first = renderer.columns(i -> y[i], visible, target, width, low, high);
            for(int px = first; px < width; ++px) {
                if(Float.isNaN(low[px]))
                    continue;
                worst = Math.max(worst, Math.max(high[px] - 1, -1 - low[px]));
            }
        }
        return worst;
    }

    // -- the staircase itself -------------------------------------------------------------------

    @Test
    void theReferenceReproducesTheStaircase() {
        // Guard on the guard: if the copy of the old loop did not stair-step, every "the old code
        // fails this" below would be a statement about a strawman. 32 samples over 304 pixels is
        // the window a tester screenshotted (50 ms at 640 Hz).
        var y = sampled(sine(12.9, 0.4), 32);
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        OLD_HOLD.columns(i -> y[i], 32, 32, WIDTH, low, high);

        var flatColumns = 0;
        var jumps = 0;
        var tallestJump = 0f;
        for(int px = 1; px < WIDTH; ++px) {
            if(high[px] - low[px] < 1e-6)
                ++flatColumns;
            var step = Math.abs(high[px] - low[px]);
            if(step > 0.05f) {
                ++jumps;
                tallestJump = Math.max(tallestJump, step);
            }
        }
        // A sample is 9.5 columns wide, so all but the first column of each is a flat repeat.
        Assertions.assertTrue(flatColumns > WIDTH * 3 / 4,
                "The old loop should be flat for most columns; flat columns: " + flatColumns);
        Assertions.assertTrue(jumps >= 25 && tallestJump > 0.2f,
                "The old loop should jump once per sample by a large step; jumps " + jumps
                        + ", tallest " + tallestJump);
    }

    @Test
    void theOldMappingWasFarFromASineAtEveryRateAMeterSees() {
        // The plot a tester screenshotted: 32 samples over the plot. {samples per cycle, the least
        // the old drawing could be out even with a half sample of free shift, the least without}.
        // Measured: 84.0 % and 146.6 % at 4, 43.0 and 80.3 at 8, 26.8 and 50.7 at 12.9, 21.7 and
        // 41.0 at 16, 10.8 and 20.6 at 32, 6.8 and 13.0 at 51. The floors sit a little below.
        double[][] floors = {
                {4, 0.80, 1.40}, {8, 0.40, 0.75}, {12.9, 0.25, 0.48}, {16, 0.20, 0.39},
                {32, 0.10, 0.19}, {51, 0.06, 0.12}
        };
        for(var floor : floors) {
            var centred = holdDeviation(floor[0], 32, 32, WIDTH, true);
            var forward = holdDeviation(floor[0], 32, 32, WIDTH, false);
            Assertions.assertTrue(centred >= floor[1],
                    "At " + floor[0] + " samples a cycle the old mapping was only " + centred
                            + " out with the shift credited; expected at least " + floor[1]);
            Assertions.assertTrue(forward >= floor[2],
                    "At " + floor[0] + " samples a cycle the old mapping was only " + forward
                            + " out without the shift; expected at least " + floor[2]);
        }
    }

    @Test
    void theOldMappingDrewEachSampleAsAFlatStepNineOrTenColumnsWide() {
        // The mapping itself, with no waveform in it: 304 columns over 32 samples is 9.5 columns a
        // sample, so each sample is drawn 9 or 10 columns wide and the picture is 32 flat steps.
        var runs = new int[32];
        for(int px = 0; px < WIDTH; ++px)
            ++runs[oldSampleForColumn(px, 32, 32, WIDTH)];
        for(int i = 0; i < runs.length; ++i)
            Assertions.assertTrue(runs[i] == 9 || runs[i] == 10,
                    "Sample " + i + " should span 9 or 10 columns, spans " + runs[i]);
    }

    // -- accuracy on a sine ---------------------------------------------------------------------

    @Test
    void aSineIsReconstructedWithinTheMeasuredBound() {
        // {samples per cycle, bound as a fraction of amplitude}. Measured worst case over 15 phases
        // and three windows: 11.6 % at 4, 2.9 % at 6, 1.2 % at 8, 0.72 % at 12.9, 0.51 % at 16,
        // 0.14 % at 32, 0.06 % at 51. The bounds sit a little above that.
        double[][] bounds = {
                {4, 0.125}, {6, 0.035}, {8, 0.015}, {12.9, 0.009}, {16, 0.007}, {32, 0.002},
                {51, 0.001}
        };
        // 32 samples over the plot is 9.5 columns a sample; 103 is the automatic window at 640 Hz
        // and 49.75 Hz; 256 is 1.2 columns a sample, the sparse side of the boundary.
        for(var bound : bounds) {
            for(int target : new int[]{32, 103, 256}) {
                var error = worstError(RECONSTRUCTION, bound[0], target, target, WIDTH);
                Assertions.assertTrue(error <= bound[1],
                        String.format("%.1f samples a cycle over %d samples: error %.4f exceeds %.4f",
                                bound[0], target, error, bound[1]));
            }
        }
    }

    @Test
    void theOldHoldBreaksThoseBoundsByAnOrderOfMagnitude() {
        // The same wave and windows: the hold is off by more than a fifth of the amplitude at 12.9
        // samples a cycle, and by more than the whole tolerance at every rate tested.
        for(int target : new int[]{32, 103, 256}) {
            var error = worstError(OLD_HOLD, 12.9, target, target, WIDTH);
            Assertions.assertTrue(error > 0.2,
                    "The old hold should be more than 20 % out at 12.9 samples a cycle; was "
                            + error + " over " + target + " samples");
        }
        for(double samplesPerCycle : new double[]{8, 16, 32, 51})
            Assertions.assertTrue(worstError(OLD_HOLD, samplesPerCycle, 103, 103, WIDTH) > 0.05,
                    "The old hold should be more than 5 % out at " + samplesPerCycle);
    }

    // -- overshoot ------------------------------------------------------------------------------

    @Test
    void aSquareWaveIsNeverDrawnPastItsPlateaus() {
        // Plateaus of three samples or more, which is every rate from 8 samples a cycle up. The
        // curve must stay inside +-1 and flat on the plateaus, or a clean square wave grows ears.
        for(double samplesPerCycle : new double[]{8, 12.9, 16, 32}) {
            for(int target : new int[]{32, 103, 256}) {
                var lie = worstSquareLie(RECONSTRUCTION, samplesPerCycle, target, target, WIDTH);
                Assertions.assertTrue(lie < 1e-4,
                        "A square wave at " + samplesPerCycle + " samples a cycle was drawn "
                                + lie + " beyond its plateau over " + target + " samples");
            }
        }
    }

    @Test
    void aCatmullRomCurveWouldHaveFailedTheSquareWave() {
        // The counter-example that makes the test above mean something. A hold cannot overshoot,
        // so it is no use here; Catmull-Rom, the obvious way to smooth a sine, rings.
        var lie = worstSquareLie(probing(CATMULL_ROM), 12.9, 103, 103, WIDTH);
        Assertions.assertTrue(lie > 0.10,
                "Catmull-Rom should ring more than 10 % of the amplitude past a square wave; "
                        + "measured " + lie);
    }

    @Test
    void aStepIsASmoothRampInsideItsOwnLevels() {
        // The most common non-sine trace in this mod: a switch closing. Nothing may dip below the
        // old level or rise past the new one, and the levels themselves must be drawn flat.
        var y = new float[40];
        for(int i = 20; i < y.length; ++i)
            y[i] = 10;
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        var first = RECONSTRUCTION.columns(i -> y[i], 40, 40, WIDTH, low, high);
        for(int px = first; px < WIDTH; ++px) {
            Assertions.assertTrue(low[px] >= -1e-5f && high[px] <= 10 + 1e-5f,
                    "Step column " + px + " left the two levels: " + low[px] + " .. " + high[px]);
        }
        // Column 0 is nowhere near the edge, the last is far past it: both flat.
        Assertions.assertEquals(0f, high[0], 1e-6f, "The old level should be flat");
        Assertions.assertEquals(10f, low[WIDTH - 1], 1e-6f, "The new level should be flat");
    }

    // -- continuity -----------------------------------------------------------------------------

    @Test
    void neighbouringColumnsAlwaysTouch() {
        for(double samplesPerCycle : new double[]{4, 8, 12.9, 32, 51}) {
            for(int target : new int[]{32, 103, 256, 1024}) {
                var gap = worstGap(RECONSTRUCTION, samplesPerCycle, target, target, WIDTH);
                Assertions.assertTrue(gap <= 0,
                        "Columns should touch at " + samplesPerCycle + " samples a cycle over "
                                + target + " samples; gap " + gap);
            }
        }
    }

    @Test
    void aSteepEdgeIsAsTallAsTheWaveNotAsTheSampleSpacing() {
        // Touching is not enough: the old loop joined its steps too, with a vertical line the full
        // height of the step. What a curve buys is that no column is much taller than the wave's
        // own travel across it. Ratio 1 is exactly that; the hold's ratio is the columns per sample.
        for(double samplesPerCycle : new double[]{8, 12.9, 16, 32}) {
            for(int target : new int[]{32, 103, 256}) {
                var ratio = worstSpanRatio(RECONSTRUCTION, samplesPerCycle, target, target, WIDTH);
                Assertions.assertTrue(ratio <= 1.25,
                        "A column was " + ratio + " times taller than the wave's travel at "
                                + samplesPerCycle + " samples a cycle over " + target);
            }
        }
    }

    @Test
    void theOldHoldDrawsColumnsAsTallAsAWholeSample() {
        // 32 samples over 304 columns is 9.5 columns a sample, so the steep part of the wave is a
        // vertical line about nine and a half times taller than the wave moves in a column.
        var ratio = worstSpanRatio(OLD_HOLD, 12.9, 32, 32, WIDTH);
        Assertions.assertTrue(ratio > 5,
                "The old hold should draw columns several times the wave's own travel; " + ratio);
    }

    // -- a window that is not full --------------------------------------------------------------

    @Test
    void aPartlyFilledWindowGrowsInFromTheRightAndIsNotStretched() {
        // 103 is the window; 40 samples have arrived. The trace starts 63/103 of the way across.
        var target = 103;
        var visible = 40;
        var y = sampled(sine(12.9, 0.4), visible);
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        java.util.Arrays.fill(low, Float.NaN);
        java.util.Arrays.fill(high, Float.NaN);
        var first = RECONSTRUCTION.columns(i -> y[i], visible, target, WIDTH, low, high);

        var expectedFirst = (target - visible) * WIDTH / target;
        Assertions.assertEquals(expectedFirst, first,
                "The trace should start where the empty part of the window ends");
        for(int px = 0; px < first; ++px)
            Assertions.assertTrue(Float.isNaN(low[px]) && Float.isNaN(high[px]),
                    "Column " + px + " is before the first sample and must stay blank");
        for(int px = first; px < WIDTH; ++px)
            Assertions.assertFalse(Float.isNaN(low[px]) || Float.isNaN(high[px]),
                    "Column " + px + " should have been filled in");

        // Not stretched: sample i belongs in the middle of cell (blank + i), and that column must
        // bracket the sample's own value.
        var perSample = (double) WIDTH / target;
        for(int i = 4; i < visible - 4; ++i) {
            var column = (int) Math.floor((target - visible + i + 0.5) * perSample);
            Assertions.assertTrue(low[column] - 1e-5 <= y[i] && y[i] <= high[column] + 1e-5,
                    "Sample " + i + " is not where its cell is: column " + column + " draws "
                            + low[column] + " .. " + high[column] + " but the sample is " + y[i]);
        }
    }

    @Test
    void aPartlyFilledWindowIsReconstructedAsWellAsAFullOne() {
        // Same wave, same bound. The old hold fails this exactly as it fails the full window.
        var full = worstError(RECONSTRUCTION, 12.9, 103, 103, WIDTH);
        var partial = worstError(RECONSTRUCTION, 12.9, 45, 103, WIDTH);
        Assertions.assertTrue(partial <= 0.009,
                "A window less than half full should still be within 0.9 %; was " + partial);
        Assertions.assertTrue(full <= 0.009, "Sanity: the full window was " + full);

        Assertions.assertTrue(worstError(OLD_HOLD, 12.9, 45, 103, WIDTH) > 0.2,
                "The old hold should be badly out on a part-filled window too");
    }

    @Test
    void aWindowOfOneSampleDrawsALine() {
        var y = new float[]{3f};
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        var first = RECONSTRUCTION.columns(i -> y[i], 1, 100, WIDTH, low, high);
        Assertions.assertEquals(99 * WIDTH / 100, first, "One sample sits at the right edge");
        for(int px = first; px < WIDTH; ++px)
            Assertions.assertTrue(low[px] == 3f && high[px] == 3f, "A single sample is a level");
    }

    @Test
    void nothingToDrawReturnsNoColumns() {
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        Assertions.assertEquals(WIDTH, RECONSTRUCTION.columns(i -> 0, 0, 100, WIDTH, low, high),
                "An empty window has no first column");
    }

    // -- the ends of the window -----------------------------------------------------------------

    /** Full windows at three sizes and a part-filled one, as {visible, target}. */
    private static final int[][] WINDOWS = {{32, 32}, {103, 103}, {256, 256}, {45, 103}};

    @Test
    void theNewestAndOldestSamplesAreInsideTheColumnsThatEndTheTrace() {
        // The right-hand edge is the live edge of a scope: the newest sample is the one a player is
        // watching. Every accuracy figure above skips the ends, so nothing else would notice that
        // the last sample had been dropped from the drawing.
        for(double samplesPerCycle : new double[]{4, 8, 12.9, 32, 51}) {
            for(var window : WINDOWS) {
                for(var phase : PHASES) {
                    var y = sampled(sine(samplesPerCycle, phase), window[0]);
                    var low = new float[WIDTH];
                    var high = new float[WIDTH];
                    var first = RECONSTRUCTION.columns(i -> y[i], window[0], window[1], WIDTH,
                            low, high);
                    var newest = y[window[0] - 1];
                    var oldest = y[0];
                    var where = samplesPerCycle + " samples a cycle, window " + window[0] + " of "
                            + window[1] + ", phase " + phase;
                    Assertions.assertTrue(
                            low[WIDTH - 1] - 1e-6 <= newest && newest <= high[WIDTH - 1] + 1e-6,
                            "The last column draws " + low[WIDTH - 1] + " .. " + high[WIDTH - 1]
                                    + " but the newest sample is " + newest + ": " + where);
                    Assertions.assertTrue(
                            low[first] - 1e-6 <= oldest && oldest <= high[first] + 1e-6,
                            "The first column draws " + low[first] + " .. " + high[first]
                                    + " but the oldest sample is " + oldest + ": " + where);
                }
            }
        }
    }

    @Test
    void aSpikeInTheNewestOrOldestSampleIsDrawn() {
        // The same property with nothing else in the picture, so a failure names the cause.
        var y = new float[32];
        y[0] = -3;
        y[31] = 5;
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        var first = RECONSTRUCTION.columns(i -> y[i], 32, 32, WIDTH, low, high);
        Assertions.assertEquals(5f, high[WIDTH - 1], 1e-5f, "The newest sample should be drawn");
        Assertions.assertEquals(-3f, low[first], 1e-5f, "The oldest sample should be drawn");

        // And in a window that is still filling, where the oldest sample is not at column 0.
        var few = new float[]{7f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -4f};
        var firstOfFew = RECONSTRUCTION.columns(i -> few[i], 10, 32, WIDTH, low, high);
        Assertions.assertEquals(7f, high[firstOfFew], 1e-5f, "The oldest sample should be drawn");
        Assertions.assertEquals(-4f, low[WIDTH - 1], 1e-5f, "The newest sample should be drawn");
    }

    @Test
    void theCurveWithinThreeSamplesOfAnEndIsBoundedToo() {
        // There the curve has neighbours on one side only, and the end slope is the end chord, so a
        // peak falling across the last two samples is under-reached: up to 1 - cos(pi / N). Worst
        // of 360 phases over all four windows: 29.3 % at 4 samples a cycle, 13.4 at 6, 7.55 at 8,
        // 2.95 at 12.9, 1.91 at 16, 0.48 at 32, 0.19 at 51. The bounds sit a little above, and this
        // test uses 15 phases, so it sees a little less than that.
        double[][] bounds = {
                {4, 0.30}, {6, 0.14}, {8, 0.08}, {12.9, 0.031}, {16, 0.02}, {32, 0.005},
                {51, 0.002}
        };
        for(var bound : bounds) {
            for(var window : WINDOWS) {
                var error = worstError(RECONSTRUCTION, bound[0], window[0], window[1], WIDTH,
                        Zone.END, 1);
                Assertions.assertTrue(error <= bound[1],
                        String.format("%.1f samples a cycle, window %d of %d: end error %.4f "
                                + "exceeds %.4f", bound[0], window[0], window[1], error, bound[1]));
            }
        }
        // The old hold was a fifth of the amplitude out at 12.9, ends included.
        Assertions.assertTrue(worstError(OLD_HOLD, 12.9, 103, 103, WIDTH, Zone.END, 1) > 0.2,
                "The old hold should be badly out near the ends as well");
    }

    @Test
    void theHalfCellBeyondTheOutermostSamplesIsAHoldOfKnownSize() {
        // No sample lies beyond the centre of the first or last cell, so that half cell holds the
        // outermost value, which is no better than the drawing this change replaced. Holding a unit
        // sine over half a sample costs 2 sin(pi / (2 N)) at its steepest: 24.3 % at 12.9 samples a
        // cycle, over 4.75 columns at each end of a 32-sample window. docs/AC.md quotes the figure;
        // if this test needs a new bound, that section does too.
        for(double samplesPerCycle : new double[]{8, 12.9, 32}) {
            var hold = 2 * Math.sin(Math.PI / (2 * samplesPerCycle));
            for(var window : WINDOWS) {
                var error = worstError(RECONSTRUCTION, samplesPerCycle, window[0], window[1], WIDTH,
                        Zone.STUB, 1);
                Assertions.assertTrue(error <= hold + 1e-3,
                        "The half cell at an end was " + error + " out at " + samplesPerCycle
                                + " samples a cycle, a hold's worst is " + hold);
                Assertions.assertTrue(error >= 0.9 * hold,
                        "The half cell at an end was only " + error + " out, a hold's is " + hold
                                + ": if it is no longer a hold, docs/AC.md is out of date");
            }
        }
    }

    // -- the two regimes ------------------------------------------------------------------------

    @Test
    void columnsSpanningManySamplesKeepTheEnvelope() {
        // 4096 samples across 304 columns is 13 a column: the case the old code was written for.
        // Every sample inside a column must still be inside what is drawn, and the picture may
        // differ from the old one by no more than two steps between samples: the columns moved by
        // half a sample when a sample became a point rather than a cell, and each column now also
        // reaches the curve's value at both of its edges.
        var y = sampled(sine(51, 0.3), 4096);
        var low = new float[WIDTH];
        var high = new float[WIDTH];
        var oldLow = new float[WIDTH];
        var oldHigh = new float[WIDTH];
        RECONSTRUCTION.columns(i -> y[i], 4096, 4096, WIDTH, low, high);
        OLD_HOLD.columns(i -> y[i], 4096, 4096, WIDTH, oldLow, oldHigh);

        var step = 2 * Math.PI / 51;
        for(int px = 0; px < WIDTH; ++px) {
            var from = (int) Math.ceil(px * 4096.0 / WIDTH - 0.5);
            var to = (int) Math.floor((px + 1) * 4096.0 / WIDTH - 0.5);
            for(int i = Math.max(from, 0); i <= Math.min(to, 4095); ++i)
                Assertions.assertTrue(low[px] - 1e-6 <= y[i] && y[i] <= high[px] + 1e-6,
                        "Sample " + i + " is inside column " + px + " but outside what it draws");
            Assertions.assertTrue(Math.abs(low[px] - oldLow[px]) <= 2 * step
                            && Math.abs(high[px] - oldHigh[px]) <= 2 * step,
                    "Column " + px + " differs from the old envelope by more than two steps");
        }
    }

    @Test
    void thereIsNoSeamWhereTheColumnsPassOneSamplePerColumn() {
        // 64 samples; the plot width sweeps from twice as wide as the window to half as wide, which
        // is 0.5 to 2 samples a column, straight across the point where the search changes.
        for(int width : new int[]{32, 40, 48, 56, 62, 63, 64, 65, 66, 72, 80, 96, 128}) {
            var ratio = worstSpanRatio(RECONSTRUCTION, 12.9, 64, 64, width);
            Assertions.assertTrue(ratio <= 1.3,
                    "A seam at " + (64.0 / width) + " samples a column: tallest column "
                            + ratio + " times the wave's travel");
            var gap = worstGap(RECONSTRUCTION, 12.9, 64, 64, width);
            Assertions.assertTrue(gap <= 0, "Columns stopped touching at width " + width);
        }
    }

    @Test
    void theStepInErrorAtTheSeamIsNoMoreThanWhatTheSampleEnvelopeAlreadyCost() {
        // Measured with 256 samples at 12.9 samples a cycle: 0.63 to 0.72 % of the amplitude while
        // a column is narrower than a sample, 2.78 to 2.93 % once it is wider. Past the seam the
        // envelope of the samples is drawn, which under-reaches a peak that falls between two of
        // them by 1 - cos(pi / 12.9) = 2.95 %, exactly what the old code did there and what any
        // scope drawing sample values does. The bound is that figure; what it must not become is a
        // second, larger discontinuity next to it.
        for(int width : new int[]{512, 400, 320, 270, 256, 240, 200, 160, 128, 64}) {
            var error = worstError(RECONSTRUCTION, 12.9, 256, 256, width);
            Assertions.assertTrue(error <= 0.031,
                    "Error " + error + " at " + (256.0 / width) + " samples a column is beyond "
                            + "the envelope's own 2.95 %");
        }
        // And on the sparse side, where the reconstruction is doing the work, it must stay below one
        // percent however close to the seam it gets, or the seam is a cliff.
        for(int width : new int[]{512, 400, 320, 270, 256})
            Assertions.assertTrue(worstError(RECONSTRUCTION, 12.9, 256, 256, width) <= 0.009,
                    "The sparse side lost accuracy at width " + width);
    }

    @Test
    void theOldHoldHasACliffAtTheSameBoundary() {
        // The old code is fine when a column holds two samples and a staircase when it holds a
        // fifth of one.
        var dense = worstSpanRatio(OLD_HOLD, 12.9, 64, 64, 32);
        var sparse = worstSpanRatio(OLD_HOLD, 12.9, 64, 64, 304);
        Assertions.assertTrue(dense < 2, "The old code was fine when dense: " + dense);
        Assertions.assertTrue(sparse > 4,
                "The old code should fall off a cliff when sparse: " + sparse);
    }

    // -- the curve itself -----------------------------------------------------------------------

    @Test
    void theCurvePassesThroughEverySample() {
        var y = new float[]{0f, 3f, -1f, 4f, 4f, 2.5f, 9f, -6f};
        for(int i = 0; i < y.length; ++i)
            Assertions.assertEquals(y[i], TraceReconstruction.valueAt(j -> y[j], y.length, i), 1e-5,
                    "The curve must hit sample " + i);
    }

    @Test
    void aStraightLineAndALevelAreReproducedExactly() {
        var ramp = new float[12];
        var level = new float[12];
        for(int i = 0; i < ramp.length; ++i) {
            ramp[i] = 2.5f * i - 7;
            level[i] = 4.25f;
        }
        for(double u = 0; u <= 11; u += 0.37) {
            Assertions.assertEquals(2.5 * u - 7, TraceReconstruction.valueAt(i -> ramp[i], 12, u),
                    1e-4, "A ramp should stay a ramp at " + u);
            Assertions.assertEquals(4.25, TraceReconstruction.valueAt(i -> level[i], 12, u), 1e-6,
                    "A level should stay level at " + u);
        }
    }

    @Test
    void twoSamplesAreJoinedByAStraightLine() {
        var y = new float[]{1f, 5f};
        Assertions.assertEquals(3f, TraceReconstruction.valueAt(i -> y[i], 2, 0.5), 1e-6);
        Assertions.assertEquals(2f, TraceReconstruction.valueAt(i -> y[i], 2, 0.25), 1e-6);
    }

    @Test
    void positionsBeyondTheEndsAreClamped() {
        var y = new float[]{1f, 5f, 2f};
        Assertions.assertEquals(1f, TraceReconstruction.valueAt(i -> y[i], 3, -4), 1e-6);
        Assertions.assertEquals(2f, TraceReconstruction.valueAt(i -> y[i], 3, 99), 1e-6);
    }

    // -- why this curve and not another ---------------------------------------------------------

    @Test
    void theChosenCurveBeatsLinearOnASineAndBeatsCatmullRomOnASquareWave() {
        // The trade-off, pinned so it cannot be undone by someone who only looks at one half of it.
        // Linear never overshoots but is four times as far out on a sine (2.95 % against 0.72 % at
        // 12.9 samples a cycle); Catmull-Rom is the most accurate on a sine and rings 14.8 % on a
        // square wave. The chosen curve has to sit on the right side of both.
        var linear = worstError(probing(LINEAR), 12.9, 103, 103, WIDTH);
        var chosen = worstError(RECONSTRUCTION, 12.9, 103, 103, WIDTH);
        Assertions.assertTrue(chosen < linear / 3,
                "The chosen curve should be at least three times as accurate as linear on a sine: "
                        + chosen + " against " + linear);
    }

    @Test
    void aCatmullRomCurveCannotClaimTheTradeOffEither() {
        // The other half: on the square wave the chosen curve does not overshoot and Catmull-Rom
        // does, so accuracy on a sine alone does not decide it.
        var catmull = worstSquareLie(probing(CATMULL_ROM), 12.9, 103, 103, WIDTH);
        var chosen = worstSquareLie(RECONSTRUCTION, 12.9, 103, 103, WIDTH);
        Assertions.assertTrue(chosen < 1e-4 && catmull > 0.10,
                "Chosen " + chosen + ", Catmull-Rom " + catmull);
    }

    // -- the data path is not the cause ---------------------------------------------------------

    /**
     * A stream of {@code ticks} world ticks of {@code snapshot(limit)} from an AC island stepped
     * {@code subTicks} times a tick, the way the server builds each packet.
     */
    private static float[] streamedSine(double hertz, int subTicks, int limit, int ticks) {
        var net = new TestHelper.Network();
        var ground = net.V(0);
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 10, (float) hertz);
        net.network.addNode(terminal);
        net.network.addNode(source);
        net.W(10f, terminal, ground);

        var out = new float[ticks * Math.min(limit, subTicks)];
        var at = 0;
        for(int t = 0; t < ticks; ++t) {
            var probe = ProbeSampler.voltage(terminal, ground);
            net.network.addObserver(probe);
            net.network.calculate(subTicks);
            for(var v : probe.snapshot(limit))
                out[at++] = v;
            net.network.clearObservers();
        }
        return out;
    }

    @Test
    void theStreamNeverRepeatsASampleOfASine() {
        // Half of the question of where the staircase came from: does the client ever receive a
        // held value? 49.75 Hz is the tester's frequency. Each case is 20 world ticks, and no two
        // neighbouring samples may be equal, which a held or duplicated stream would produce in
        // runs. Measured: no equal pair in any of these, at 640 samples a second for 32, 64 and 128
        // sub-ticks against the default limit of 32, 320 for 16 sub-ticks, 200 for a limit of 10,
        // and 20 a second for an island stepped once a tick.
        int[][] cases = {{32, 32}, {64, 32}, {128, 32}, {16, 32}, {32, 10}, {1, 32}};
        for(var c : cases) {
            var y = streamedSine(49.75, c[0], c[1], 20);
            Assertions.assertEquals(20 * Math.min(c[0], c[1]), y.length);
            for(int i = 1; i < y.length; ++i)
                Assertions.assertNotEquals(y[i - 1], y[i],
                        c[0] + " sub-ticks with limit " + c[1] + " repeated sample " + i);
        }
    }
}
