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
 * The motor coil, which used to be a plain resistor and is now an LR branch.
 *
 * <h2>What a resistor could not do</h2>
 * A resistor draws current exactly in phase with the voltage across it, so a motor modelled as
 * one presents the same impedance at every frequency and reports a power factor of 1 whatever it
 * is plugged into. Real machine windings are dominated by their inductance: they present
 * {@code |Z| = sqrt(R^2 + (omega*L)^2)}, draw a lagging current, and get worse as frequency
 * rises. That is most of what makes alternating supplies interesting to design around, and none
 * of it existed for motors.
 *
 * <p>These drive an LR branch built the way {@code ElectricMotorBlockEntity.buildCircuit} builds
 * it — inductance derived from the nominal resistance through the configured time constant — and
 * check the electrical claims against theory. The block entity itself needs Minecraft and is not
 * exercised here; what is exercised is the branch it constructs.
 */
public class MotorReactanceTest extends TestHelper {
    /** One electrical cycle per world tick, so the per-tick RMS accumulators see whole cycles. */
    private static final double FREQUENCY = 20;
    private static final double OMEGA = 2 * Math.PI * FREQUENCY;
    private static final int SUB_TICKS = 64;

    private static final double AMPLITUDE = 10;
    private static final float SOURCE_RESISTANCE = 0.001f;

    /** Nominal motor resistance for these tests. */
    private static final double R = 10;

    /** The shipped default: same electrical time constant as the generator winding. */
    private static final double TAU = 0.01;

    /** Reactance at the test frequency, for readability below. */
    private static final double X = OMEGA * (R * TAU);

    private static class Rig {
        final Network net = new Network();
        final FloatingNode terminal = new FloatingNode();
        final FloatingNode ground;

        Rig(double frequency) {
            var source = new ACVoltageSourceCoupling(terminal, null, SOURCE_RESISTANCE,
                    (float) AMPLITUDE, (float) frequency);
            net.network.addNode(terminal);
            net.network.addNode(source);
            ground = net.N();
            net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        }

        LRSeriesWire coil(double resistance, double timeConstant) {
            var wire = new LRSeriesWire(resistance * timeConstant, resistance, terminal, ground);
            net.network.addWire(wire);
            return wire;
        }

        void run(int ticks) {
            run(ticks, SUB_TICKS);
        }

        void run(int ticks, int subTicks) {
            for(int i = 0; i < ticks; ++i)
                net.network.calculate(subTicks);
        }
    }

    @Test
    void motorCoilPresentsInductiveReactance() {
        var rig = new Rig(FREQUENCY);
        var coil = rig.coil(R, TAU);
        rig.run(60);

        // |Z| = sqrt(R^2 + X^2), not R. With the default time constant at 20 Hz that is a
        // roughly 60% increase over the resistance alone, which is not a subtlety.
        var expected = Math.hypot(R, X);
        Assertions.assertEquals(expected, coil.rmsVoltage() / coil.rmsCurrent(), expected * 0.05,
                "Motor coil impedance should be sqrt(R^2 + (omega*L)^2)");
        Assertions.assertTrue(expected > R * 1.5,
                "The chosen test point should make the reactance clearly visible");
    }

    @Test
    void motorPowerFactorLagsUnderAc() {
        var rig = new Rig(FREQUENCY);
        var coil = rig.coil(R, TAU);
        rig.run(60);

        // cos(phi) = R / |Z|. A pure resistor reports 1; anything less is the whole point.
        var expected = R / Math.hypot(R, X);
        Assertions.assertEquals(expected, coil.powerFactor(), 0.05,
                "Power factor should be R/|Z| for a series LR branch");
        Assertions.assertTrue(coil.powerFactor() < 0.9,
                "A motor on an alternating supply should not look purely resistive");
    }

