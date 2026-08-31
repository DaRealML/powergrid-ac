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
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.special.InductorWire;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;

/**
 * Tests for the alternating-current source.
 * <p>
 * These run the real solver with no Minecraft present, the same way the existing electrical
 * tests do, so the numbers below come out of the actual MNA path rather than a mock.
 */
public class AlternatorTest extends TestHelper {
    /** A rotor spinning at a fixed speed, recording whatever torque is fed back to it. */
    private static class FixedRotor implements IRotor {
        private final float rpm;
        private final float inertia;
        float appliedForce = 0;

        FixedRotor(float rpm, float inertia) {
            this.rpm = rpm;
            this.inertia = inertia;
        }

        @Override
        public float getInertia() {
            return inertia;
        }

        @Override
        public float getAngularVelocity() {
            return rpm;
        }

        @Override
        public void applyTickForce(float force) {
            appliedForce += force;
        }
    }

    /** Build an alternator driving a single resistive load referenced to ground. */
    private static class Rig {
        final Network net = new Network();
        final AlternatorCoupling alternator;
        final ElectricWire load;
        final FixedRotor rotor;
        /** Exposed so a test can hang a second, reactive load on the same machine. */
        final FloatingNode terminal;
        final IElectricNode ground;

        Rig(float rpm, float field, int polePairs, float loadResistance) {
            rotor = new FixedRotor(rpm, 1.0f);
            ground = net.V(0);
            terminal = new FloatingNode();
            alternator = new AlternatorCoupling(terminal, null, 0.01f, rotor);
            alternator.setField(field);
            alternator.setPolePairs(polePairs);
            net.network.addNode(terminal);
            net.network.addNode(alternator);
            load = net.W(loadResistance, terminal, ground);
        }
    }

    // 60 rpm is one mechanical revolution per second, so with a single pole pair the
    // electrical frequency is exactly 1 Hz and one world second is exactly one cycle.
    private static final float RPM_1HZ = 60;
    private static final double OMEGA_1HZ = RPM_1HZ * Math.PI / 30;

    @Test
    void producesSineWave() {
        var rig = new Rig(RPM_1HZ, 1.0f, 1, 10f);
        var subTicks = 20;

        // One world second = 20 world ticks = one full electrical cycle.
        var samples = new double[20 * subTicks];
        var at = 0;
        for(int tick = 0; tick < 20; ++tick) {
            rig.net.network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s) {
                rig.net.network.singleTick();
                samples[at++] = rig.alternator.getVoltage();
            }
        }

        var peak = OMEGA_1HZ;  // field * omega, with field = 1
        var max = Double.NEGATIVE_INFINITY;
        var min = Double.POSITIVE_INFINITY;
        var sum = 0.0;
        for(var v : samples) {
            max = Math.max(max, v);
            min = Math.min(min, v);
            sum += v;
        }

