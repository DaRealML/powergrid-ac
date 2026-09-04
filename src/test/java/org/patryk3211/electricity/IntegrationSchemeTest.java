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
import org.patryk3211.powergrid.electricity.sim.node.ITimeAwareWire;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * What the integration scheme costs in accuracy, measured rather than asserted.
 *
 * <h2>Why this is separate from {@link MotorReactanceTest}</h2>
 * That one measures {@code rmsVoltage()/rmsCurrent()}, which is a magnitude and nothing else. The
 * magnitude was always the part backward Euler got nearly right. The damage was in the
 * <em>phase</em>, which a ratio of two RMS accumulators cannot see at all, and which decides power
 * factor, real power, and how much torque an alternator has to take off its shaft to feed a motor.
 * An accuracy problem that only shows in the phase is exactly the kind that hides in a green suite.
 *
 * <h2>What these numbers used to be</h2>
 * Under backward Euler, at sixteen pole pairs and the sub-tick ceiling of the day, this coil lagged
 * by 40.3 degrees where 77.6 is correct. The scheme's damping appeared as a fictitious series
 * resistance — the branch's real part read 95.6 ohms against a true 25.6 — so the source delivered
 * 175.3 W into a coil that turned 47.0 W into heat, and 73% of the power the grid paid for was
 * absorbed by the integration. Those figures are what motivated moving to the theta-method; they
 * are recorded here because this file is where someone will come looking for them.
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

    /** What the shipped config asks for, and the ceiling that grants or denies it. */
    private static final int SAMPLES_PER_CYCLE = 32;
    private static final int MAX_SUB_TICKS = 32;

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

    private static double exactPhase() {
        return Math.atan2(AcSampling.TWO_PI * TOP_FREQUENCY * L, R);
    }

    private static double exactMagnitude() {
        return Math.hypot(R, AcSampling.TWO_PI * TOP_FREQUENCY * L);
    }

    @Test
    void theCoilReadsItsTruePhaseAtTheShippedCeiling() {
        var subTicks = AcSampling.subTicksFor(TOP_FREQUENCY, SAMPLES_PER_CYCLE, MAX_SUB_TICKS);
        Assertions.assertEquals(MAX_SUB_TICKS, subTicks,
                "Sixteen pole pairs should be sampled at the shipped ceiling");

        var r = measure(TOP_FREQUENCY, subTicks);
        var measured = Math.toDegrees(r.phase());
        var exact = Math.toDegrees(exactPhase());

        // 76.1 against 77.6. Backward Euler read 40.3 here, so this is the single number that says
        // whether the scheme change is still in place.
        Assertions.assertEquals(exact, measured, 2.5,
                "The coil should read close to its true phase, got "
                        + String.format("%.2f against %.2f", measured, exact));

        // The magnitude, which was never the problem, must not have been traded away for the
        // phase: any theta below 1 warps the frequency slightly and inflates the reactance.
        Assertions.assertEquals(exactMagnitude(), r.magnitude(), exactMagnitude() * 0.08,
                "The impedance magnitude should still be within a few percent, got "
                        + r.magnitude() + " against " + exactMagnitude());
    }

    @Test
    void theSchemeNoLongerFabricatesPower() {
        // The consequence of the phase error, and the one a player can feel: a dissipative scheme
        // charges the grid — alternator torque, fuel, wire heating — for power the load never
        // receives. Backward Euler delivered 3.39 times the true real power here and absorbed 73%
        // of it internally.
        var r = measure(TOP_FREQUENCY, MAX_SUB_TICKS);

        var exactCurrent = r.vPeak() / exactMagnitude();
        var exactPower = 0.5 * exactCurrent * exactCurrent * R;
        var ratio = r.sourcePower() / exactPower;
        Assertions.assertEquals(1.0, ratio, 0.20,
                "The grid should deliver about the true real power, got "
                        + String.format("%.2fx", ratio));

        // Still slightly dissipative, because theta sits above one half on purpose. What matters
        // is that the phantom share is a small correction rather than most of the bill.
        var phantom = (r.sourcePower() - r.coilHeat()) / r.sourcePower();
        Assertions.assertTrue(phantom < 0.45,
                "The scheme should absorb only a small share of the delivered power, got "
                        + String.format("%.0f%%", 100 * phantom));
    }

    @Test
    void thePhaseErrorScalesWithTheSampleInterval() {
        // The theta-method lags a reactive branch by (theta - 1/2)*omega*dt to leading order, so
        // the error is set by the sample interval and by how far theta sits above one half.
        // Backward Euler is theta = 1 and therefore carries the full omega*dt/2 — ten times this.
        //
        // The leading-order figure is an upper bound rather than a prediction: at omega*dt near
        // 0.7 radians the higher terms take a third off it. Both facts are asserted, because a
        // measured error ABOVE the first-order bound would mean theta is not what it should be.
        var exact = exactPhase();
        var coarse = measure(TOP_FREQUENCY, MAX_SUB_TICKS);
        var fine = measure(TOP_FREQUENCY, MAX_SUB_TICKS * 4);

        var coarseError = Math.abs(exact - coarse.phase());
        var fineError = Math.abs(exact - fine.phase());

        var leadingOrder = (ITimeAwareWire.DEFAULT_THETA - 0.5) * AcSampling.TWO_PI * TOP_FREQUENCY
                * (AcSampling.TICK_SECONDS / MAX_SUB_TICKS);
        Assertions.assertTrue(coarseError > 0 && coarseError < leadingOrder,
                "The lag error should be positive and under the first-order bound of "
                        + String.format("%.2f deg, got %.2f deg", Math.toDegrees(leadingOrder),
                                Math.toDegrees(coarseError)));

        // First order in dt, so four times the sampling should take roughly four times off it.
        Assertions.assertTrue(fineError < coarseError / 3,
                "Four times the sampling should cut the error by about four, got "
                        + String.format("%.2f deg -> %.2f deg", Math.toDegrees(coarseError),
                                Math.toDegrees(fineError)));
    }
}
