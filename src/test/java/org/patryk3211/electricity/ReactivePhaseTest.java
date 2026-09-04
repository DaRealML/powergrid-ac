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
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.CRSeriesWire;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterPhasor;

/**
 * Where the phase shift of a reactive component actually shows up in a <em>voltage</em>
 * measurement.
 *
 * <h2>The measurement that looks broken but is not</h2>
 * Probing straight across a capacitor that sits directly on a source measures the source: the two
 * are the same node pair, so the trace is in phase with the supply by definition and no amount of
 * capacitance changes that. A capacitor shifts the phase of the <em>current</em> through it
 * relative to the voltage across it, and that only becomes a voltage phase shift once something
 * else in the loop drops part of the supply — a series resistance, another reactance, a load.
 * <p>
 * These pin both halves of that: the quarter-cycle lead in the capacitor's own current, and the
 * dividers where a voltage shift can genuinely appear.
 *
 * <h2>Why these are not asserted against 90 and 45</h2>
 * A discrete scheme does not reproduce the continuous phase exactly, and a band wide enough to
 * admit the textbook figure is also wide enough to admit a sub-tick rate an octave wrong. So the
 * expectation is the phase the scheme in force actually produces, derived rather than measured:
 * the theta-method's residual shift on a single reactive element is exactly
 * {@code (theta - 1/2) * omega * dt}, which is {@link #schemeShiftDegrees()}.
 * <p>
 * That shift used to be 2.81 degrees, because backward Euler is {@code theta = 1} and the
 * coefficient was the full one half. At the shipped 0.55 it is 0.28 degrees — a tenth — so these
 * now sit within a third of a degree of the continuous answer, and the assertions have been
 * tightened to match rather than left loose enough to hide the difference.
 */
public class ReactivePhaseTest extends TestHelper {
    private static final double FREQUENCY = 20;
    private static final double OMEGA = 2 * Math.PI * FREQUENCY;
    private static final int SUB_TICKS = 64;

    /**
     * Phase error the integration leaves on one reactive element, in degrees.
     * <p>
     * {@code (theta - 1/2) * omega * dt} — zero for the trapezoidal rule, {@code omega*dt/2} for
     * backward Euler. Derived from the theta actually in force so that retuning the scheme moves
     * these expectations with it instead of failing them for the wrong reason.
     */
    private static double schemeShiftDegrees() {
        var dt = 0.05 / SUB_TICKS;
        return Math.toDegrees((ITimeAwareWire.DEFAULT_THETA - 0.5) * OMEGA * dt);
    }
    private static final double SAMPLE_RATE = 20 * SUB_TICKS;
    private static final double AMPLITUDE = 10;

    /** Reactance the dividers are tuned to, R = X, which is the 45-degree point. */
    private static final double REACTANCE = 10;

    private static class Rig {
        final Network net = new Network();
        final FloatingNode terminal = new FloatingNode();
        final FloatingNode ground;

        Rig() {
            var source = new ACVoltageSourceCoupling(terminal, null, 0.001f,
                    (float) AMPLITUDE, (float) FREQUENCY);
            net.network.addNode(terminal);
            net.network.addNode(source);
            ground = net.N();
            net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        }

        /** Run for {@code settle} ticks, then capture every sub-tick voltage of two nodes. */
        float[][] capture(IElectricNode a, IElectricNode b, int settle, int ticks) {
            for(int i = 0; i < settle; ++i)
                net.network.calculate(SUB_TICKS);

            var first = new float[ticks * SUB_TICKS];
            var second = new float[ticks * SUB_TICKS];
            var n = 0;
            for(int t = 0; t < ticks; ++t) {
                net.network.prepare(SUB_TICKS);
                for(int i = 0; i < SUB_TICKS; ++i) {
                    net.network.singleTick();
                    first[n] = (float) a.getVoltage();
                    second[n] = (float) b.getVoltage();
                    ++n;
                }
            }
            return new float[][] { first, second };
        }
    }

    /** Phase of the second capture relative to the first, in degrees, wrapped to (-180, 180]. */
    private static double relativePhase(float[][] captured) {
        var a = MultimeterPhasor.goertzel(captured[0], FREQUENCY, SAMPLE_RATE);
        var b = MultimeterPhasor.goertzel(captured[1], FREQUENCY, SAMPLE_RATE);
        var d = b.phaseDegrees() - a.phaseDegrees();
        while(d <= -180) d += 360;
        while(d > 180) d -= 360;
        return d;
    }

