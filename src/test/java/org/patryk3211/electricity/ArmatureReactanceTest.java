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
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;

/**
 * The alternator's own armature reactance, which nothing used to exercise.
 *
 * <h2>Why this file exists</h2>
 * {@code armatureInductance} defaults to zero and is only ever set from
 * {@code CommutatorBlockEntity}, so every test in the suite — {@link AlternatorTest} included —
 * ran the machine with no reactance at all. The companion model in the source row was therefore
 * never executed by a test, and it was wrong: the history term was stamped with a plus where the
 * row's sign convention requires a minus, so the two terms added instead of cancelling and the
 * machine presented an internal impedance of roughly {@code 2L/dt}. That is purely
 * resistive-looking and, worse, **proportional to the sub-tick rate** — into a 2 Ω load at one
 * pole pair it delivered 1.36 A at 16 sub-ticks and 0.049 A at 512, against an analytic 9.64 A.
 *
 * <h2>What is asserted</h2>
 * The first test is the one that would have caught it, and it needs no reference solution and no
 * phasor algebra: <em>the current a machine delivers cannot depend on how finely the solver is
 * stepping.</em> That is a property of the physics, so a scheme that violates it is wrong however
 * plausible its arithmetic looks.
 */
public class ArmatureReactanceTest extends TestHelper {
    private static final float FIELD = 1.0f;
    private static final float BASE_RESISTANCE = 0.01f;
    private static final float LOAD = 2.0f;

    /** The shipped {@code solver.acArmatureInductance}. */
    private static final double ARMATURE_L = 0.02;

    /** The alternator's top shaft speed. */
    private static final float TOP_RPM = 272;

    /** A shaft held at a fixed speed, which is all these tests need from the kinetics. */
    private static class FixedRotor implements IRotor {
        private final float rpm;

        FixedRotor(float rpm) {
            this.rpm = rpm;
        }

        @Override
        public float getInertia() {
            return 1.0f;
        }

        @Override
        public float getAngularVelocity() {
            return rpm;
        }

        @Override
        public void applyTickForce(float force) {
        }
    }

    private static class Rig {
        final Network net = new Network();
        final AlternatorCoupling alternator;
        final ElectricWire load;
        final double frequency;

        Rig(int polePairs, double inductance) {
            var ground = net.N();
            net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
            var terminal = new FloatingNode();
            alternator = new AlternatorCoupling(terminal, null, BASE_RESISTANCE, new FixedRotor(TOP_RPM));
            alternator.setField(FIELD);
            alternator.setPolePairs(polePairs);
            alternator.setArmatureInductance(inductance);
            net.network.addNode(terminal);
            net.network.addNode(alternator);
            load = new ElectricWire(LOAD, terminal, ground);
            net.network.addWire(load);

            frequency = polePairs * (TOP_RPM * Math.PI / 30) / AcSampling.TWO_PI;
        }

        /** RMS load current over forty electrical cycles, after the transient has gone. */
        double settledRmsCurrent(int subTicks) {
            for(int t = 0; t < 200; ++t)
                net.network.calculate(subTicks);

            var dt = AcSampling.TICK_SECONDS / subTicks;
            var n = (int) Math.ceil(40 / (frequency * dt));
            var sumSquares = 0.0;
            var k = 0;
            while(k < n) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks && k < n; ++s, ++k) {
                    net.network.singleTick();
                    var i = load.current();
                    sumSquares += i * i;
                }
            }
            return Math.sqrt(sumSquares / n);
        }
    }

    @Test
    void deliveredCurrentDoesNotDependOnTheSubTickRate() {
        // The property that would have caught the original bug, and the reason it is first: it
        // needs no analytic reference at all. How much current a machine pushes into a resistor is
        // physics; the sub-tick rate is an implementation detail of the solver. If changing one
        // moves the other, the companion model is wrong whatever its algebra looks like.
        var coarse = new Rig(1, ARMATURE_L).settledRmsCurrent(16);
        var fine = new Rig(1, ARMATURE_L).settledRmsCurrent(512);

        Assertions.assertEquals(coarse, fine, coarse * 0.05,
                "Delivered current must not track the solver's step count, got "
                        + String.format("%.4f A at 16 sub-ticks and %.4f A at 512", coarse, fine));
    }

    @Test
    void theMachinePresentsItsAnalyticInternalImpedance() {
        // Now against theory. The load current is EMF / |R_armature + jwL + R_load|, and the EMF is
        // whatever the machine makes at this field and speed -- taken from the same run so the
        // test does not also become an assertion about the EMF constant.
        for(var polePairs : new int[] { 1, 4, 16 }) {
            var rig = new Rig(polePairs, ARMATURE_L);
            var measured = rig.settledRmsCurrent(512);

            var omega = AcSampling.TWO_PI * rig.frequency;
            var openCircuit = new Rig(polePairs, ARMATURE_L);
            var emfPeak = peakEmf(openCircuit, 512);
            var expected = emfPeak / Math.sqrt(2)
                    / Math.hypot(BASE_RESISTANCE + LOAD, omega * ARMATURE_L);

            Assertions.assertEquals(expected, measured, expected * 0.05,
                    "At " + polePairs + " pole pairs the machine should deliver "
                            + String.format("%.4f A, got %.4f", expected, measured));
        }
    }

    @Test
    void reactanceThrottlesTheMachineAsFrequencyRises() {
        // The behaviour the inductance exists for. A synchronous machine cannot deliver unlimited
        // current as it speeds up, because its own reactance rises with frequency: sixteen pole
        // pairs is sixteen times the electrical frequency of one, so 2*pi*f*L goes from well under
        // the load resistance to several times it.
        var slow = new Rig(1, ARMATURE_L).settledRmsCurrent(512);
        var fast = new Rig(16, ARMATURE_L).settledRmsCurrent(512);
        Assertions.assertTrue(fast < slow / 3,
                "Sixteen pole pairs should be throttled well below one, got "
                        + String.format("%.3f A against %.3f A", fast, slow));

        // The control. With no armature inductance the machine is a voltage source behind a
        // resistance, so the current is the same at any speed -- which is exactly the behaviour
        // the broken companion model was hiding behind.
        var slowIdeal = new Rig(1, 0).settledRmsCurrent(512);
        var fastIdeal = new Rig(16, 0).settledRmsCurrent(512);
        Assertions.assertEquals(slowIdeal, fastIdeal, slowIdeal * 0.02,
                "With no reactance the delivered current should not vary with speed");
    }

    private static double peakEmf(Rig rig, int subTicks) {
        for(int t = 0; t < 200; ++t)
            rig.net.network.calculate(subTicks);
        var peak = 0.0;
        for(int t = 0; t < 40; ++t) {
            rig.net.network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s) {
                rig.net.network.singleTick();
                peak = Math.max(peak, Math.abs(rig.alternator.getVoltage()));
            }
        }
        return peak;
    }
}
