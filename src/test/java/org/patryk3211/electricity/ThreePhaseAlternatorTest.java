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
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;

/**
 * Several alternator windings on one shaft, which is what a three-phase machine is.
 *
 * <h2>What is being pinned</h2>
 * The windings have to agree on the shaft's angle for their spacing to mean anything, and they
 * cannot if each integrates a private copy: one built later starts from zero, and islands stepped
 * at different rates sum different float timesteps. {@link Shaft} below stands in for {@code RotorBehaviour} and keeps the
 * angle the way it does -- advanced once per world tick, after the solve, by the speed the solve
 * used -- and the tests hold the windings to it.
 * <p>
 * The rest is what three-phase is for, measured rather than asserted from the formula: constant
 * torque on a balanced load where one winding pulses, and a delta that circulates nothing when
 * wired right and shorts the machine when one winding is reversed.
 */
public class ThreePhaseAlternatorTest extends TestHelper {
    private static final float FIELD = 1.0f;
    private static final float WINDING_RESISTANCE = 0.01f;
    private static final float LOAD = 2.0f;

    /** The shipped {@code solver.acArmatureInductance}. */
    private static final double ARMATURE_L = 0.02;

    // 240 rpm on one pole pair is 4 Hz, and eight sub-ticks make that exactly 40 samples per
    // cycle -- a whole number, so a window of whole cycles is a whole number of samples.
    private static final float RPM = 240;
    private static final int SUB_TICKS = 8;
    private static final int SAMPLES_PER_CYCLE = 40;

    /** Peak EMF: field times mechanical angular velocity, the model's definition. */
    private static final double PEAK = FIELD * (RPM * Math.PI / 30);

    /**
     * A shaft that keeps its own angle, as {@code RotorBehaviour} does, and records the torque the
     * windings put on it. Advanced by the test once per world tick, after every island has solved.
     */
    private static class Shaft implements IRotor {
        private final float rpm;
        private double angle;
        private long tick;
        private double pendingForce;

        Shaft(float rpm) {
            this.rpm = rpm;
        }

        void advance() {
            // Identical arithmetic to RotorBehaviour.tick, float accessor included.
            angle = AcSampling.wrapAngle(angle + getAngularVelocityRadians() * AcSampling.TICK_SECONDS);
            ++tick;
        }

        double drainForce() {
            var force = pendingForce;
            pendingForce = 0;
            return force;
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

    private static IElectricNode ground(Network net) {
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        return ground;
    }

    private static AlternatorCoupling winding(Network net, Shaft shaft, double degrees,
                                              IElectricNode positive, IElectricNode negative) {
        var winding = new AlternatorCoupling(positive, negative, WINDING_RESISTANCE, shaft);
        winding.setField(FIELD);
        winding.setArmatureInductance(ARMATURE_L);
        winding.setWindingAngle(Math.toRadians(degrees));
        net.network.addNode(winding);
        return winding;
    }

    private static FloatingNode node(Network net) {
        var node = new FloatingNode();
        net.network.addNode(node);
        return node;
    }

    /** One world tick: solve, then let the shaft advance, in the order the game does it. */
    private static void tick(Network net, Shaft shaft) {
        net.network.calculate(SUB_TICKS);
        shaft.advance();
    }

    /** Three windings, each with its own load to a grounded star point. */
    private record Star(Network net, Shaft shaft, AlternatorCoupling[] windings, ElectricWire[] loads) { }

    private static Star star(float rpm, double... degrees) {
        var net = new Network();
        var shaft = new Shaft(rpm);
        var neutral = ground(net);
        var windings = new AlternatorCoupling[degrees.length];
        var loads = new ElectricWire[degrees.length];
        for(int k = 0; k < degrees.length; ++k) {
            var terminal = node(net);
            windings[k] = winding(net, shaft, degrees[k], terminal, neutral);
            loads[k] = net.W(LOAD, terminal, neutral);
        }
        return new Star(net, shaft, windings, loads);
    }

    /** EMF of every winding at every sub-tick over {@code cycles} electrical cycles. */
    private static double[][] recordEmf(Star star, int cycles) {
        var samples = new double[star.windings.length][cycles * SAMPLES_PER_CYCLE];
        var ticks = cycles * SAMPLES_PER_CYCLE / SUB_TICKS;
        var at = 0;
        for(int t = 0; t < ticks; ++t) {
            star.net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                star.net.network.singleTick();
                for(int k = 0; k < star.windings.length; ++k)
                    samples[k][at] = star.windings[k].getVoltage();
            }
            star.shaft.advance();
        }
        return samples;
    }

