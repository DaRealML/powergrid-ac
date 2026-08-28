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
import org.patryk3211.powergrid.equipment.multimeter.MultimeterPhasor;

/**
 * The phasor extraction the multimeter uses to report phase and complex impedance.
 * <p>
 * Pure arithmetic over a sample array, so unlike the rest of the meter it can be checked properly
 * against synthesised waveforms with known answers rather than only by looking at it in game.
 */
public class PhasorTest {
    private static final double SAMPLE_RATE = 320;

    /** {@code amplitude * sin(2*pi*f*t + phaseDegrees)}, plus an optional DC offset. */
    private static float[] sine(double amplitude, double frequency, double phaseDegrees,
                                double offset, int count) {
        var out = new float[count];
        var phase = Math.toRadians(phaseDegrees);
        for(int i = 0; i < count; ++i)
            out[i] = (float) (offset + amplitude * Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE + phase));
        return out;
    }

    @Test
    void recoversAmplitudeOfAKnownSine() {
        var samples = sine(7.5, 20, 0, 0, 320);
        var phasor = MultimeterPhasor.goertzel(samples, 20, SAMPLE_RATE);
        Assertions.assertEquals(7.5, phasor.magnitude(), 0.05,
                "Magnitude should be the amplitude of the sinusoid");
    }

    @Test
    void ignoresDcOffset() {
        // A rectified or biased supply must not have its offset counted as signal.
        var withOffset = MultimeterPhasor.goertzel(sine(5, 20, 0, 100, 320), 20, SAMPLE_RATE);
        var without = MultimeterPhasor.goertzel(sine(5, 20, 0, 0, 320), 20, SAMPLE_RATE);
        Assertions.assertEquals(without.magnitude(), withOffset.magnitude(), 0.05,
                "A large DC offset must not leak into the fundamental");
    }

    @Test
    void quarterCycleShiftReadsAsNinetyDegrees() {
        // The measurement the whole feature exists for: a reactive component puts voltage and
        // current a quarter cycle apart, and only a phase-aware meter can see it.
        var reference = MultimeterPhasor.goertzel(sine(1, 20, 0, 0, 320), 20, SAMPLE_RATE);
        var shifted = MultimeterPhasor.goertzel(sine(1, 20, 90, 0, 320), 20, SAMPLE_RATE);

        var difference = shifted.phaseDegrees() - reference.phaseDegrees();
        while(difference <= -180) difference += 360;
        while(difference > 180) difference -= 360;

        Assertions.assertEquals(90, difference, 1.0, "A quarter-cycle shift should read 90 degrees");
    }

    @Test
    void impedanceOfAResistiveLoadIsRealAndPositive() {
        // 10 V across 5 ohms is 2 A, in phase. Z should come out 5 + j0.
        var voltage = MultimeterPhasor.goertzel(sine(10, 20, 0, 0, 320), 20, SAMPLE_RATE);
        var current = MultimeterPhasor.goertzel(sine(2, 20, 0, 0, 320), 20, SAMPLE_RATE);
        var z = MultimeterPhasor.impedance(voltage, current);

        Assertions.assertEquals(5.0, z.real(), 0.05, "Resistance should be V/I");
        Assertions.assertEquals(0.0, z.imaginary(), 0.05, "A resistor has no reactance");
    }

    @Test
    void inductiveAndCapacitiveLoadsHaveOppositeReactanceSigns() {
        var voltage = MultimeterPhasor.goertzel(sine(10, 20, 0, 0, 320), 20, SAMPLE_RATE);

        // Current lagging voltage by 90 degrees is an inductor: reactance positive.
        var lagging = MultimeterPhasor.goertzel(sine(2, 20, -90, 0, 320), 20, SAMPLE_RATE);
        var inductive = MultimeterPhasor.impedance(voltage, lagging);
        Assertions.assertTrue(inductive.imaginary() > 0,
                "A lagging current should give positive (inductive) reactance, got " + inductive.imaginary());
        Assertions.assertEquals(0, inductive.real(), 0.05, "An ideal inductor has no resistance");

        // Current leading voltage is a capacitor: reactance negative.
        var leading = MultimeterPhasor.goertzel(sine(2, 20, 90, 0, 320), 20, SAMPLE_RATE);
        var capacitive = MultimeterPhasor.impedance(voltage, leading);
        Assertions.assertTrue(capacitive.imaginary() < 0,
                "A leading current should give negative (capacitive) reactance, got " + capacitive.imaginary());
    }

    @Test
    void estimatesFrequencyFromZeroCrossings() {
        Assertions.assertEquals(20, MultimeterPhasor.estimateFrequency(sine(5, 20, 0, 0, 320), SAMPLE_RATE), 1.0);
        Assertions.assertEquals(40, MultimeterPhasor.estimateFrequency(sine(5, 40, 0, 0, 320), SAMPLE_RATE), 1.5);
        Assertions.assertEquals(4.5, MultimeterPhasor.estimateFrequency(sine(5, 4.5, 0, 0, 320), SAMPLE_RATE), 0.5);
    }

    @Test
    void steadySignalHasNoFrequency() {
        var flat = new float[320];
        java.util.Arrays.fill(flat, 12f);
        Assertions.assertEquals(0, MultimeterPhasor.estimateFrequency(flat, SAMPLE_RATE), 1e-9,
                "A steady reading should report no alternating component");
    }

    @Test
    void reflectionCoefficientIsZeroOnAMatchedLoad() {
        var matched = MultimeterPhasor.reflectionCoefficient(new MultimeterPhasor.Phasor(50, 0), 50);
        Assertions.assertEquals(0, matched.magnitude(), 1e-9, "A matched load reflects nothing");
        Assertions.assertEquals(1, MultimeterPhasor.standingWaveRatio(matched), 1e-9,
                "A matched load has an SWR of 1");

        // Two-to-one mismatch is a textbook SWR of 2.
        var mismatched = MultimeterPhasor.reflectionCoefficient(new MultimeterPhasor.Phasor(100, 0), 50);
        Assertions.assertEquals(2.0, MultimeterPhasor.standingWaveRatio(mismatched), 0.01);
    }

    @Test
    void nonIntegerCycleCountStillResolves() {
        // The window rarely holds a whole number of cycles, which is exactly why this uses
        // Goertzel at an arbitrary frequency rather than an FFT bin.
        var samples = sine(3, 17.3, 0, 0, 250);
        var phasor = MultimeterPhasor.goertzel(samples, 17.3, SAMPLE_RATE);
        Assertions.assertEquals(3.0, phasor.magnitude(), 0.15,
                "Amplitude should survive a non-integer number of cycles in the window");
    }
}
