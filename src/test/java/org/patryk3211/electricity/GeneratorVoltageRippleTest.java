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
import org.patryk3211.powergrid.electricity.sim.calculation.Precalculated;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * Why a single-phase alternator's output voltage will not hold still, and what fixes it.
 *
 * <h2>The report</h2>
 * A player testing the mod: the generator's RMS reads about 225 V but dips to about 217 V, and the
 * waveform looks like a slowly rising squarish sine. The dip is 3.5 %, which is far too large to be
 * a metering artifact -- the meter's RMS window is eight cycles and its worst rounding error is
 * under 1 % -- so the voltage really is moving.
 *
 * <h2>Why it moves</h2>
 * One winding on a resistive load takes {@code e*i}, a sine squared, off the shaft: the torque
 * swings from zero to twice its mean at twice the electrical frequency. The rotor integrates that
 * against its own inertia, which the mod ships small -- 0.5 for a rotor segment, 0.1 for the
 * commutator -- so the speed ripples, and the EMF is proportional to the speed. The governor
 * cannot help: it is a per-tick PD loop closing at 20 Hz against a ripple at twice the supply
 * frequency.
 *
 * <p>Nothing is wrong with the arithmetic. It is what a real single-phase machine does, which is
 * why real power stations are not single-phase. Three windings at 0, 120 and 240 degrees take a
 * constant torque off the same shaft, and their voltage holds.
 *
 * <h2>What {@link Shaft} is</h2>
 * A copy of {@code RotorBehaviour.tick}'s controller branch: the same friction clamp, the same
 * {@code v += force / 20 / inertia}, the same Kp/Kd governor with the shipped constants, in the
 * same order, and the shaft angle advanced before the speed changes. If that method changes, this
 * stops mirroring it -- it pins the reported behaviour, not the implementation.
 */
public class GeneratorVoltageRippleTest extends TestHelper {
    /** Shipped kinetics.generatorControls values. */
    private static final float KP = 0.99f, KD = 0.002f, SEGMENT_FRICTION = 0.25f;

    /** A commutator (0.1) plus two induction rotor segments (0.5 each). */
    private static final float INERTIA = 1.1f;
    private static final int SEGMENTS = 3;

    private static final float TARGET_RPM = 272;
    private static final int SUB_TICKS = 32;

    /** Field chosen so the machine makes about 225 V RMS at its top speed. */
    private static final float FIELD = (float) (225 * Math.sqrt(2) / (TARGET_RPM * Math.PI / 30));

    private static final float WINDING_RESISTANCE = 0.01f;
    private static final double ARMATURE_L = 0.02;

    /** A governed shaft, ticking exactly as RotorBehaviour's controller does. */
    private static class Shaft implements IRotor {
        private final float inertia;
        private final float maxForce;
        private float velocity;
        private float oldVelocity;
        private float pendingForce;
        private double angle;
        private long tick;

        Shaft(float inertia, float maxForce) {
            this.inertia = inertia;
            this.maxForce = maxForce;
            this.velocity = TARGET_RPM;
        }

        void advance() {
            var velocityNow = velocity;
            angle = AcSampling.wrapAngle(angle + getAngularVelocityRadians() * AcSampling.TICK_SECONDS);
            ++tick;

            var friction = Math.min(Math.abs(velocityNow * 20f * inertia), SEGMENTS * SEGMENT_FRICTION);
            var total = pendingForce - Math.signum(velocityNow) * friction;
            velocity += total / 20f / inertia;
            if(Math.abs(velocity) < 0.01f)
                velocity = 0;

            var deltaT = Math.max(0, TARGET_RPM - velocity);
            var deltaAV = oldVelocity - velocity;
            var force = (KP * deltaT + KD * deltaAV) * 20f * inertia;
            force = Math.min(Math.abs(force), maxForce);
            velocity += force / 20f / inertia;

            oldVelocity = velocity;
            pendingForce = 0;
        }

        @Override
        public float getInertia() {
            return inertia;
        }

        @Override
        public float getAngularVelocity() {
            return velocity;
        }

        @Override
        public void applyTickForce(float force) {
            pendingForce += force;
        }

        @Override
        public double getShaftAngle() {
            return angle;
        }

        @Override
        public long getShaftTick() {
            return tick;
        }
    }

    /**
     * A machine with {@code windings} windings evenly spaced, each on its own share of one total
     * load, so every case draws the same power and the comparison is fair.
     */
    private record Machine(Network net, Shaft shaft, IElectricNode[] terminals, IElectricNode neutral) { }

    private static Machine machine(int windings, float totalLoad) {
        return machine(windings, totalLoad, INERTIA);
    }

    private static Machine machine(int windings, float totalLoad, float inertia) {
        return machine(windings, totalLoad, inertia, 1e6f);
    }

