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
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * Coils, and why their thresholds are compared against a settled magnitude.
 *
 * <h2>The defect</h2>
 * A relay released its armature whenever {@code |i| < dropOutCurrent}, tested every solver
 * sub-tick. A sinusoidal coil current passes through zero twice per cycle <em>whatever its
 * amplitude</em>, so that condition was entered every half cycle even with the coil driven hard —
 * a relay buzzing at twice supply frequency instead of holding in. The contactor ran the same rule
 * once per world tick, which aliases a 4.5–72.5 Hz waveform at 20 Hz. A fuse compared its rating
 * against the peak and so blew an AC circuit at 0.707 of nameplate.
 *
 * <p>Real hardware is held in by the coil's inductance and the armature's inertia.
 * {@code lastRmsCurrent()} is the electrical half of that.
 */
public class CoilThresholdTest extends TestHelper {
    private static final int SUB_TICKS = 64;
    private static final double AMPLITUDE = 24;

    /** A contactor-ish coil: 12 ohms, the shipped coil time constant. */
    private static final double R = 12;
    private static final double TAU = 0.01;

    private static LRSeriesWire coilOn(double frequency, double amplitude) {
        var net = new Network();
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f,
                (float) amplitude, (float) frequency);
        net.network.addNode(terminal);
        net.network.addNode(source);
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var coil = new LRSeriesWire(R * TAU, R, terminal, ground);
        net.network.addWire(coil);
        for(int i = 0; i < 60; ++i)
            net.network.calculate(SUB_TICKS);
        return coil;
    }

    @Test
    void theInstantaneousCurrentCrossesADropOutThresholdThatTheSettledOneNeverDoes() {
        var coil = coilOn(20, AMPLITUDE);
        var net = coil.getNetwork();

        // A drop-out threshold at 90% of the settled magnitude, which is the shipped
        // holdingCurrentPercent. A coil driven this hard must never release.
        var dropOut = coil.lastRmsCurrent() * 0.9;
        Assertions.assertTrue(dropOut > 0, "The coil should be carrying current");

        var instantaneousBelow = 0;
        var settledBelow = 0;
        for(int t = 0; t < 20; ++t) {
            net.prepare(SUB_TICKS);
            for(int i = 0; i < SUB_TICKS; ++i) {
                net.singleTick();
                if(Math.abs(coil.current()) < dropOut)
                    ++instantaneousBelow;
                if(coil.lastRmsCurrent() < dropOut)
                    ++settledBelow;
            }
        }

        Assertions.assertTrue(instantaneousBelow > 200,
                "The old rule should fall below the threshold constantly, got " + instantaneousBelow
                        + " of " + (20 * SUB_TICKS) + " sub-ticks");
        Assertions.assertEquals(0, settledBelow,
                "The settled magnitude must never fall below the threshold on a steady supply");
    }

    @Test
    void theSettledMagnitudeIsSteadyWhereOneTicksRmsIsNot() {
        // Why the settled magnitude is filtered across ticks rather than being one tick's RMS.
        // A world tick spans a whole number of cycles only at 20, 40 and 60 Hz; at 4.53 Hz it
        // covers 0.23 of one, so a single tick's RMS swings about 2.4 to 1 with where the tick
        // fell -- which would still cross a drop-out threshold set at 0.9 of nominal.
        var coil = coilOn(4.53, AMPLITUDE);
        var net = coil.getNetwork();

        var min = Double.POSITIVE_INFINITY;
        var max = 0.0;
        var rawMin = Double.POSITIVE_INFINITY;
        var rawMax = 0.0;
        for(int t = 0; t < 60; ++t) {
            net.calculate(SUB_TICKS);
            min = Math.min(min, coil.lastRmsCurrent());
            max = Math.max(max, coil.lastRmsCurrent());
            rawMin = Math.min(rawMin, coil.rmsCurrent());
            rawMax = Math.max(rawMax, coil.rmsCurrent());
        }

        Assertions.assertTrue(rawMax / rawMin > 1.5,
                "One tick's RMS should be visibly unstable at this frequency, got "
                        + (rawMax / rawMin) + ":1");
        Assertions.assertTrue(max / min < 1.15,
                "The settled magnitude must be steady enough to hold a 0.9 threshold, got "
                        + (max / min) + ":1");
    }

    @Test
    void aSteadySupplyIsUnchanged() {
        // The non-regression. On DC there are no sub-ticks, the filter is bypassed entirely, and
        // lastRmsCurrent() is exactly the magnitude of the instantaneous current -- so every
        // threshold in the mod behaves as it always did.
        var net = new Network();
        var supply = net.V(24);
        var ground = net.V(0);
        var coil = new LRSeriesWire(R * TAU, R, supply, ground);
        net.network.addWire(coil);
        for(int i = 0; i < 20; ++i)
            net.network.calculate(1);

        Assertions.assertEquals(Math.abs(coil.current()), coil.lastRmsCurrent(), 1e-12,
                "On a steady supply the settled magnitude is the instantaneous magnitude");
        Assertions.assertEquals(24.0 / R, coil.lastRmsCurrent(), 1e-4,
                "A 12 ohm coil on 24 V should settle at 2 A");
    }

    @Test
    void aCoilPresentsReactanceWhereAResistorWouldNot() {
        // 20 and 40 Hz, not the alternator's 4.53 and 72.53. rmsVoltage()/rmsCurrent() are
        // accumulated over ONE world tick, so their ratio is |Z| only when a tick spans a whole
        // number of cycles -- true at 20, 40 and 60 Hz and nowhere else. Measured at 4.53 Hz the
        // same expression returns 9.66 against a true 12.48, because it divides the RMS of one
        // part of a cycle by the RMS of another.
        var low = coilOn(20, AMPLITUDE);
        var high = coilOn(40, AMPLITUDE);

        var lowZ = low.rmsVoltage() / low.rmsCurrent();
        var highZ = high.rmsVoltage() / high.rmsCurrent();

        var expectLow = Math.hypot(R, 2 * Math.PI * 20 * R * TAU);
        var expectHigh = Math.hypot(R, 2 * Math.PI * 40 * R * TAU);
        Assertions.assertEquals(expectLow, lowZ, expectLow * 0.10,
                "A coil at 20 Hz should present sqrt(R^2 + (omega*L)^2)");
        Assertions.assertEquals(expectHigh, highZ, expectHigh * 0.10,
                "A coil at 40 Hz should present sqrt(R^2 + (omega*L)^2)");
        Assertions.assertTrue(highZ > lowZ * 1.4,
                "Impedance should rise substantially with frequency, got " + lowZ + " -> " + highZ);

        // The control: with no inductance it is exactly its resistance at any frequency, which is
        // what every one of these coils was before connectCoil.
        var resistor = new LRSeriesWire(0, R, low.getNode1(), low.getNode2());
        Assertions.assertEquals(1 / R, resistor.conductance(), 1e-9,
                "With no inductance a coil is exactly its resistance");
    }
}