    @Test
    void windingsAt0And120And240AreABalancedThreePhaseSet() {
        var star = star(RPM, 0, 120, 240);
        for(int t = 0; t < 40; ++t)
            tick(star.net, star.shaft);

        var emf = recordEmf(star, 4);
        var l1 = PhasorFit.fit(emf[0], SAMPLES_PER_CYCLE);
        var l2 = PhasorFit.fit(emf[1], SAMPLES_PER_CYCLE);
        var l3 = PhasorFit.fit(emf[2], SAMPLES_PER_CYCLE);

        // Each phase lags the one before: a winding further round the stator meets the pole later.
        // That is the L1-L2-L3 sequence a player setting 0, 120, 240 would expect. The tolerance
        // is float precision: IRotor reports speed as a float, so the waveform is not exactly the
        // 4 Hz the fit projects onto, and a millionth of a degree of leakage between phases is that.
        Assertions.assertEquals(-120, l2.degreesFrom(l1), 1e-4, "L2 should lag L1 by 120 degrees");
        Assertions.assertEquals(-120, l3.degreesFrom(l2), 1e-4, "L3 should lag L2 by 120 degrees");
        for(var phase : new PhasorFit.Phasor[]{ l1, l2, l3 })
            Assertions.assertEquals(PEAK, phase.magnitude(), PEAK * 1e-6,
                    "Every winding has the full EMF of the machine; nothing is divided between them");

        var worst = 0.0;
        for(int k = 0; k < emf[0].length; ++k)
            worst = Math.max(worst, Math.abs(emf[0][k] + emf[1][k] + emf[2][k]));
        Assertions.assertEquals(0, worst, PEAK * 1e-9,
                "A balanced set sums to zero at every instant, not merely on average");
    }

    @Test
    void theVoltageBetweenTwoPhasesIsRootThreeTimesOnePhase() {
        // What a player measures with one probe on each of two phases of a star-connected machine.
        // Reported from in game as reading one phase's voltage instead, which is what it reads when
        // the two windings are at the same angle -- see the test below.
        var star = star(RPM, 0, 120, 240);
        for(int t = 0; t < 40; ++t)
            tick(star.net, star.shaft);

        var samples = new double[2][4 * SAMPLES_PER_CYCLE];
        var at = 0;
        for(int t = 0; t < 4 * SAMPLES_PER_CYCLE / SUB_TICKS; ++t) {
            star.net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                star.net.network.singleTick();
                var l1 = star.windings[0].getPositive().getVoltage();
                var l2 = star.windings[1].getPositive().getVoltage();
                var neutral = star.windings[0].getNegative().getVoltage();
                samples[0][at] = l1 - neutral;
                samples[1][at] = l1 - l2;
            }
            star.shaft.advance();
        }

        var phase = PhasorFit.fit(samples[0], SAMPLES_PER_CYCLE);
        var line = PhasorFit.fit(samples[1], SAMPLES_PER_CYCLE);
        Assertions.assertEquals(Math.sqrt(3) * phase.magnitude(), line.magnitude(),
                phase.magnitude() * 0.01,
                "Line to line should be root three times phase to neutral, got " + line.magnitude()
                        + " against " + phase.magnitude());
        Assertions.assertEquals(30, line.degreesFrom(phase), 0.1,
                "and lead the phase voltage by thirty degrees");
    }

