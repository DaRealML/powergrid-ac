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
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * What the integration scheme costs in accuracy, measured rather than asserted.
 *
 * <h2>Why this is separate from {@link MotorReactanceTest}</h2>
 * That one measures {@code rmsVoltage()/rmsCurrent()}, which is a magnitude and nothing else. The
 * magnitude is the part backward Euler gets nearly right — within 5% even at the worst sampling
 * the mod reaches. The damage is in the <em>phase</em>, which a ratio of two RMS accumulators
 * cannot see at all, and which decides power factor, real power, and how much torque an
 * alternator has to take off its shaft to feed a motor.
 *
 * <h2>These pin the defect, not the fix</h2>
 * They assert the numbers the shipped scheme produces today, as bands. An improvement to the
 * integration will fail them, which is deliberate: the config comment on
 * {@code electricity.motorTimeConstant} quotes these figures to players, so they should not be
 * able to drift without someone noticing.
 */
public class IntegrationSchemeTest extends TestHelper {
    /** The shipped electric motor coil: nominal resistance and the configured time constant. */
    private static final double R = 25.6;
    private static final double TAU = 0.01;
    private static final double L = R * TAU;

    private static final double AMPLITUDE = 240;
    private static final float SOURCE_RESISTANCE = 0.001f;

    /** Sixteen pole pairs at the alternator's top shaft speed — the worst case the mod makes. */
    private static final double TOP_FREQUENCY = 72.533;

    /** What the shipped config asks for, and the ceiling that denies it. */
    private static final int SAMPLES_PER_CYCLE = 32;
    private static final int MAX_SUB_TICKS = 16;

    /** A branch's fundamental response: impedance magnitude, phase, and the power behind it. */
    private record Response(double magnitude, double phase, double vPeak, double iPeak) {
        double powerFactor() {
            return Math.cos(phase);
        }

        /** Real power the source must deliver. Peak phasors, so half the product. */
        double sourcePower() {
            return 0.5 * vPeak * iPeak * powerFactor();
        }

        /** Real power the coil turns into heat in its own resistance. */
        double coilHeat() {
            return 0.5 * iPeak * iPeak * R;
        }
    }

    /**
     * Drive an L-R branch at one frequency and extract its fundamental response.
     * <p>
     * The phasors come from a least-squares fit at the known driving frequency rather than a
     * discrete Fourier transform. A transform would need the record to hold a whole number of
     * cycles and the sub-tick grid gives no such thing — at 72.5 Hz and sixteen sub-ticks a cycle
     * is 4.41 samples — so its spectral leakage would be the same order as the error being
     * measured. The frequency is known exactly here, which makes fitting both simpler and exact.
     */
    private static Response measure(double frequency, int subTicks) {
        var net = new Network();
        var terminal = new FloatingNode();
        net.network.addNode(terminal);
        net.network.addNode(new ACVoltageSourceCoupling(terminal, null, SOURCE_RESISTANCE,
                (float) AMPLITUDE, (float) frequency));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var coil = new LRSeriesWire(L, R, terminal, ground);
        net.network.addWire(coil);

        var dt = AcSampling.TICK_SECONDS / subTicks;
        // Twenty cycles of transient, or ten world ticks, whichever is the longer wait.
        var settle = (int) Math.max(10, Math.ceil(20 / (frequency * AcSampling.TICK_SECONDS)));
        for(int t = 0; t < settle; ++t)
            net.network.calculate(subTicks);

        var n = (int) Math.ceil(40 / (frequency * dt));
        var v = new double[n];
        var i = new double[n];
        var theta = new double[n];
        var k = 0;
        while(k < n) {
            net.network.prepare(subTicks);
            for(int s = 0; s < subTicks && k < n; ++s, ++k) {
                net.network.singleTick();
                v[k] = coil.potentialDifference();
                i[k] = coil.current();
                theta[k] = AcSampling.TWO_PI * frequency * (k * dt);
            }
        }

        var V = fit(v, theta);
        var I = fit(i, theta);
        // V/I as a complex ratio. Components are ordered {sine, cosine}, so a pure sine reads
        // zero phase and a branch that lags reads positive.
        var d = I[0] * I[0] + I[1] * I[1];
        var re = (V[0] * I[0] + V[1] * I[1]) / d;
        var im = (V[1] * I[0] - V[0] * I[1]) / d;
        return new Response(Math.hypot(re, im), Math.atan2(im, re),
                Math.hypot(V[0], V[1]), Math.hypot(I[0], I[1]));
    }

    /** Least squares fit of a signal to {@code a*sin(t) + b*cos(t) + c}, returning {@code {a, b}}. */
    private static double[] fit(double[] signal, double[] theta) {
        double ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0, ys = 0, yc = 0, y1 = 0;
        for(int k = 0; k < signal.length; ++k) {
            var s = Math.sin(theta[k]);
            var c = Math.cos(theta[k]);
            ss += s * s; sc += s * c; s1 += s;
            cc += c * c; c1 += c;
            ys += signal[k] * s; yc += signal[k] * c; y1 += signal[k];
        }
        var A = new double[][] { { ss, sc, s1 }, { sc, cc, c1 }, { s1, c1, signal.length } };
        var b = new double[] { ys, yc, y1 };
        for(int col = 0; col < 3; ++col) {
            var pivot = col;
            for(int r = col + 1; r < 3; ++r)
                if(Math.abs(A[r][col]) > Math.abs(A[pivot][col]))
                    pivot = r;
            var swapRow = A[col]; A[col] = A[pivot]; A[pivot] = swapRow;
            var swapB = b[col]; b[col] = b[pivot]; b[pivot] = swapB;
            for(int r = col + 1; r < 3; ++r) {
                var f = A[r][col] / A[col][col];
                for(int c = col; c < 3; ++c)
                    A[r][c] -= f * A[col][c];
                b[r] -= f * b[col];
            }
        }
        var x = new double[3];
        for(int r = 2; r >= 0; --r) {
            var sum = b[r];
            for(int c = r + 1; c < 3; ++c)
                sum -= A[r][c] * x[c];
            x[r] = sum / A[r][r];
        }
        return new double[] { x[0], x[1] };
    }