        Assertions.assertEquals(peak, max, peak * 0.01, "Positive peak EMF is incorrect");
        Assertions.assertEquals(-peak, min, peak * 0.01, "Negative peak EMF is incorrect");
        Assertions.assertEquals(0, sum / samples.length, peak * 0.01, "Waveform is not centred on zero");
    }

    @Test
    void phaseAdvancesAtTheCorrectRate() {
        var rig = new Rig(RPM_1HZ, 1.0f, 1, 10f);
        var subTicks = 20;

        // A quarter of a second at 1 Hz is a quarter turn: theta should reach pi/2.
        for(int tick = 0; tick < 5; ++tick) {
            rig.net.network.calculate(subTicks);
        }

        // Tolerance is 1e-6 rather than 1e-9 because IRotor reports speed as a float, so omega
        // carries float precision into a double accumulator. A genuine rate error would be off
        // by a factor, not by 1e-8.
        Assertions.assertEquals(Math.PI / 2, rig.alternator.getPhase(), 1e-6,
                "Phase did not advance at omega * dt");
    }

    @Test
    void polePairsMultiplyElectricalFrequency() {
        // Four pole pairs turn one mechanical revolution into four electrical cycles, so after
        // a quarter mechanical turn the electrical angle has gone a full cycle and sin is back
        // to zero having passed through a whole period.
        var rig = new Rig(RPM_1HZ, 1.0f, 4, 10f);
        var subTicks = 32;

        var zeroCrossings = 0;
        var previous = 0.0;
        for(int tick = 0; tick < 20; ++tick) {
            rig.net.network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s) {
                rig.net.network.singleTick();
                var v = rig.alternator.getVoltage();
                if(previous != 0 && Math.signum(v) != Math.signum(previous))
                    ++zeroCrossings;
                previous = v;
            }
        }

        // Four cycles in one second, two zero crossings per cycle.
        Assertions.assertEquals(8, zeroCrossings, "Pole pairs did not scale electrical frequency");
    }

    @Test
    void rmsOfSineIsPeakOverRootTwo() {
        var subTicks = 64;

        // One world tick must be a whole number of electrical cycles, or the per-tick RMS
        // accumulators cover a partial period and the comparison is against nothing in
        // particular. 1200 rpm at one pole pair is 20 Hz: exactly one cycle per world tick.
        // (A 1 Hz rig used to be built and run for 20 ticks here before being discarded
        // unread — 1280 solves that asserted nothing.)
        var fast = new Rig(1200, 1.0f, 1, 10f);
        for(int tick = 0; tick < 5; ++tick) {
            fast.net.network.calculate(subTicks);
        }

        var omega = 1200 * Math.PI / 30;
        var sourcePeak = omega;                       // field * omega
        var loadPeak = sourcePeak * 10.0 / 10.01;     // resistive divider against 0.01 ohm

        Assertions.assertEquals(loadPeak / Math.sqrt(2), fast.load.rmsVoltage(), loadPeak * 0.02,
                "Load RMS voltage is not peak/sqrt(2)");
        // powerFactor() returns 1 when apparentPower() is zero, so without this guard the
        // assertion below also passes on a stopped machine drawing nothing at all.
        Assertions.assertTrue(fast.load.apparentPower() > 0, "The load should be carrying power");
        Assertions.assertEquals(1.0, fast.load.powerFactor(), 0.02,
                "Power factor of a purely resistive load should be 1");
    }

    @Test
    void resistiveLoadPowerMatchesRmsProduct() {
        // For a resistive load, real power P must equal V_rms * I_rms, so the power factor is
        // 1 and apparent power equals real power. 20 Hz keeps one cycle per world tick.
        var rig = new Rig(1200, 1.0f, 1, 10f);
        for(int tick = 0; tick < 5; ++tick) {
            rig.net.network.calculate(64);
        }

        // P == S is an algebraic identity for any ElectricWire, not a property of the
        // alternator: current() is potentialDifference()*conductance(), so both sides reduce
        // to G*sum(v^2)/n for any waveform whatsoever. The old assertion passed on a DC
        // divider with no alternator in the world, and on an alternator that was not turning.
        //
        // The contrast is the real content: a resistive load is in phase and a reactive one is
        // not, on the same machine.
        Assertions.assertTrue(rig.load.apparentPower() > 0, "The load should be carrying power");
        Assertions.assertEquals(1.0, rig.load.powerFactor(), 0.02,
                "A resistive load on an alternator should be in phase");

        var reactive = new Rig(1200, 1.0f, 1, 10f);
        var inductor = new InductorWire(0.1, reactive.terminal, reactive.ground);
        reactive.net.network.addWire(inductor);
        for(int tick = 0; tick < 5; ++tick)
            reactive.net.network.calculate(64);
        Assertions.assertTrue(inductor.powerFactor() < 0.3,
                "An inductive load on the same machine should not be, got "
                        + inductor.powerFactor());
    }

    @Test
    void phaseKeepsAdvancingWhileNotConverged() {
        // Warm-up deliberately holds the network non-converged so component state can settle.
        // Phase is a clock and must not participate in that, or machines desynchronise every
        // time a player edits the grid.
        var rig = new Rig(RPM_1HZ, 1.0f, 1, 10f);
        rig.net.network.warmUp(5);

        var before = rig.alternator.getPhase();
        rig.net.network.calculate(20);
        var after = rig.alternator.getPhase();

        Assertions.assertNotEquals(before, after, "Phase froze during warm-up");
        Assertions.assertEquals(2 * Math.PI * 0.05, after - before, 1e-6,
                "Phase advanced by the wrong amount during warm-up");
    }

    @Test
    void subTickRateTracksSpeedAndIsBounded() {
        var slow = new Rig(0, 1.0f, 1, 10f);
        Assertions.assertEquals(1, slow.alternator.requiredSubTicks(),
                "A stopped machine should not ask for sub-ticks");

        var running = new Rig(272, 1.0f, 1, 10f);
        running.alternator.setSamplingPolicy(32, 16);
        var rate = running.alternator.requiredSubTicks();
        // 272 rpm, one pole pair -> 4.53 Hz -> 32 samples/cycle needs 7.25 per world tick,
        // rounded up to the next power of two.
        Assertions.assertEquals(8, rate, "Sub-tick rate for a full-speed machine is incorrect");

        running.alternator.setSamplingPolicy(32, 4);
        Assertions.assertEquals(4, running.alternator.requiredSubTicks(),
                "Sub-tick rate ignored its configured ceiling");
    }

    @Test
    void networkTakesTheMaximumRequestedSubTicks() {
        var rig = new Rig(272, 1.0f, 1, 10f);
        rig.alternator.setSamplingPolicy(32, 16);

        Assertions.assertEquals(8, rig.net.network.computeSubTicks(1),
                "Network did not adopt the alternator's requested rate");
        Assertions.assertEquals(32, rig.net.network.computeSubTicks(32),
                "Configured rate should act as a floor, not be overridden downward");
        Assertions.assertFalse(rig.net.network.requiresLockstep(),
                "A network with no transmission line should not demand lockstep");
    }

    @Test
    void torqueOpposesRotationUnderLoad() {
        // A loaded alternator must load the shaft. Sign convention follows the existing DC
        // generator: torque is fed back through applyTickForce as field * sin(p*theta) * i.
        var rig = new Rig(1200, 1.0f, 1, 10f);
        rig.net.network.calculate(64);

        // Not assertNotEquals(0). The name of this test promises a sign, and an alternator
        // that ACCELERATED its own shaft — free energy — passed the old assertion.
        Assertions.assertTrue(rig.rotor.appliedForce < 0,
                "A loaded alternator must oppose its rotor, got " + rig.rotor.appliedForce);

        var reversed = new Rig(-1200, 1.0f, 1, 10f);
        reversed.net.network.calculate(64);
        Assertions.assertTrue(reversed.rotor.appliedForce > 0,
                "Reversing the shaft must reverse the opposing torque, got "
                        + reversed.rotor.appliedForce);
    }
}