    @Test
    void theShiftIsInTheCurrentNotTheVoltageAcrossTheCapacitor() {
        // This test used to compare rig.terminal against rig.terminal and assert the phase
        // difference was zero. Those two capture arrays are bit-identical, so the difference was
        // exactly 0.0 by construction: it passed with the capacitor deleted and the source dead.
        // A long comment about physics sat above an assertEquals(x, x).
        //
        // The claim that comment was making is real and is testable — just not against the
        // voltage, which genuinely cannot shift. It is the CURRENT through the capacitor that
        // leads, by a quarter cycle.
        var rig = new Rig();
        var capacitance = 1 / (OMEGA * REACTANCE);
        var c = new CRSeriesWire(capacitance, 0.01f, rig.terminal, rig.ground);
        rig.net.network.addWire(c);

        for(int i = 0; i < 40; ++i)
            rig.net.network.calculate(SUB_TICKS);

        var voltage = new float[4 * SUB_TICKS];
        var current = new float[4 * SUB_TICKS];
        var n = 0;
        for(int t = 0; t < 4; ++t) {
            rig.net.network.prepare(SUB_TICKS);
            for(int i = 0; i < SUB_TICKS; ++i) {
                rig.net.network.singleTick();
                voltage[n] = (float) rig.terminal.getVoltage();
                current[n] = (float) c.current();
                ++n;
            }
        }

        var v = MultimeterPhasor.goertzel(voltage, FREQUENCY, SAMPLE_RATE);
        var i = MultimeterPhasor.goertzel(current, FREQUENCY, SAMPLE_RATE);
        var lead = i.phaseDegrees() - v.phaseDegrees();
        while(lead <= -180) lead += 360;
        while(lead > 180) lead -= 360;

        // The continuous answer less the scheme's own shift: 89.72 degrees rather than 90 at
        // this sub-tick count, where backward Euler gave 87.19.
        Assertions.assertEquals(90 - schemeShiftDegrees(), lead, 0.3,
                "Capacitor current should lead its voltage by a quarter cycle");
        Assertions.assertTrue(c.rmsCurrent() > 0, "The capacitor should be conducting");
    }

    @Test
    void rcDividerLagsBy45Degrees() {
        // R = Xc puts the midpoint exactly 45 degrees behind the supply.
        var rig = new Rig();
        var mid = rig.net.N();
        rig.net.W((float) REACTANCE, rig.terminal, mid);
        var capacitance = 1 / (OMEGA * REACTANCE);
        rig.net.network.addWire(new CRSeriesWire(capacitance, 0.001f, mid, rig.ground));

        var captured = rig.capture(rig.terminal, mid, 40, 4);
        // Not quite -45, for the same reason. A divider carries about half the single-element
        // shift, because the resistor contributes none of it -- which is why the tolerance here is
        // the shift itself rather than a fraction of it.
        Assertions.assertEquals(-45 + schemeShiftDegrees() / 2, relativePhase(captured),
                Math.max(0.2, schemeShiftDegrees()),
                "An RC divider with R = Xc should put the midpoint 43.58 degrees behind");
    }

    @Test
    void rlDividerLeadsBy45Degrees() {
        // The inductive mirror image: the midpoint of an RL divider runs ahead of the supply.
        var rig = new Rig();
        var mid = rig.net.N();
        rig.net.W((float) REACTANCE, rig.terminal, mid);
        var inductance = REACTANCE / OMEGA;
        rig.net.network.addWire(new LRSeriesWire(inductance, 0.001f, mid, rig.ground));

        var captured = rig.capture(rig.terminal, mid, 40, 4);
        Assertions.assertEquals(45 - schemeShiftDegrees() / 2, relativePhase(captured),
                Math.max(0.2, schemeShiftDegrees()),
                "An RL divider with R = Xl should put the midpoint just under 45 degrees ahead");
    }

    @Test
    void dividerMidpointSitsAtOneOverRootTwo() {
        // Confirms the divider is genuinely reactive rather than merely phase-shifted: with
        // R = Xc the magnitude must also fall to 1/sqrt(2), not to one half as two equal
        // resistors would give.
        var rig = new Rig();
        var mid = rig.net.N();
        rig.net.W((float) REACTANCE, rig.terminal, mid);
        var capacitance = 1 / (OMEGA * REACTANCE);
        rig.net.network.addWire(new CRSeriesWire(capacitance, 0.001f, mid, rig.ground));

        var captured = rig.capture(rig.terminal, mid, 40, 4);
        var supply = MultimeterPhasor.goertzel(captured[0], FREQUENCY, SAMPLE_RATE).magnitude();
        var midpoint = MultimeterPhasor.goertzel(captured[1], FREQUENCY, SAMPLE_RATE).magnitude();

        // 0.705 against a continuous 0.7071, where backward Euler gave 0.6905: the magnitude
        // error follows the same shift and is small once the scheme is not backward Euler. Still
        // decisively distinguishes a reactive divider from the 0.5 two equal resistors give.
        Assertions.assertEquals(1 / Math.sqrt(2), midpoint / supply, 0.01,
                "A reactive divider with R = Xc should give ~1/sqrt(2), not 1/2");
    }
}
