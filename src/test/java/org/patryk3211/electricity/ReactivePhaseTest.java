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
 * These pin both halves of that: the reading that is legitimately in phase, and the divider that
 * produces the textbook 45 degrees. If the second ever stops holding, the components really are
 * broken.
 */
public class ReactivePhaseTest extends TestHelper {
    private static final double FREQUENCY = 20;
    private static final double OMEGA = 2 * Math.PI * FREQUENCY;
    private static final int SUB_TICKS = 64;
    private static final double SAMPLE_RATE = 20 * SUB_TICKS;
    private static final double AMPLITUDE = 10;

    /** Reactance the dividers are tuned to, so both give exactly 45 degrees. */
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
    void capacitorStraightAcrossTheSourceIsInPhaseAndShouldBe() {
        // The reading that gets reported as a bug. Same node pair as the supply, so there is
        // nothing for a phase shift to appear across.
        var rig = new Rig();
        var capacitance = 1 / (OMEGA * REACTANCE);
        var c = new CRSeriesWire(capacitance, 0.01f, rig.terminal, rig.ground);
        rig.net.network.addWire(c);

        var phase = relativePhase(rig.capture(rig.terminal, rig.terminal, 40, 4));
        Assertions.assertEquals(0, phase, 1,
                "A capacitor across the supply cannot shift the supply's own phase");

        // The shift is in the CURRENT, not the voltage, so the part is doing its job.
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
        Assertions.assertEquals(-45, relativePhase(captured), 4,
                "An RC divider with R = Xc should put the midpoint 45 degrees behind");
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
        Assertions.assertEquals(45, relativePhase(captured), 4,
                "An RL divider with R = Xl should put the midpoint 45 degrees ahead");
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

        Assertions.assertEquals(1 / Math.sqrt(2), midpoint / supply, 0.05,
                "A reactive divider with R = Xc should give 1/sqrt(2), not 1/2");
    }
}
