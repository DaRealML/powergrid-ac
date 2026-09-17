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
import org.patryk3211.powergrid.equipment.multimeter.MultimeterTrace;

/**
 * The meter's RMS should not move while the signal holds still.
 *
 * <h2>Why it did</h2>
 * The displayed window is eight cycles of the measured frequency rounded to a whole number of
 * samples, so it holds a part cycle as well. That part is squared into the average like everything
 * else, and as the waveform walks through the window the reading moves with it. It is a small
 * error -- the numbers below are under a percent -- but it is visible, because it moves.
 */
public class WholeCycleRmsTest {
    private static float[] sine(double amplitude, double samplesPerCycle, int count, double offset) {
        var out = new float[count];
        for(int k = 0; k < count; ++k)
            out[k] = (float) (amplitude * Math.sin(2 * Math.PI * (k + offset) / samplesPerCycle));
        return out;
    }

    /** Peak-to-peak spread of the reading as the waveform walks through the window, in percent. */
    private static double spread(double samplesPerCycle, int window, boolean wholeCycles) {
        var amplitude = 225 * Math.sqrt(2);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for(int step = 0; step < 64; ++step) {
            var samples = sine(amplitude, samplesPerCycle, window, step * samplesPerCycle / 64);
            var rms = wholeCycles
                    ? MultimeterTrace.wholeCycleRms(samples, samplesPerCycle)
                    : MultimeterTrace.wholeCycleRms(samples, 0);
            min = Math.min(min, rms);
            max = Math.max(max, rms);
        }
        return (max - min) / (amplitude / Math.sqrt(2)) * 100;
    }

    @Test
    void awholeCycleWindowHoldsStillWhereAPartCycleWanders() {
        // 50 Hz at 32 sub-ticks: 12.8 samples a cycle, and eight cycles rounds to 102 samples.
        var whole = spread(12.8, 102, true);
        var part = spread(12.8, 102, false);
        System.out.printf("50 Hz at 32 sub-ticks: part-cycle window %.2f%%, whole-cycle window %.2f%%%n",
                part, whole);
        Assertions.assertTrue(part > 0.2,
                "The part-cycle window should visibly wander, got " + String.format("%.2f%%", part));
        Assertions.assertTrue(whole < part / 3,
                "Whole cycles should hold far steadier, got " + String.format("%.2f%%", whole)
                        + " against " + String.format("%.2f%%", part));
    }

    @Test
    void theReadingIsStillTheRightNumber() {
        // Steadier is worthless if it is steady at the wrong value.
        var amplitude = 225 * Math.sqrt(2);
        var samples = sine(amplitude, 12.8, 102, 0);
        Assertions.assertEquals(225, MultimeterTrace.wholeCycleRms(samples, 12.8), 225 * 0.005,
                "Whole-cycle RMS of a 225 V sine is 225 V");
    }

    @Test
    void aWindowTooShortForACycleIsLeftAlone() {
        // Fewer samples than one cycle, and there is no whole cycle to take: every sample counts,
        // which is also what a steady reading needs.
        var samples = sine(10, 40, 12, 0);
        Assertions.assertEquals(MultimeterTrace.wholeCycleRms(samples, 0),
                MultimeterTrace.wholeCycleRms(samples, 40), 1e-6,
                "A window shorter than a cycle should use every sample");
        Assertions.assertEquals(0, MultimeterTrace.wholeCycleRms(new float[0], 12.8), 1e-9,
                "An empty window is zero, not a division by zero");
    }

    @Test
    void steadyReadingsAreUnaffected() {
        // Direct current arrives with no frequency at all, and must read its own magnitude.
        var flat = new float[64];
        java.util.Arrays.fill(flat, 12f);
        Assertions.assertEquals(12, MultimeterTrace.wholeCycleRms(flat, 0), 1e-6);
        Assertions.assertEquals(12, MultimeterTrace.wholeCycleRms(flat, 12.8), 1e-6);
    }
}