    private static Machine machine(int windings, float totalLoad, float inertia, float maxForce) {
        var net = new Network();
        var shaft = new Shaft(inertia, maxForce);
        var neutral = net.N();
        net.network.addNode(new VoltageSourceCoupling(neutral, null, 0f, 0f));
        var terminals = new IElectricNode[windings];
        for(int k = 0; k < windings; ++k) {
            var terminal = new FloatingNode();
            net.network.addNode(terminal);
            var winding = new AlternatorCoupling(terminal, neutral, WINDING_RESISTANCE, shaft);
            winding.setField(FIELD);
            winding.setArmatureInductance(ARMATURE_L);
            winding.setWindingAngle(Math.toRadians(360.0 * k / windings));
            net.network.addNode(winding);
            net.W(totalLoad * windings, terminal, neutral);
            terminals[k] = terminal;
        }
        return new Machine(net, shaft, terminals, neutral);
    }

    /**
     * What the meter shows, and how much it moves.
     * <p>
     * RMS over a sliding window of whole cycles, not over one world tick: a tick is 0.23 of a
     * cycle at 4.53 Hz, and the RMS of a quarter cycle swings by more than two to one wherever it
     * falls. That is a property of the window, not of the machine, and measuring it would say
     * nothing about the report. The multimeter uses eight cycles, so this does too.
     *
     * @return spread as a fraction of the mean, mean, min, max
     */
    private static double[] slidingRms(Machine machine, int ticks) {
        for(int t = 0; t < 200; ++t) {
            machine.net.network.calculate(SUB_TICKS);
            machine.shaft.advance();
        }
        var samples = new double[ticks * SUB_TICKS];
        var at = 0;
        for(int t = 0; t < ticks; ++t) {
            machine.net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                machine.net.network.singleTick();
                samples[at] = machine.terminals[0].getVoltage() - machine.neutral.getVoltage();
            }
            machine.shaft.advance();
        }