    @Test
    void reactanceRisesWithFrequency() {
        // The property that separates an inductor from a resistor, and the reason a motor's
        // behaviour now depends on what it is plugged into.
        var low = new Rig(FREQUENCY / 2);
        var lowCoil = low.coil(R, TAU);
        low.run(60);

        var high = new Rig(FREQUENCY);
        var highCoil = high.coil(R, TAU);
        high.run(60);

        var lowZ = lowCoil.rmsVoltage() / lowCoil.rmsCurrent();
        var highZ = highCoil.rmsVoltage() / highCoil.rmsCurrent();
        Assertions.assertTrue(highZ > lowZ * 1.1,
                "Impedance should rise with frequency, got " + lowZ + " -> " + highZ);

        // Reactance itself, extracted from the impedance, should double with frequency.
        var lowX = Math.sqrt(lowZ * lowZ - R * R);
        var highX = Math.sqrt(highZ * highZ - R * R);
        Assertions.assertEquals(2.0, highX / lowX, 0.15,
                "Doubling frequency should double the reactance");
    }

    @Test
    void steadyDirectCurrentIsUnchanged() {
        // The non-regression that matters: an inductor is a short at DC, so every existing
        // direct-current build must draw exactly what it drew when the coil was a resistor.
        var net = new Network();
        var supply = net.V(10);
        var ground = net.V(0);
        var coil = new LRSeriesWire(R * TAU, R, supply, ground);
        net.network.addWire(coil);

        for(int i = 0; i < 20; ++i)
            net.network.calculate(1);

        // Not an equality, and the reason is worth stating. Every reactive component in the mod
        // carries ITimeAwareWire's per-step leakage (0.99999 per 50 ms), so the fixed point of
        // the LR companion model sits at V/R * (1 - rs)/(1 - leak*rs) where rs = L/(L + R*dt) --
        // about two parts per million here. Set leakage to 1 and the algebra gives exactly V/R.
        // Two ppm on motor speed is some three orders of magnitude below the nearest integer
        // RPM, so nothing in a world can see it, but the tolerance has to admit it.
        Assertions.assertEquals(10.0 / R, coil.current(), 1e-5,
                "Steady DC current through the coil should still be V/R to within the leakage floor");
    }

    @Test
    void reactanceDegradesAtTheShippedSubTickCeiling() {
        // Everything above runs at SUB_TICKS = 64, which the shipped configuration cannot
        // produce. acSamplesPerCycle defaults to 32 and acMaxSubTicks to 16, so
        // AcSampling.subTicksFor(20, 32, 16) caps this rig at 16 -- sixteen samples per cycle,
        // not sixty-four. Validating a model only at a sampling density the game never reaches
        // is how an accuracy problem hides in a green suite, so the degraded figure is pinned
        // here instead.
        var subTicks = AcSampling.subTicksFor(FREQUENCY, 32, 16);
        Assertions.assertEquals(16, subTicks,
                "Shipped acSamplesPerCycle/acMaxSubTicks should cap this rig at 16 sub-ticks");

        var rig = new Rig(FREQUENCY);
        var coil = rig.coil(R, TAU);
        rig.run(60, subTicks);

        // Every scheme overstates the impedance as the sampling coarsens, and the exact answer
        // here is 16.06. Backward Euler used to read about 17.5, some 9% high; the theta-method
        // reads 1.8% high at the same sixteen samples per cycle. Asserted as a BAND rather than a
        // bound so that a further change to the integration fails this test and has to be
        // acknowledged, rather than silently loosening what the suite claims.
        var measured = coil.rmsVoltage() / coil.rmsCurrent();
        var exact = Math.hypot(R, X);
        var error = (measured - exact) / exact;
        Assertions.assertTrue(error > 0.005 && error < 0.05,
                "At the shipped ceiling the impedance should read 0.5-5% high, got "
                        + String.format("%.1f%%", error * 100));
    }

    @Test
    void zeroTimeConstantIsExactlyTheOldResistor() {
        // The config escape hatch has to be an exact restoration, not an approximation.
        var rig = new Rig(FREQUENCY);
        var coil = rig.coil(R, 0);
        rig.run(60);

        Assertions.assertEquals(R, coil.rmsVoltage() / coil.rmsCurrent(), R * 1e-3,
                "With no inductance the coil should be purely resistive");
        Assertions.assertEquals(1.0, coil.powerFactor(), 0.01,
                "A purely resistive coil should report unity power factor");
    }
}