    @Test
    void twoWindingsAtTheSameAngleShowNothingBetweenThem() {
        // The reported symptom, reproduced: two windings left at the same angle are the same
        // waveform, so the difference between them is zero however much each one makes on its own.
        // Before the slider routing was fixed, every winding on a machine sat at 0 degrees.
        var star = star(RPM, 0, 0, 0);
        for(int t = 0; t < 40; ++t)
            tick(star.net, star.shaft);

        double worstPhase = 0, worstLine = 0;
        for(int t = 0; t < 40; ++t) {
            star.net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s) {
                star.net.network.singleTick();
                var neutral = star.windings[0].getNegative().getVoltage();
                worstPhase = Math.max(worstPhase,
                        Math.abs(star.windings[0].getPositive().getVoltage() - neutral));
                worstLine = Math.max(worstLine, Math.abs(star.windings[0].getPositive().getVoltage()
                        - star.windings[1].getPositive().getVoltage()));
            }
            star.shaft.advance();
        }

        Assertions.assertTrue(worstPhase > PEAK * 0.5,
                "Each winding still makes its own voltage, got " + worstPhase);
        Assertions.assertEquals(0, worstLine, PEAK * 1e-6,
                "But there is nothing between two of them, got " + worstLine);
    }

    @Test
    void reversingTheShaftReversesThePhaseSequence() {
        // Which is how a real machine behaves, and why swapping the direction of a prime mover
        // runs every three-phase motor on the grid backwards.
        var star = star(-RPM, 0, 120, 240);
        for(int t = 0; t < 40; ++t)
            tick(star.net, star.shaft);

        var emf = recordEmf(star, 4);
        var l1 = PhasorFit.fit(emf[0], SAMPLES_PER_CYCLE);
        var l2 = PhasorFit.fit(emf[1], SAMPLES_PER_CYCLE);
        Assertions.assertEquals(120, l2.degreesFrom(l1), 1e-4,
                "Turned backwards, L2 should lead L1 by 120 degrees instead of lagging it");
    }

    @Test
    void windingsInIslandsSteppedAtDifferentRatesStayInStep() {
        // Two windings on one shaft, each in its own island, one stepped eight times as finely as
        // the other. Private integrators would each sum a different number of float timesteps and
        // drift apart for as long as the world ran; reading the shaft, they cannot.
        var shaft = new Shaft(RPM);
        var fine = new Network();
        var coarse = new Network();
        var a = winding(fine, shaft, 0, node(fine), ground(fine));
        fine.W(LOAD, a.getPositive(), a.getNegative());
        var b = winding(coarse, shaft, 120, node(coarse), ground(coarse));
        coarse.W(LOAD, b.getPositive(), b.getNegative());

        // An hour and a bit of game time.
        for(int t = 0; t < 100_000; ++t) {
            fine.network.calculate(32);
            coarse.network.calculate(4);
            shaft.advance();
        }

        Assertions.assertEquals(a.getPhase(), b.getPhase(), 1e-12,
                "Both windings must read the same shaft angle at the end of a tick");
        // And the EMF each produced on its last sub-tick is exactly its own angle on that shaft.
        var theta = a.getPhase();
        Assertions.assertEquals(PEAK * Math.sin(theta), a.getVoltage(), PEAK * 1e-5);
        Assertions.assertEquals(PEAK * Math.sin(theta - Math.toRadians(120)), b.getVoltage(), PEAK * 1e-5);
    }

    @Test
    void aWindingAddedToARunningMachineStartsInStep() {
        // Placing a third alternator on a shaft that has been turning for a while. With a private
        // integrator it would start at zero and sit wherever build order left it; reading the shaft
        // it joins at the angle the other two are already at.
        var star = star(RPM, 0, 120);
        for(int t = 0; t < 1234; ++t)
            tick(star.net, star.shaft);

        var neutral = star.windings[0].getNegative();
        var terminal = node(star.net);
        var late = winding(star.net, star.shaft, 240, terminal, neutral);
        star.net.W(LOAD, terminal, neutral);
        tick(star.net, star.shaft);

        Assertions.assertEquals(star.windings[0].getPhase(), late.getPhase(), 1e-12,
                "A winding built late must read the same shaft angle as the ones already running");
    }

    /**
     * Torque on the shaft at every sub-tick, after the transient has gone. The windings divide
     * their torque by the sub-tick count so the rotor can sum a whole tick, so this multiplies it
     * back up to the instantaneous value.
     */
    private static double[] torquePerSubTick(Star star) {
        for(int t = 0; t < 200; ++t) {
            tick(star.net, star.shaft);
            star.shaft.drainForce();
        }
        var torque = new double[4 * SAMPLES_PER_CYCLE];
        var at = 0;
        while(at < torque.length) {
            star.net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                star.net.network.singleTick();
                torque[at] = star.shaft.drainForce() * SUB_TICKS;
            }
            star.shaft.advance();
        }
        return torque;
    }

    private static double mean(double[] values) {
        var sum = 0.0;
        for(var v : values)
            sum += v;
        return sum / values.length;
    }

    private static double ripple(double[] values) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for(var v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return (max - min) / Math.abs(mean(values));
    }

    @Test
    void aBalancedThreePhaseLoadPutsASteadyTorqueOnTheShaft() {
        var single = torquePerSubTick(star(RPM, 0));
        var three = torquePerSubTick(star(RPM, 0, 120, 240));

        // One winding: e*i is a sine squared, so torque swings from about zero to twice its mean at
        // twice the supply frequency. The armature's lag lets it dip just below zero.
        Assertions.assertTrue(ripple(single) > 1.9,
                "One winding's torque should swing by about twice its mean, got " + ripple(single));

        // Three: sin^2(x) + sin^2(x - 120) + sin^2(x - 240) is exactly 3/2 for every x, and the lag
        // does not change that because it is the same on every phase.
        Assertions.assertTrue(ripple(three) < 1e-4,
                "Three balanced windings should put a constant torque on the shaft, got a ripple of "
                        + ripple(three));

        // Constant, but not free: three times the power of one phase, so three times the torque.
        Assertions.assertEquals(3 * mean(single), mean(three), Math.abs(mean(single)) * 1e-3,
                "Three identical phases should load the shaft three times as hard as one");
    }

    /**
     * Three windings chained end to start. {@code second} is the angle of the middle winding, so
     * 120 is a correct delta and 300 is the same winding connected backwards.
     */
    private static double circulatingRmsCurrent(double second) {
        var net = new Network();
        var shaft = new Shaft(RPM);
        var n1 = ground(net);
        var n2 = node(net);
        var n3 = node(net);
        var l1 = winding(net, shaft, 0, n2, n1);
        winding(net, shaft, second, n3, n2);
        winding(net, shaft, 240, n1, n3);

        for(int t = 0; t < 200; ++t)
            tick(net, shaft);
        var current = new double[4 * SAMPLES_PER_CYCLE];
        var at = 0;
        while(at < current.length) {
            net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                net.network.singleTick();
                current[at] = l1.getCurrent();
            }
            shaft.advance();
        }
        return PhasorFit.rms(current);
    }

    @Test
    void aDeltaWiredCorrectlyCirculatesNothing() {
        var rms = circulatingRmsCurrent(120);
        Assertions.assertEquals(0, rms, 1e-6,
                "Three balanced EMFs round a closed loop sum to zero and drive no current, got " + rms);
    }

    @Test
    void aDeltaWithOneWindingReversedShortCircuitsTheMachine() {
        // The loop EMF becomes -2 times the reversed winding's, driven through three armatures.
        var rms = circulatingRmsCurrent(300);

        var omega = 2 * Math.PI * RPM / 60;
        var impedance = Math.hypot(3 * WINDING_RESISTANCE, 3 * omega * ARMATURE_L);
        var expected = 2 * PEAK / impedance / Math.sqrt(2);
        Assertions.assertEquals(expected, rms, expected * 0.02,
                "A reversed delta winding should drive 2E / 3Z round the loop, about "
                        + String.format("%.1f", expected) + " A RMS, got " + String.format("%.1f", rms));
    }
}