    @Test
    void theMagnitudeSurvivesTheCoarseSamplingAndThePhaseDoesNot() {
        var subTicks = AcSampling.subTicksFor(TOP_FREQUENCY, SAMPLES_PER_CYCLE, MAX_SUB_TICKS);
        Assertions.assertEquals(MAX_SUB_TICKS, subTicks,
                "Sixteen pole pairs should be sampled at the shipped ceiling");

        var r = measure(TOP_FREQUENCY, subTicks);
        var omega = AcSampling.TWO_PI * TOP_FREQUENCY;
        var exactMagnitude = Math.hypot(R, omega * L);
        var exactPhase = Math.toDegrees(Math.atan2(omega * L, R));

        Assertions.assertEquals(exactMagnitude, r.magnitude(), exactMagnitude * 0.08,
                "The impedance magnitude should be within a few percent even here, got "
                        + r.magnitude() + " against " + exactMagnitude);

        // The phase is where it falls apart: about 40 degrees measured against 78 exact. A band,
        // so that fixing the scheme fails this test rather than quietly passing it.
        var measured = Math.toDegrees(r.phase());
        Assertions.assertTrue(measured > 35 && measured < 46,
                "Backward Euler should read about 40 degrees of lag where "
                        + String.format("%.1f", exactPhase) + " is correct, got "
                        + String.format("%.1f", measured));
    }

    @Test
    void theSchemeAbsorbsMostOfThePowerTheGridDelivers() {
        // The consequence of that phase error, and the one a player can feel. Backward Euler is
        // dissipative, and its damping appears as a fictitious resistance in series with the coil:
        // the branch's real part reads about 96 ohms against a true 25.6. The grid pays for all of
        // it — alternator torque, fuel, wire heating — and the motor sees none of it.
        var r = measure(TOP_FREQUENCY, MAX_SUB_TICKS);

        var phantom = r.sourcePower() - r.coilHeat();
        Assertions.assertTrue(phantom > 0,
                "Backward Euler is dissipative, so the source must deliver more than the coil takes");
        Assertions.assertTrue(phantom / r.sourcePower() > 0.6,
                "The scheme should currently absorb most of the delivered power, got "
                        + String.format("%.0f%%", 100 * phantom / r.sourcePower()));

        // Against the exact branch driven to the same terminal voltage.
        var omega = AcSampling.TWO_PI * TOP_FREQUENCY;
        var exactCurrent = r.vPeak() / Math.hypot(R, omega * L);
        var exactPower = 0.5 * exactCurrent * exactCurrent * R;
        var ratio = r.sourcePower() / exactPower;
        Assertions.assertTrue(ratio > 2.5 && ratio < 4.5,
                "The grid should currently deliver roughly 3.4x the true real power, got "
                        + String.format("%.2fx", ratio));
    }

    @Test
    void thePhaseErrorIsTheSampleIntervalAndNotTheFrequency() {
        // Backward Euler lags a reactive branch by omega*dt/2 to first order. That is the whole
        // story: the error is set by the sample interval, not by the frequency. It is invisible at
        // 4.5 Hz and ruinous at 72.5 only because the sub-tick ceiling stops the timestep
        // shrinking once eight sub-ticks are no longer enough — dt stops falling while the
        // frequency keeps rising. Solved finely, the same 72.5 Hz obeys the same law and lands
        // within a few degrees, which puts the defect in the sampling ceiling rather than in the
        // companion models.
        var omega = AcSampling.TWO_PI * TOP_FREQUENCY;
        var exactPhase = Math.atan2(omega * L, R);

        var coarse = measure(TOP_FREQUENCY, MAX_SUB_TICKS);
        var fine = measure(TOP_FREQUENCY, 128);

        var coarseError = Math.abs(exactPhase - coarse.phase());
        var fineError = Math.abs(exactPhase - fine.phase());
        Assertions.assertTrue(fineError < coarseError / 4,
                "Eight times the sampling should cut the phase error by much more than half, got "
                        + String.format("%.1f deg -> %.1f deg", Math.toDegrees(coarseError),
                                Math.toDegrees(fineError)));

        // Both rates against omega*dt/2. The prediction is first order, so it is a little
        // optimistic at the coarse rate where omega*dt is 1.42 radians and higher terms are no
        // longer negligible; 15% covers both without being loose enough to accept a scheme change.
        for(var rate : new int[] { MAX_SUB_TICKS, 128 }) {
            var error = rate == MAX_SUB_TICKS ? coarseError : fineError;
            var predicted = omega * (AcSampling.TICK_SECONDS / rate) / 2;
            Assertions.assertEquals(predicted, error, predicted * 0.15,
                    "At " + rate + " sub-ticks the lag error should be omega*dt/2 = "
                            + String.format("%.2f deg, got %.2f deg", Math.toDegrees(predicted),
                                    Math.toDegrees(error)));
        }
    }
}
