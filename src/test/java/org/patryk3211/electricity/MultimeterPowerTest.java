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
 * Real power, apparent power and power factor, as the multimeter computes them.
 *
 * <h2>Why real power is taken from the samples and not from the phasors</h2>
 * The multimeter already fits a phasor to each channel, so deriving power from the fitted
 * magnitudes and the angle between them would have been the obvious route. It is also wrong the
 * moment a waveform is not a pure sinusoid: a phasor at the fundamental knows nothing about the
 * harmonics, and a rectifier's current is mostly harmonics. The mean of the product is what real
 * power <em>is</em>, at any waveform, so that is what this measures. The last test is the one that
 * would catch a regression back to the phasor route.
 */
public class MultimeterPowerTest {
    private static final int SAMPLES = 2048;

    /** A sine of the given amplitude, phase-shifted by {@code degrees}, over whole cycles. */
    private static float[] sine(double amplitude, double degrees, int cycles) {
        var out = new float[SAMPLES];
        var shift = Math.toRadians(degrees);
        for(int k = 0; k < SAMPLES; ++k)
            out[k] = (float) (amplitude * Math.sin(2 * Math.PI * cycles * k / SAMPLES + shift));
        return out;
    }

    @Test
    void aResistiveLoadTakesAllOfWhatItCosts() {
        // In phase, so real and apparent power are the same number and the factor is one.
        var v = sine(240, 0, 8);
        var i = sine(10, 0, 8);

        var real = MultimeterPhasor.realPower(v, i);
        var apparent = MultimeterPhasor.apparentPower(v, i);

        // Peak times peak over two, for a sinusoid.
        Assertions.assertEquals(240 * 10 / 2.0, real, 240 * 10 / 2.0 * 0.01,
                "A resistive load's real power should be Vpk*Ipk/2, got " + real);
        Assertions.assertEquals(real, apparent, real * 0.01,
                "With no phase angle the two powers are the same, got " + real + " and " + apparent);
        Assertions.assertEquals(1.0, MultimeterPhasor.powerFactor(real, apparent), 0.01,
                "A resistor's power factor is one");
    }

    @Test
    void aPurelyReactiveLoadCostsEverythingAndConsumesNothing() {
        // A quarter cycle apart: energy goes in during one quarter and comes back out the next, so
        // the mean of the product is zero while the RMS values are as large as ever. This is the
        // case the old instantaneous product could never have shown, and the reason a meter that
        // reports only one number is hiding the interesting part.
        var v = sine(240, 0, 8);
        var i = sine(10, -90, 8);

        var real = MultimeterPhasor.realPower(v, i);
        var apparent = MultimeterPhasor.apparentPower(v, i);

        Assertions.assertEquals(0, real, 240 * 10 * 0.001,
                "A quarter-cycle shift should consume no real power, got " + real);
        Assertions.assertEquals(240 * 10 / 2.0, apparent, 240 * 10 / 2.0 * 0.01,
                "But it still costs the full apparent power, got " + apparent);
        Assertions.assertEquals(0, MultimeterPhasor.powerFactor(real, apparent), 0.01,
                "And its power factor is zero");
    }

    @Test
    void thePowerFactorIsTheCosineOfTheAngle() {
        for(var degrees : new double[] { 0, 30, 45, 60, 80 }) {
            var v = sine(240, 0, 8);
            var i = sine(10, -degrees, 8);
            var real = MultimeterPhasor.realPower(v, i);
            var apparent = MultimeterPhasor.apparentPower(v, i);
            var factor = MultimeterPhasor.powerFactor(real, apparent);
            Assertions.assertEquals(Math.cos(Math.toRadians(degrees)), factor, 0.02,
                    "At " + degrees + " degrees the power factor should be its cosine, got " + factor);
        }
    }

    @Test
    void aProbePointedAtASourceReadsNegativeRealPower() {
        // Antiphase: power flowing the other way. Worth having rather than clamping to zero,
        // because it is how a player can tell a generator from a load with the same probe.
        var v = sine(240, 0, 8);
        var i = sine(10, 180, 8);
        var real = MultimeterPhasor.realPower(v, i);
        Assertions.assertTrue(real < 0, "Antiphase current means power flowing out, got " + real);
        Assertions.assertEquals(-1.0,
                MultimeterPhasor.powerFactor(real, MultimeterPhasor.apparentPower(v, i)), 0.01,
                "And a power factor of minus one");
    }

    @Test
    void nothingFlowingIsNotADivisionByZero() {
        var v = sine(240, 0, 8);
        var i = new float[SAMPLES];
        var real = MultimeterPhasor.realPower(v, i);
        var apparent = MultimeterPhasor.apparentPower(v, i);
        Assertions.assertEquals(0, real, 1e-9);
        Assertions.assertEquals(0, MultimeterPhasor.powerFactor(real, apparent), 1e-9,
                "No current should give a power factor of zero rather than a NaN");
        Assertions.assertFalse(Double.isNaN(MultimeterPhasor.powerFactor(0, 0)),
                "Zero over zero must not escape as NaN");
    }

    @Test
    void aDistortedCurrentIsMeasuredCorrectlyWhereAPhasorWouldNotBe() {
        // The reason this reads samples rather than fitted phasors. A square current has the same
        // fundamental as a sine of 4/pi times the amplitude, so a phasor-derived power would use
        // that fundamental alone; the true real power against a sinusoidal voltage is the mean of
        // the product, and the harmonics contribute nothing to it because they are orthogonal to
        // the voltage. The two routes therefore disagree, and the sample route is the right one.
        var v = sine(240, 0, 8);
        var square = new float[SAMPLES];
        for(int k = 0; k < SAMPLES; ++k)
            square[k] = Math.signum(sine(1, 0, 8)[k]) * 10;

        var real = MultimeterPhasor.realPower(v, square);

        // The fundamental of a square wave of amplitude A is 4A/pi, in phase, so against a
        // sinusoidal voltage the real power is Vpk * (4A/pi) / 2.
        var expected = 240 * (4 * 10 / Math.PI) / 2;
        Assertions.assertEquals(expected, real, expected * 0.02,
                "Real power against a square current should be " + expected + ", got " + real);

        // And the apparent power uses the square's own RMS, which is its full amplitude, so the
        // power factor is below one even though nothing is out of phase. That is distortion factor
        // rather than displacement factor, and a meter should show it.
        var apparent = MultimeterPhasor.apparentPower(v, square);
        var factor = MultimeterPhasor.powerFactor(real, apparent);
        Assertions.assertTrue(factor > 0.85 && factor < 0.95,
                "A square current should read a power factor near 0.9 with nothing out of phase, got "
                        + factor);
    }
}