        // Eight cycles at the machine's nominal electrical frequency.
        var frequency = TARGET_RPM / 60.0;
        var window = (int) Math.round(8 * SUB_TICKS * 20 / frequency);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
        var count = 0;
        var square = 0.0;
        for(int i = 0; i < window; ++i)
            square += samples[i] * samples[i];
        for(int i = window; i < samples.length; ++i) {
            var rms = Math.sqrt(square / window);
            min = Math.min(min, rms);
            max = Math.max(max, rms);
            sum += rms;
            ++count;
            square += samples[i] * samples[i] - samples[i - window] * samples[i - window];
        }
        var mean = sum / count;
        return new double[]{ (max - min) / mean, mean, min, max };
    }

    @Test
    void theGovernorHoldsCloseToTargetSpeedUnderOrdinaryLoad() {
        // Distinct from aMarginalPrimeMoverDroopsAndWobbles below: that test's drive is clamped
        // to maxForce 125 and is genuinely out of torque. This one leaves maxForce effectively
        // unlimited, so any gap between the settled speed and TARGET_RPM is the PD loop's own
        // proportional droop -- a real player reported landing only within 0.4 Hz of 60 Hz on an
        // ordinary, non-overloaded build. Measured here: 1.4361 rpm at the old Kp=0.85 (0.31 Hz
        // at 13 pole pairs, matching the report), cut to 0.0209 rpm at the shipped Kp=0.99.
        var m = machine(1, 12f);
        for(int t = 0; t < 400; ++t) {
            m.net.network.calculate(SUB_TICKS);
            m.shaft.advance();
        }
        var droop = TARGET_RPM - m.shaft.getAngularVelocity();
        System.out.printf("governor droop at Kp=%.3f: %.4f rpm%n", KP, droop);
        Assertions.assertTrue(Math.abs(droop) < 0.05,
                "The governor's own steady-state droop should stay under 0.05 rpm at Kp=" + KP
                        + ", got " + String.format("%.4f", droop));
    }

    @Test
    void theMachineHoldsItsVoltageWhenTheDriveCanKeepUp() {
        // The control, and the answer to "should it wander at all": with a prime mover that can
        // hold the speed, it does not. One winding and three windings both sit inside half a
        // percent, so a reading that swings by three percent is not the machine being alternating.
        var single = slidingRms(machine(1, 12f), 200);
        var three = slidingRms(machine(3, 12f), 200);

        System.out.printf("one winding:    mean %.1f V, %.1f to %.1f, ripple %.2f%%%n",
                single[1], single[2], single[3], single[0] * 100);
        System.out.printf("three windings: mean %.1f V, %.1f to %.1f, ripple %.2f%%%n",
                three[1], three[2], three[3], three[0] * 100);

        Assertions.assertTrue(single[0] < 0.01,
                "A driven single-phase machine should hold within 1%, got "
                        + String.format("%.2f%%", single[0] * 100));
        Assertions.assertTrue(three[0] < 0.01,
                "And so should three windings, got " + String.format("%.2f%%", three[0] * 100));
    }

    @Test
    void aMarginalPrimeMoverDroopsAndWobbles() {
        // And this is what a wandering reading looks like. The governor is a per-tick PD loop; once
        // the drive cannot supply the torque the load is taking, the speed falls until the two
        // balance, and the EMF falls with it -- a machine that should read 224 V reads 189 V and
        // moves five times as much. In game that is a prime mover at its stress limit, and the cure
        // is more input power, not more electrical anything.
        var strong = slidingRms(machine(1, 12f, INERTIA, 1e6f), 200);
        var marginal = slidingRms(machine(1, 12f, INERTIA, 125f), 200);

        System.out.printf("strong drive:   mean %.1f V, ripple %.2f%%%n", strong[1], strong[0] * 100);
        System.out.printf("marginal drive: mean %.1f V, ripple %.2f%%%n", marginal[1], marginal[0] * 100);

        Assertions.assertTrue(marginal[1] < strong[1] * 0.9,
                "A drive at its limit should droop the voltage, got " + marginal[1] + " against " + strong[1]);
        Assertions.assertTrue(marginal[0] > strong[0] * 2,
                "And make the reading wander, got " + String.format("%.2f%%", marginal[0] * 100)
                        + " against " + String.format("%.2f%%", strong[0] * 100));
    }

    @Test
    void aHeavierRotorRipplesLess() {
        // The lever a single-phase build can pull without rewiring: more rotor segments is more
        // inertia, and the speed ripple is the torque ripple integrated against it. At the shipped
        // Kp=0.99 the governor's own tighter loop already suppresses most of the ripple itself --
        // theMachineHoldsItsVoltageWhenTheDriveCanKeepUp's "one winding" row is 0.06% here against
        // 0.47% at the old Kp=0.85 -- so inertia is no longer the dominant lever and the margin is
        // smaller than it used to be, though still real.
        var light = slidingRms(machine(1, 12f, INERTIA), 200);
        var heavy = slidingRms(machine(1, 12f, INERTIA * 4), 200);

        System.out.printf("inertia %.1f: ripple %.2f%%; inertia %.1f: ripple %.2f%%%n",
                INERTIA, light[0] * 100, INERTIA * 4, heavy[0] * 100);
        Assertions.assertTrue(heavy[0] < light[0] * 0.8,
                "Four times the inertia should still cut the ripple, got "
                        + String.format("%.2f%%", heavy[0] * 100) + " against "
                        + String.format("%.2f%%", light[0] * 100));
    }

    /** WindingBlockEntity.fieldStrength, with the shipped saturation current and coil constant. */
    private static double fieldStrength(double current) {
        final double saturation = 2.0, coilConstant = 5;
        current = saturation * Math.tanh(1.5 * current / saturation) + current * 0.05;
        return current * coilConstant + 0.001;
    }

    /**
     * The same machine exciting itself: a field coil across its own terminals, the field taken from
     * that coil's current through the winding block's saturation curve, as a shunt-wound machine is
     * built in game.
     */
    private static double[] selfExcited(float load, float fieldCoilResistance, int ticks) {
        var net = new Network();
        var shaft = new Shaft(INERTIA, 1e6f);
        var neutral = net.N();
        net.network.addNode(new VoltageSourceCoupling(neutral, null, 0f, 0f));
        var terminal = new FloatingNode();
        net.network.addNode(terminal);

        var winding = new AlternatorCoupling(terminal, neutral, WINDING_RESISTANCE, shaft);
        winding.setArmatureInductance(ARMATURE_L);
        net.network.addNode(winding);
        net.W(load, terminal, neutral);

        var fieldCoil = new LRSeriesWire(fieldCoilResistance * 0.01, fieldCoilResistance, terminal, neutral);
        net.network.addWire(fieldCoil);
        winding.setFieldStrengthProvider(new Precalculated<Float>(0.001f) {
            @Override
            public Float get() {
                return (float) fieldStrength(fieldCoil.current());
            }

            @Override
            public int getStamp() {
                return 0;
            }

            @Override
            public void invalidate() { }
        });

        var machine = new Machine(net, shaft, new IElectricNode[]{ terminal }, neutral);
        return slidingRms(machine, ticks);
    }

    @Test
    void aSelfExcitedMachineBuildsUpAndStaysWithinAPercent() {
        // The other suspect for a wandering reading, ruled out at this size: a shunt-wound machine
        // closes a loop round itself through a 0.25 s filter, and it settles rather than hunting.
        var selfExcited = selfExcited(12f, 100f, 400);
        System.out.printf("self-excited:  mean %.1f V, %.1f to %.1f, ripple %.2f%%%n",
                selfExcited[1], selfExcited[2], selfExcited[3], selfExcited[0] * 100);

        Assertions.assertTrue(selfExcited[1] > 1,
                "A shunt-wound machine should build up to some voltage, got " + selfExcited[1]);
        Assertions.assertTrue(selfExcited[0] < 0.02,
                "and hold it within 2%, got " + String.format("%.2f%%", selfExcited[0] * 100));
    }
}
