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
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.AlternatorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort;
import org.patryk3211.powergrid.electricity.sim.special.WattmeterWire;

import java.util.function.DoubleSupplier;

/**
 * Wye and delta as a player builds them, rather than as a textbook draws them.
 *
 * <h2>Why this file exists next to the two three-phase ones</h2>
 * {@code ThreePhaseAlternatorTest} and {@code ThreePhaseTransmissionTest} pin the ideal cases, and
 * they do it on a network the game never builds: {@code new ElectricalNetwork(false)}, so without
 * the stabilising shunt, and with a node held at zero volts by a source. The game builds its
 * islands with {@code addGMin = true}, where the only reference to ground is a 1000 S shunt put on
 * one node when no wire runs to ground, and a player's star point has no ground at all unless he
 * places a grounding rod. Every network here is {@code Network(true)}.
 *
 * <h2>What is being asked of the solver</h2>
 * Nothing in the solver knows what a phase, a line or a star point is. Whether a wired circuit
 * behaves as wye or delta is decided by which nodes the wires join, so what these tests establish
 * is that the solver stays well conditioned and gives the textbook answer when nothing is grounded,
 * when something is grounded through a poor earth, when the load is unbalanced, when the conductors
 * are not the same length, and when the structure changes underneath a running machine. Each
 * measurement is compared with an independent complex-number solution held in the test
 * ({@link C}), not with a constant copied from a previous run.
 *
 * <h2>Layout</h2>
 * Machines and their loads and earths first; then sub-tick rate, frequency, start-up and structure
 * changes; then a whole chain (generator, step-up bank, line, step-down bank, load); then unequal
 * conductors; then what a line split across two islands does to three phases; then what a one-branch
 * wattmeter reads on three. A test that prints a figure prints it so that docs/AC.md section 3.15
 * can quote it; run with {@code --info} to see them.
 *
 * <h2>What these cannot reach</h2>
 * Block entities, value behaviours, the transformer winding screen, wire placement, rendering and
 * networking. {@link Shaft} stands in for {@code RotorBehaviour} and {@link #transformer} for
 * {@code TransformerBlockEntity.buildCircuit}, exactly as the older files do, and the islands are
 * plain {@code ElectricalNetwork}s rather than the {@code GraphedElectricalNetwork} the game builds.
 * Whether a player can physically wire each of these shapes is answered by reading the code, in
 * docs/AC.md section 3.15.
 */
public class WyeDeltaSystemTest extends TestHelper {
    private static final float FIELD = 1.0f;
    private static final float WINDING_RESISTANCE = 0.01f;

    /** The shipped {@code solver.acArmatureInductance}. */
    private static final double ARMATURE_L = 0.02;
    private static final double ROOT3 = Math.sqrt(3);

    // 240 rpm on one pole pair is 4 Hz. At 8 sub-ticks that is 40 samples a cycle, and a cycle is
    // exactly five world ticks, so a window of whole cycles is a whole number of ticks.
    private static final float RPM = 240;
    private static final int SUB_TICKS = 8;
    private static final int CYCLES = 4;
    private static final int SETTLE_CYCLES = 8;

    private static final double E = peak(RPM);

    /** Peak EMF at a shaft speed: field times mechanical angular velocity, the model's definition. */
    private static double peak(float rpm) {
        return FIELD * (rpm * Math.PI / 30);
    }

    // ------------------------------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------------------------------

    /**
     * A shaft that keeps its own angle, as {@code RotorBehaviour} does, records the torque the
     * windings put on it, and can be told to change speed.
     */
    private static final class Shaft implements IRotor {
        float rpm;
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

    /**
     * A phasor. Used to solve the same circuit on paper, so that the solver is compared with an
     * answer that was not produced by the solver.
     */
    private record C(double re, double im) {
        static C polar(double magnitude, double degrees) {
            return new C(magnitude * Math.cos(Math.toRadians(degrees)),
                    magnitude * Math.sin(Math.toRadians(degrees)));
        }

        static C real(double re) {
            return new C(re, 0);
        }

        C plus(C o) {
            return new C(re + o.re, im + o.im);
        }

        C minus(C o) {
            return new C(re - o.re, im - o.im);
        }

        C times(C o) {
            return new C(re * o.re - im * o.im, re * o.im + im * o.re);
        }

        C div(C o) {
            var d = o.re * o.re + o.im * o.im;
            return new C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
        }

        double abs() {
            return Math.hypot(re, im);
        }

        double degrees() {
            return Math.toDegrees(Math.atan2(im, re));
        }
    }

    private static C phasor(PhasorFit.Phasor p) {
        return C.polar(p.magnitude(), p.degrees());
    }

    /**
     * Fit over a window that is {@code cycles} whole cycles long, however many samples that is.
     * {@link PhasorFit#fit} needs an integer number of samples per cycle, which 4.5 Hz at eight
     * sub-ticks (35.6) is not.
     */
    private static PhasorFit.Phasor fit(double[] samples, int cycles) {
        double s = 0, c = 0;
        var n = samples.length;
        for(int k = 0; k < n; ++k) {
            // Sample k is taken at the end of sub-tick k, as PhasorFit assumes.
            var angle = 2 * Math.PI * cycles * (k + 1) / n;
            s += samples[k] * Math.sin(angle);
            c += samples[k] * Math.cos(angle);
        }
        s *= 2.0 / n;
        c *= 2.0 / n;
        return new PhasorFit.Phasor(Math.hypot(s, c), Math.toDegrees(Math.atan2(c, s)));
    }

    private static double mean(double[] samples) {
        var sum = 0.0;
        for(var v : samples)
            sum += v;
        return sum / samples.length;
    }

    private static double maxAbs(double[] samples) {
        var max = 0.0;
        for(var v : samples)
            max = Math.max(max, Math.abs(v));
        return max;
    }

    /**
     * One island, its shaft, and the stepping the game does: solve every sub-tick of a world tick,
     * then advance the shaft.
     */
    private static final class Bench {
        final Network net;
        final Shaft shaft;
        int subTicks;
        int polePairs = 1;

        /**
         * @param gameLike build the island without the test helper's permanent warm-up. The helper
         *                 calls {@code warmUp(-1)}, which switches off the frozen-state ticks the
         *                 game holds an island in for five sub-ticks after every change; a test of
         *                 structure changes that skipped them would be testing something else.
         */
        Bench(boolean gameLike, float rpm, int subTicks) {
            net = new Network(true);
            if(gameLike)
                net.network = new ElectricalNetwork(true);
            shaft = new Shaft(rpm);
            this.subTicks = subTicks;
        }

        /** Electrical frequency: shaft speed times pole pairs, as {@code requiredSubTicks} computes it. */
        double frequency() {
            return shaft.rpm * polePairs / 60.0;
        }

        /**
         * The smallest cycle count of at least {@code minimum} that is also a whole number of world
         * ticks, so a window of that many cycles is a whole number of sub-ticks. At 4.5 Hz a tick is
         * 0.225 of a cycle and nine cycles is forty ticks; four is not a whole number of anything.
         */
        int cyclesFor(int minimum) {
            var perTick = frequency() * AcSampling.TICK_SECONDS;
            for(int cycles = minimum; cycles < 10_000; ++cycles) {
                var ticks = cycles / perTick;
                if(Math.abs(ticks - Math.round(ticks)) < 1e-6)
                    return cycles;
            }
            throw new AssertionError("No whole-tick window for " + frequency() + " Hz");
        }

        void tick() {
            net.network.calculate(subTicks);
            shaft.advance();
        }

        void ticks(int count) {
            for(int i = 0; i < count; ++i)
                tick();
        }

        int ticksFor(int cycles) {
            var perTick = frequency() * AcSampling.TICK_SECONDS;
            var ticks = Math.round(cycles / perTick);
            Assertions.assertEquals(cycles, ticks * perTick, 1e-6, "The window is not a whole number of ticks");
            return (int) ticks;
        }

        void settle() {
            ticks(ticksFor(cyclesFor(SETTLE_CYCLES)));
        }

        /**
         * Every probe at every sub-tick over {@code cycles} whole cycles. A value that is not
         * finite fails the test where it happens, not somewhere downstream.
         */
        double[][] record(int cycles, DoubleSupplier... probes) {
            var ticks = ticksFor(cycles);
            var out = new double[probes.length][ticks * subTicks];
            var at = 0;
            for(int t = 0; t < ticks; ++t) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s, ++at) {
                    net.network.singleTick();
                    for(int p = 0; p < probes.length; ++p) {
                        var value = probes[p].getAsDouble();
                        if(!Double.isFinite(value))
                            Assertions.fail("Probe " + p + " is " + value + " at tick " + t + ", sub-tick " + s);
                        out[p][at] = value;
                    }
                }
                shaft.advance();
            }
            return out;
        }

        FloatingNode node() {
            return net.N();
        }

        AlternatorCoupling winding(double degrees, IElectricNode positive, IElectricNode negative) {
            var winding = new AlternatorCoupling(positive, negative, WINDING_RESISTANCE, shaft);
            winding.setField(FIELD);
            winding.setArmatureInductance(ARMATURE_L);
            winding.setWindingAngle(Math.toRadians(degrees));
            // The rate the game would ask for, so that a ramp through the speeds picks its own.
            winding.setSamplingPolicy(32, 64);
            winding.setPolePairs(polePairs);
            net.network.addNode(winding);
            return winding;
        }
    }

    private static DoubleSupplier across(IElectricNode a, IElectricNode b) {
        return () -> a.getVoltage() - b.getVoltage();
    }

    private static DoubleSupplier volts(IElectricNode node) {
        return node::getVoltage;
    }

    /** Three windings and the nodes a player would wire to. {@code neutral} is null for a delta. */
    private record Machine(AlternatorCoupling[] windings, IElectricNode[] lines, IElectricNode neutral) { }

    /**
     * Star: one terminal of each winding tied together. {@code tieNegatives} ties the blue
     * terminals, which is the usual way, and puts each line on the red one.
     */
    private static Machine wye(Bench bench, boolean tieNegatives) {
        var neutral = bench.node();
        var windings = new AlternatorCoupling[3];
        var lines = new IElectricNode[3];
        for(int k = 0; k < 3; ++k) {
            lines[k] = bench.node();
            windings[k] = tieNegatives
                    ? bench.winding(120.0 * k, lines[k], neutral)
                    : bench.winding(120.0 * k, neutral, lines[k]);
        }
        return new Machine(windings, lines, neutral);
    }

    /**
     * Delta: each winding's end to the next one's start, round the loop and nothing else. The
     * three corners are the lines. Windings at 0, 120 and 240 degrees, so the loop EMF sums to zero.
     */
    private static Machine delta(Bench bench) {
        return delta(bench, 0, 120, 240);
    }

    private static Machine delta(Bench bench, double first, double second, double third) {
        var corners = new IElectricNode[]{ bench.node(), bench.node(), bench.node() };
        var angles = new double[]{ first, second, third };
        var windings = new AlternatorCoupling[3];
        for(int k = 0; k < 3; ++k)
            windings[k] = bench.winding(angles[k], corners[(k + 1) % 3], corners[k]);
        return new Machine(windings, corners, null);
    }

    /** A run of conductor on each line: the nodes at the far end and the wires themselves. */
    private record Feeders(IElectricNode[] far, ElectricWire[] wires) { }

    private static Feeders feeders(Bench bench, IElectricNode[] near, double... ohms) {
        var far = new IElectricNode[3];
        var wires = new ElectricWire[3];
        for(int k = 0; k < 3; ++k) {
            far[k] = bench.node();
            wires[k] = bench.net.W((float) ohms[ohms.length == 1 ? 0 : k], near[k], far[k]);
        }
        return new Feeders(far, wires);
    }

    /** Three loads and, for a star, its point. {@code star} is null for a delta. */
    private record Load(ElectricWire[] wires, IElectricNode star) { }

    private static Load wyeLoad(Bench bench, IElectricNode[] lines, double... ohms) {
        var star = bench.node();
        var wires = new ElectricWire[3];
        for(int k = 0; k < 3; ++k)
            wires[k] = bench.net.W((float) ohms[ohms.length == 1 ? 0 : k], lines[k], star);
        return new Load(wires, star);
    }

    private static Load deltaLoad(Bench bench, IElectricNode[] lines, double... ohms) {
        var wires = new ElectricWire[3];
        for(int k = 0; k < 3; ++k)
            wires[k] = bench.net.W((float) ohms[ohms.length == 1 ? 0 : k], lines[k], lines[(k + 1) % 3]);
        return new Load(wires, null);
    }

    /** A ground rod that works: a switched wire to ground, as {@code GroundingRodBlockEntity} builds. */
    private static SwitchedWire rod(Bench bench, IElectricNode node, double ohms, boolean working) {
        return bench.net.SW((float) ohms, node, null, working);
    }

    private static double sum3(DoubleSupplier[] each) {
        return each[0].getAsDouble() + each[1].getAsDouble() + each[2].getAsDouble();
    }

    private static DoubleSupplier[] currents(ElectricWire[] wires) {
        return new DoubleSupplier[]{ wires[0]::current, wires[1]::current, wires[2]::current };
    }

    /** Impedance of one winding at a shaft speed, as the armature is specified. */
    private static C windingImpedance(float rpm) {
        return windingImpedance(rpm, 1);
    }

    private static C windingImpedance(float rpm, int polePairs) {
        return new C(WINDING_RESISTANCE, 2 * Math.PI * rpm * polePairs / 60 * ARMATURE_L);
    }

    /**
     * Three phase currents that should be a balanced set: equal magnitudes, {@code -120} degrees
     * apart. The angular tolerance is float precision in the shaft speed.
     */
    private static void assertBalanced(String what, PhasorFit.Phasor a, PhasorFit.Phasor b, PhasorFit.Phasor c,
                                       double relative) {
        Assertions.assertEquals(a.magnitude(), b.magnitude(), a.magnitude() * relative, what + ": phases 1 and 2 differ");
        Assertions.assertEquals(a.magnitude(), c.magnitude(), a.magnitude() * relative, what + ": phases 1 and 3 differ");
        Assertions.assertEquals(-120, b.degreesFrom(a), 1e-3, what + ": phase 2 should lag phase 1 by 120 degrees");
        Assertions.assertEquals(-120, c.degreesFrom(b), 1e-3, what + ": phase 3 should lag phase 2 by 120 degrees");
    }

    // ------------------------------------------------------------------------------------------
    // Balanced systems, G_MIN on, nothing grounded
    // ------------------------------------------------------------------------------------------

    @Test
    void aFloatingWyeMachineIntoAWyeLoadHasRootThreeLineVoltage() {
        // The case the older files could not have caught: no neutral wire, no ground, no source
        // holding a node at zero. This test does not need G_MIN's shunt: with the shunt taken out a
        // floating wye still solves, its star point merely wanders. The shunt is what a floating
        // delta and the 1 ohm unequal-conductor case need, and those tests guard it; where it puts
        // zero volts is guarded by whichNodeTheSolverAnchors... and the unbalanced-load tests.
        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, 0.01);
        var load = wyeLoad(bench, feeders.far, 2);
        bench.settle();

        var s = bench.record(CYCLES,
                across(machine.lines[0], machine.neutral),
                across(machine.lines[0], machine.lines[1]),
                machine.windings[0]::getVoltage,
                feeders.wires[0]::current, feeders.wires[1]::current, feeders.wires[2]::current,
                across(load.star, machine.neutral),
                () -> sum3(currents(feeders.wires)));

        var phase = fit(s[0], CYCLES);
        var line = fit(s[1], CYCLES);
        Assertions.assertEquals(ROOT3 * phase.magnitude(), line.magnitude(), phase.magnitude() * 1e-4,
                "Line to line should be root three times phase to neutral");
        Assertions.assertEquals(30, line.degreesFrom(phase), 0.01, "and lead it by thirty degrees");

        var i1 = fit(s[3], CYCLES);
        var i2 = fit(s[4], CYCLES);
        var i3 = fit(s[5], CYCLES);
        assertBalanced("floating wye", i1, i2, i3, 1e-6);
        Assertions.assertEquals(0, maxAbs(s[7]), 1e-9,
                "With no neutral the three line currents sum to zero at every instant");
        Assertions.assertEquals(0, maxAbs(s[6]), E * 1e-6,
                "A balanced load's star point sits on the machine's, though nothing joins them");

        // The current the paper solution gives: one EMF behind the winding and the load in series.
        var expected = E / windingImpedance(RPM).plus(C.real(2.01)).abs();
        Assertions.assertEquals(expected, i1.magnitude(), expected * 0.005,
                "Phase current should be EMF over winding-plus-load impedance");
        Assertions.assertTrue(bench.net.network.isConverged(), "The island should be converged");
    }

    @Test
    void whichNodeTheSolverAnchorsDecidesWhereZeroVoltsIsAndNothingElse() {
        // With no wire to ground, ElectricalNetwork puts a 1000 S shunt on the negative terminal of
        // the first voltage source it finds. For a machine wired star with the blue terminals tied
        // that is the star point, and the neutral sits at exactly zero. Tie the red ones instead and
        // the same rule lands on a line, the star point floats to one phase voltage above ground.
        // Nothing but the reference moves: currents and line voltages are identical.
        double[] blueLineToGround = new double[2];
        double[] neutralToGround = new double[2];
        PhasorFit.Phasor[] current = new PhasorFit.Phasor[2];
        PhasorFit.Phasor[] lineVoltage = new PhasorFit.Phasor[2];
        for(int tied = 0; tied < 2; ++tied) {
            var bench = new Bench(false, RPM, SUB_TICKS);
            var machine = wye(bench, tied == 0);
            var feeders = feeders(bench, machine.lines, 0.01);
            wyeLoad(bench, feeders.far, 2);
            bench.settle();
            var s = bench.record(CYCLES, volts(machine.neutral), volts(machine.lines[0]),
                    feeders.wires[0]::current, across(machine.lines[0], machine.lines[1]));
            neutralToGround[tied] = maxAbs(s[0]);
            blueLineToGround[tied] = maxAbs(s[1]);
            current[tied] = fit(s[2], CYCLES);
            lineVoltage[tied] = fit(s[3], CYCLES);
        }
        Assertions.assertEquals(0, neutralToGround[0], 1e-9, "Blue terminals tied: the star point is the anchor");
        Assertions.assertEquals(0, blueLineToGround[1], 1e-9, "Red terminals tied: line 1 is the anchor");
        Assertions.assertTrue(neutralToGround[1] > 20, "and the star point floats a phase voltage off ground, got "
                + neutralToGround[1]);
        Assertions.assertEquals(current[0].magnitude(), current[1].magnitude(), current[0].magnitude() * 1e-6);
        // The red-tied machine is the same set with every winding reversed, so its phase voltages
        // are 180 degrees round; the line voltage is that much too.
        Assertions.assertEquals(lineVoltage[0].magnitude(), lineVoltage[1].magnitude(), lineVoltage[0].magnitude() * 1e-6);
    }

    /** Wye or delta machine into wye or delta load, all four ways. */
    private record Pairing(String name, boolean deltaMachine, boolean deltaLoad) { }

    @Test
    void lineCurrentIsRootThreePhaseCurrentInADeltaWhicheverWayItIsFed() {
        // Ohms per load leg, chosen so every pairing draws a comparable current: a delta leg is
        // three times a star leg at the same load.
        for(var pairing : new Pairing[]{
                new Pairing("wye machine, wye load", false, false),
                new Pairing("wye machine, delta load", false, true),
                new Pairing("delta machine, wye load", true, false),
                new Pairing("delta machine, delta load", true, true) }) {
            var bench = new Bench(false, RPM, SUB_TICKS);
            var machine = pairing.deltaMachine ? delta(bench) : wye(bench, true);
            var feeders = feeders(bench, machine.lines, 0.01);
            var load = pairing.deltaLoad ? deltaLoad(bench, feeders.far, 6) : wyeLoad(bench, feeders.far, 2);
            bench.settle();

            var s = bench.record(CYCLES,
                    feeders.wires[0]::current, feeders.wires[1]::current, feeders.wires[2]::current,
                    load.wires[0]::current,
                    machine.windings[0]::getCurrent,
                    () -> sum3(currents(feeders.wires)));
            var line = new PhasorFit.Phasor[]{ fit(s[0], CYCLES), fit(s[1], CYCLES), fit(s[2], CYCLES) };
            var loadLeg = fit(s[3], CYCLES);
            var winding = fit(s[4], CYCLES);

            assertBalanced(pairing.name, line[0], line[1], line[2], 1e-6);
            Assertions.assertEquals(0, maxAbs(s[5]), 1e-9, pairing.name + ": line currents sum to zero");

            // A leg of a delta carries the line current over root three; a leg of a star, all of it.
            if(pairing.deltaLoad)
                Assertions.assertEquals(ROOT3, line[0].magnitude() / loadLeg.magnitude(), 1e-4,
                        pairing.name + ": a delta load leg carries the line current over root three");
            else
                Assertions.assertEquals(1, line[0].magnitude() / loadLeg.magnitude(), 1e-6,
                        pairing.name + ": a star load leg carries the whole line current");
            if(pairing.deltaMachine)
                Assertions.assertEquals(ROOT3, line[0].magnitude() / winding.magnitude(), 1e-4,
                        pairing.name + ": a delta winding carries the line current over root three");
            else
                Assertions.assertEquals(1, line[0].magnitude() / winding.magnitude(), 1e-6,
                        pairing.name + ": a star winding carries the whole line current");
            Assertions.assertTrue(bench.net.network.isConverged(), pairing.name + " should be converged");
        }
    }

    @Test
    void aFloatingDeltaCirculatesNothingWhenRightAndShortsTheMachineWhenOneWindingIsReversed() {
        // No load, no ground. Wired right the three EMFs round the loop sum to zero at every
        // instant; with the middle winding turned round (300 rather than 120) the loop carries 2E
        // through three armature impedances, the same figure ThreePhaseAlternatorTest derives for a
        // grounded delta. Repeated here because a floating delta is the shape a player builds first,
        // and because it is the circuit that needs G_MIN's shunt: with the shunt taken out the matrix
        // is singular and the reversed loop reads 0 A instead of 23.6.
        //
        // The loop's time constant is L/R = 2 s, so a window opened after the usual two seconds still
        // holds a decaying DC offset (mean -4.0 A, RMS 1.7 % high at 8 sub-ticks when measured) and
        // would be measuring that offset, not the model. Ten more seconds, and 32 sub-ticks, where
        // the theta-method is within 0.02 % of the paper figure (0.2 % at 8, 0.06 % at 16).
        double[] rms = new double[2];
        double[] dc = new double[2];
        double[] reversedAngle = { 120, 300 };
        for(int variant = 0; variant < 2; ++variant) {
            var bench = new Bench(false, RPM, DELTA_SUB_TICKS);
            var machine = delta(bench, 0, reversedAngle[variant], 240);
            bench.settle();
            bench.ticks(bench.ticksFor(40));
            var s = bench.record(CYCLES, machine.windings[0]::getCurrent);
            rms[variant] = PhasorFit.rms(s[0]);
            dc[variant] = mean(s[0]);
        }
        Assertions.assertEquals(0, rms[0], 1e-9, "A correctly wired delta should circulate nothing, got " + rms[0]);

        var omega = 2 * Math.PI * RPM / 60;
        var impedance = Math.hypot(3 * WINDING_RESISTANCE, 3 * omega * ARMATURE_L);
        var expected = 2 * E / impedance / Math.sqrt(2);
        System.out.printf("reversed delta at %d sub-ticks: %.5f A RMS against %.5f on paper (%.4f %%), mean %.4f A%n",
                DELTA_SUB_TICKS, rms[1], expected, 100 * (rms[1] / expected - 1), dc[1]);
        Assertions.assertEquals(expected, rms[1], expected * 0.001,
                "A reversed delta winding should drive 2E / 3Z round the loop, " + String.format("%.2f", expected)
                        + " A RMS, got " + String.format("%.2f", rms[1]));
        Assertions.assertEquals(0, dc[1], 0.1, "The DC offset should have died away, or the RMS is not the model's");
    }

    // ------------------------------------------------------------------------------------------
    // Unbalanced loads, and what the neutral and the earth do about them
    // ------------------------------------------------------------------------------------------

    private enum Earth {
        /** No neutral wire, no rod. */
        FLOATING,
        /** A neutral wire from the machine's star point to the load's. */
        NEUTRAL_WIRE,
        /** One working rod on the machine's star point, no neutral wire. */
        ROD_AT_SOURCE,
        /** A working 50 ohm rod at each star point, no neutral wire. */
        RODS_BOTH_ENDS,
        /** A rod on the star point that is not connected to earth: too few blocks round it. */
        DEAD_ROD,
        /**
         * One working rod on the LOAD's star point. With nothing grounded the solver anchors the
         * machine's star point, so only a rod placed on the other side can be told from no rod.
         */
        ROD_AT_LOAD,
        /** A rod that is not connected to earth, on the load's star point. */
        DEAD_ROD_AT_LOAD
    }

    private static final int DELTA_SUB_TICKS = 32;

    private static final double NEUTRAL_WIRE_OHMS = 0.01;
    private static final double ROD_OHMS = 50;

    /** What a wye machine into a 10, 100, 100 ohm wye load does under each way of earthing it. */
    private record Unbalanced(double[] loadVolts, C star, C emf, double rodPeak, double neutralDrift,
                              C[] expected, C expectedStar, boolean converged, double starToGround,
                              double neutralRms) { }

    private static Unbalanced unbalanced(Earth earth) {
        return unbalanced(earth, ROD_OHMS);
    }

    private static Unbalanced unbalanced(Earth earth, double rodOhms) {
        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, 0.01);
        var ohms = new double[]{ 10, 100, 100 };
        var load = wyeLoad(bench, feeders.far, ohms);
        SwitchedWire rod = null;
        var neutralConductance = 0.0;
        switch(earth) {
            case NEUTRAL_WIRE -> {
                bench.net.W((float) NEUTRAL_WIRE_OHMS, machine.neutral, load.star);
                neutralConductance = 1 / NEUTRAL_WIRE_OHMS;
            }
            case ROD_AT_SOURCE -> rod = rod(bench, machine.neutral, 5, true);
            case RODS_BOTH_ENDS -> {
                rod = rod(bench, machine.neutral, rodOhms, true);
                rod(bench, load.star, rodOhms, true);
                neutralConductance = 1 / (2 * rodOhms);
            }
            case DEAD_ROD -> rod = rod(bench, machine.neutral, 5, false);
            case ROD_AT_LOAD -> rod = rod(bench, load.star, 5, true);
            case DEAD_ROD_AT_LOAD -> rod = rod(bench, load.star, 5, false);
            default -> { }
        }
        bench.settle();

        var rodCurrent = rod;
        var s = bench.record(CYCLES,
                machine.windings[0]::getVoltage,
                () -> load.wires[0].current() * ohms[0],
                () -> load.wires[1].current() * ohms[1],
                () -> load.wires[2].current() * ohms[2],
                across(load.star, machine.neutral),
                () -> rodCurrent == null ? 0.0 : rodCurrent.current(),
                volts(machine.neutral),
                volts(load.star));

        var emf = phasor(fit(s[0], CYCLES));
        var zw = windingImpedance(RPM);
        var num = C.real(0);
        var den = C.real(neutralConductance);
        var z = new C[3];
        var e = new C[3];
        for(int k = 0; k < 3; ++k) {
            e[k] = emf.times(C.polar(1, -120 * k));
            z[k] = zw.plus(C.real(0.01 + ohms[k]));
            var y = C.real(1).div(z[k]);
            num = num.plus(y.times(e[k]));
            den = den.plus(y);
        }
        // Millman: the star point sits at the admittance-weighted mean of the phase EMFs.
        var star = num.div(den);
        var expected = new C[3];
        for(int k = 0; k < 3; ++k)
            expected[k] = e[k].minus(star).div(z[k]).times(C.real(ohms[k]));

        var loadVolts = new double[]{ fit(s[1], CYCLES).magnitude(), fit(s[2], CYCLES).magnitude(),
                fit(s[3], CYCLES).magnitude() };
        return new Unbalanced(loadVolts, phasor(fit(s[4], CYCLES)), emf, fit(s[5], CYCLES).magnitude(),
                maxAbs(s[6]), expected, star, bench.net.network.isConverged(), maxAbs(s[7]), PhasorFit.rms(s[6]));
    }

    private static void assertMatchesPaper(String what, Unbalanced result) {
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(result.expected[k].abs(), result.loadVolts[k], E * 0.005,
                    what + ": phase " + (k + 1) + " voltage");
        Assertions.assertEquals(0, result.star.minus(result.expectedStar).abs(), E * 0.005,
                what + ": the load star point should be where the paper solution puts it, "
                        + result.expectedStar.abs() + " V, got " + result.star.abs() + " V");
        Assertions.assertTrue(result.converged, what + " should be converged");
    }

    @Test
    void anUnbalancedLoadOnAFloatingStarSendsTheStarPointToMillmansValue() {
        // 10, 100, 100 ohms and no neutral. The star point goes to 0.75 E towards the heavy phase,
        // which collapses to a quarter of its voltage while the light two rise to 1.52 times theirs:
        // the lost-neutral failure. ThreePhaseTransmissionTest pins the same numbers behind a
        // transformer; this is the same thing straight off the machine, with G_MIN on.
        var result = unbalanced(Earth.FLOATING);
        assertMatchesPaper("floating", result);
        Assertions.assertEquals(0.75 * E, result.star.abs(), E * 0.01, "The star point should be at 0.75 E");
        Assertions.assertEquals(0.25 * E, result.loadVolts[0], E * 0.01, "The heavy phase should fall to a quarter");
        Assertions.assertEquals(1.52 * E, result.loadVolts[1], E * 0.01, "The light phases should rise to 1.52 E");
        Assertions.assertEquals(1.52 * E, result.loadVolts[2], E * 0.01);
        Assertions.assertEquals(0, result.neutralDrift, 1e-9, "The machine's star point is the anchor, at 0 V");
    }

    @Test
    void aNeutralWireHoldsAnUnbalancedLoadsPhaseVoltages() {
        var result = unbalanced(Earth.NEUTRAL_WIRE);
        assertMatchesPaper("neutral wire", result);
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(E, result.loadVolts[k], E * 0.015,
                    "With the neutral connected phase " + (k + 1) + " should hold its voltage");
        Assertions.assertTrue(result.star.abs() < E * 0.01,
                "The load star point should stay within 1 % of the machine's, got " + result.star.abs());
    }

    @Test
    void aSingleGroundRodOnAFloatingStarChangesNothingAndCarriesNothing() {
        // One reference point cannot carry current: there is nowhere for it to go. The rod pins the
        // star point to earth in place of G_MIN's anchor and every voltage and current is the same.
        var floating = unbalanced(Earth.FLOATING);
        var grounded = unbalanced(Earth.ROD_AT_SOURCE);
        assertMatchesPaper("rod at the source", grounded);
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(floating.loadVolts[k], grounded.loadVolts[k], E * 1e-6,
                    "A single rod should not change phase " + (k + 1));
        Assertions.assertEquals(0, grounded.rodPeak, 1e-9, "and should carry no current, got " + grounded.rodPeak);
        Assertions.assertEquals(0, grounded.neutralDrift, 1e-6, "It holds the star point at earth potential");

        // A rod on the machine's star point cannot be told from no rod, because with nothing
        // grounded the solver anchors that very node. A rod on the LOAD's star point can: it is
        // where zero volts goes, and the machine's star point takes the whole offset instead.
        var atLoad = unbalanced(Earth.ROD_AT_LOAD);
        assertMatchesPaper("rod at the load", atLoad);
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(floating.loadVolts[k], atLoad.loadVolts[k], E * 1e-6,
                    "A rod on the load's star point should not change phase " + (k + 1) + " either");
        Assertions.assertEquals(0, atLoad.rodPeak, 1e-9, "and should carry no current, got " + atLoad.rodPeak);
        Assertions.assertEquals(0, atLoad.starToGround, 1e-6, "It holds the load's star point at earth potential");
        Assertions.assertEquals(floating.star.abs(), atLoad.neutralDrift, E * 1e-3,
                "so the machine's star point sits the whole star shift off earth, got " + atLoad.neutralDrift);
        Assertions.assertEquals(floating.star.abs(), floating.starToGround, E * 1e-3,
                "where with no rod it is the load's star point that sits that far off");
    }

    @Test
    void groundRodsAtBothEndsAreAPoorNeutralAndCarryTheirShare() {
        // Two working 50 ohm rods give the star points a return path of 100 ohms. That is a
        // conductance of 0.01 S against the load legs' 0.1 S, so it helps a little, not a lot:
        // the star point still goes most of the way to Millman's value. The shipped rod is 1 to
        // 5000 ohms depending on how much conductive ground surrounds it, and at the top of that
        // range this is indistinguishable from no neutral at all.
        var floating = unbalanced(Earth.FLOATING);
        var earthed = unbalanced(Earth.RODS_BOTH_ENDS);
        assertMatchesPaper("rods at both ends", earthed);
        System.out.printf("two %.0f ohm rods: heavy phase %.4f E, star point %.4f E, rod current %.4f A peak%n", ROD_OHMS,
                earthed.loadVolts[0] / E, earthed.star.abs() / E, earthed.rodPeak);
        Assertions.assertTrue(earthed.star.abs() < floating.star.abs(),
                "The earth path should pull the star point back, got " + earthed.star.abs()
                        + " against " + floating.star.abs());
        Assertions.assertTrue(earthed.star.abs() > 0.9 * floating.star.abs(),
                "but only by a little, got " + earthed.star.abs() + " against " + floating.star.abs());
        // The rod at the source carries the whole return current: star shift over the 100 ohm loop.
        Assertions.assertEquals(earthed.expectedStar.abs() / (2 * ROD_OHMS), earthed.rodPeak,
                earthed.rodPeak * 0.02, "The neutral current through the rods");
    }

    @Test
    void noPairOfRodsTheGameCanBuildIsAsGoodAsANeutralWire() {
        // groundingLowestResistance and groundingHighestResistance are 1 and 5000 ohm, so the two rods
        // of a wye grounded at both ends return the neutral current through 2 to 10000 ohm. Even at
        // the best that is not a neutral: the heavy phase of a 10/100/100 ohm load still sits about
        // fifteen percent under its voltage, where a neutral wire holds it to within a percent. At the
        // worst the rods do nothing measurable. Volts are shown over the phase EMF, so 1.0 would be a
        // neutral that held.
        var floating = unbalanced(Earth.FLOATING);
        var best = unbalanced(Earth.RODS_BOTH_ENDS, 1);
        var worst = unbalanced(Earth.RODS_BOTH_ENDS, 5000);
        var wire = unbalanced(Earth.NEUTRAL_WIRE);
        assertMatchesPaper("1 ohm rods", best);
        assertMatchesPaper("5000 ohm rods", worst);
        var middle = unbalanced(Earth.RODS_BOTH_ENDS, 50);
        System.out.printf("heavy phase over E: floating %.4f, 1 ohm rods %.4f, 50 ohm rods %.4f, 5000 ohm rods %.4f, neutral wire %.4f%n",
                floating.loadVolts[0] / E, best.loadVolts[0] / E, middle.loadVolts[0] / E, worst.loadVolts[0] / E,
                wire.loadVolts[0] / E);
        // The potential the rod at the machine's star point reaches, RMS: what a player would meet.
        System.out.printf("rod potential RMS: 1 ohm %.2f V, 50 ohm %.2f V, 5000 ohm %.2f V (machine %.2f V peak)%n",
                best.neutralRms, middle.neutralRms, worst.neutralRms, E);
        Assertions.assertTrue(best.loadVolts[0] < 0.9 * E,
                "Two 1 ohm rods should still leave the heavy phase well under its voltage, got " + best.loadVolts[0] / E + " E");
        Assertions.assertTrue(best.loadVolts[0] > 2 * floating.loadVolts[0],
                "but they should help a great deal, got " + best.loadVolts[0] / E + " E against " + floating.loadVolts[0] / E);
        Assertions.assertEquals(floating.loadVolts[0], worst.loadVolts[0], 0.01 * floating.loadVolts[0],
                "Two 5000 ohm rods should be within one percent of no neutral at all");
        Assertions.assertTrue(wire.loadVolts[0] > 0.98 * E, "A neutral wire holds it");
    }

    @Test
    void aRodThatIsNotConnectedToEarthLeavesTheSystemFloatingButStable() {
        // A rod with too few blocks round it is still a wire to ground in the island's eyes, which
        // stops G_MIN putting its own anchor on. What is left is the rod's 5e-9 S off-state
        // conductance. The star point is then referenced through a 200 megohm leak instead of a
        // 1 milliohm shunt; measured, it drifts by 76 microvolts on a 25 V machine and every
        // phase voltage is the same as the anchored case.
        var floating = unbalanced(Earth.FLOATING);
        var dead = unbalanced(Earth.DEAD_ROD);
        assertMatchesPaper("dead rod", dead);
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(floating.loadVolts[k], dead.loadVolts[k], E * 1e-4);
        System.out.printf("dead rod on the machine's star point: drifts %.3g V%n", dead.neutralDrift);
        Assertions.assertEquals(0, dead.neutralDrift, 1e-3, "The star point should not wander, got " + dead.neutralDrift);

        // That the rod counts as ground, and so switches G_MIN's anchor off, is only visible when it
        // is NOT on the node the anchor would have chosen. On the load's star point the load star is
        // the reference (through the leak) and it is the machine's star point that sits off earth;
        // if the rod did not count, the anchor would engage and it would be the other way round.
        var atLoad = unbalanced(Earth.DEAD_ROD_AT_LOAD);
        assertMatchesPaper("dead rod at the load", atLoad);
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(floating.loadVolts[k], atLoad.loadVolts[k], E * 1e-4);
        System.out.printf("dead rod on the load's star point: load star drifts %.3g V, machine star %.4f of the shift%n",
                atLoad.starToGround, atLoad.neutralDrift / floating.star.abs());
        Assertions.assertEquals(0, atLoad.starToGround, 1e-3,
                "The dead rod should be the island's reference, got " + atLoad.starToGround + " V on the load star");
        Assertions.assertEquals(floating.star.abs(), atLoad.neutralDrift, E * 1e-3,
                "and the machine's star point should sit the whole star shift off it, got " + atLoad.neutralDrift);
    }

    // ------------------------------------------------------------------------------------------
    // Robustness: sub-tick rate, frequency, start-up, and changes under a running machine
    // ------------------------------------------------------------------------------------------

    /** What a balanced floating wye reports, for comparing runs that differ in one setting. */
    private record Balanced(PhasorFit.Phasor phase, PhasorFit.Phasor line, PhasorFit.Phasor[] current,
                            double currentSum, double expectedCurrent, boolean converged, int subTicks) { }

    /**
     * Floating wye machine into a 2 ohm wye load through 10 milliohm feeders, at a shaft speed, pole
     * count and sub-tick rate. The rate is the caller's, so a test can ask for one the game would
     * not choose; with {@code subTicks <= 0} it is what the winding itself asks for, which is what
     * the game does.
     */
    private static Balanced balancedWye(float rpm, int polePairs, int subTicks) {
        var bench = new Bench(false, rpm, Math.max(subTicks, 1));
        bench.polePairs = polePairs;
        var machine = wye(bench, true);
        if(subTicks <= 0)
            bench.subTicks = machine.windings[0].requiredSubTicks();
        var feeders = feeders(bench, machine.lines, 0.01);
        wyeLoad(bench, feeders.far, 2);
        bench.settle();

        var cycles = bench.cyclesFor(4);
        var s = bench.record(cycles,
                across(machine.lines[0], machine.neutral),
                across(machine.lines[0], machine.lines[1]),
                feeders.wires[0]::current, feeders.wires[1]::current, feeders.wires[2]::current,
                () -> sum3(currents(feeders.wires)));
        var current = new PhasorFit.Phasor[]{ fit(s[2], cycles), fit(s[3], cycles), fit(s[4], cycles) };
        var expected = peak(rpm) / windingImpedance(rpm, polePairs).plus(C.real(2.01)).abs();
        return new Balanced(fit(s[0], cycles), fit(s[1], cycles), current, maxAbs(s[5]), expected,
                bench.net.network.isConverged(), bench.subTicks);
    }

    private static void assertBalancedWye(String what, Balanced r, double currentTolerance) {
        Assertions.assertEquals(ROOT3, r.line.magnitude() / r.phase.magnitude(), 1e-4,
                what + ": line voltage should be root three times phase voltage");
        Assertions.assertEquals(30, r.line.degreesFrom(r.phase), 0.05, what + ": and lead it by thirty degrees");
        assertBalanced(what, r.current[0], r.current[1], r.current[2], 1e-5);
        Assertions.assertEquals(0, r.currentSum, r.expectedCurrent * 1e-9, what + ": no neutral, so the currents sum to zero");
        Assertions.assertEquals(r.expectedCurrent, r.current[0].magnitude(), r.expectedCurrent * currentTolerance,
                what + ": phase current should be EMF over winding-plus-load impedance");
        Assertions.assertTrue(r.converged, what + " should be converged");
    }

    @Test
    void theSameFloatingWyeAtEverySubTickRateTheGameCanChoose() {
        // 4 Hz is 40 samples a cycle at 8 sub-ticks and 320 at 64. The answer should not depend on
        // which, beyond the integration error of the coarser one, and that error should shrink as
        // the rate rises. Measured, it is -0.196, -0.095, -0.047 and -0.023 %; each rate is allowed
        // about twice its own, so a model error of a fifth of a percent no longer hides in the slack.
        var rates = new int[]{ 8, 16, 32, 64 };
        var tolerance = new double[]{ 0.004, 0.002, 0.001, 0.0005 };
        var previous = Double.MAX_VALUE;
        for(int i = 0; i < rates.length; ++i) {
            var r = balancedWye(RPM, 1, rates[i]);
            var error = r.current[0].magnitude() / r.expectedCurrent - 1;
            System.out.printf("rate %d: phase %.4f, current %.5f expected %.5f (%.4f %%)%n", rates[i], r.phase.magnitude(),
                    r.current[0].magnitude(), r.expectedCurrent, 100 * error);
            assertBalancedWye(rates[i] + " sub-ticks", r, tolerance[i]);
            Assertions.assertTrue(Math.abs(error) < previous,
                    "Doubling the rate to " + rates[i] + " should shrink the error, got " + error + " after " + previous);
            previous = Math.abs(error);
        }
    }

    @Test
    void frequenciesThatDivideTwentyHertzAndOnesThatDoNot() {
        // 20 Hz and 10 Hz land on the same point of the waveform every world tick, which is what
        // broke every once-per-tick sampler in this mod. 4.5 Hz and 36 Hz do not. Each is stepped at
        // the rate its own winding asks for, at the shipped 32 samples a cycle and a cap of 64.
        //
        // That rate is not always what AcSampling.subTicksFor gives for the exact frequency. 20 Hz and
        // 10 Hz sit exactly on a power-of-two boundary (32 * 20 * 0.05 = 32), the shaft speed reaches
        // the winding as a float, and the float lands a hair over: the winding asks for 64 and 32
        // where the exact arithmetic says 32 and 16. It errs upward, so it is only a higher rate.
        var cases = new double[][]{ { 270, 1 }, { 240, 5 }, { 150, 4 }, { 270, 8 } };
        for(var c : cases) {
            var rpm = (float) c[0];
            var pairs = (int) c[1];
            var hertz = rpm * pairs / 60.0;
            var exact = AcSampling.subTicksFor(hertz, 32, 64);
            var r = balancedWye(rpm, pairs, 0);
            System.out.printf("%.1f Hz: winding asks %d sub-ticks (exact arithmetic %d): current %.5f expected %.5f (%.4f %%)%n",
                    hertz, r.subTicks, exact, r.current[0].magnitude(), r.expectedCurrent,
                    100 * (r.current[0].magnitude() / r.expectedCurrent - 1));
            Assertions.assertTrue(r.subTicks >= exact && r.subTicks <= 2 * exact,
                    hertz + " Hz: the winding should ask for " + exact + " sub-ticks or the next step up, asked " + r.subTicks);
            assertBalancedWye(hertz + " Hz", r, 0.01);
        }
    }

    /** Line currents of a floating wye into a wye load, sampled against a phase voltage of the same window. */
    private record Snapshot(double magnitude, double degrees, double neutralSum, double[] lineMagnitudes) { }

    private static Snapshot snapshot(Bench bench, Machine machine, Feeders feeders) {
        var cycles = bench.cyclesFor(4);
        var s = bench.record(cycles,
                across(machine.lines[0], machine.neutral),
                feeders.wires[0]::current, feeders.wires[1]::current, feeders.wires[2]::current,
                () -> sum3(currents(feeders.wires)));
        var reference = fit(s[0], cycles);
        var first = fit(s[1], cycles);
        return new Snapshot(first.magnitude(), first.degreesFrom(reference), maxAbs(s[4]),
                new double[]{ first.magnitude(), fit(s[2], cycles).magnitude(), fit(s[3], cycles).magnitude() });
    }

    @Test
    void aWyeMachineStartedFromRestNeverGlitchesAndSettlesToTheSteadyAnswer() {
        // The shaft goes from standstill to 240 rpm over three seconds and the island is stepped as
        // the game would step it: at whatever rate the windings ask for that tick, one sub-tick at
        // standstill. Nothing may go non-finite or overshoot the EMF on the way, and the machine must
        // arrive at exactly the answer a machine that was always at speed gives.
        var steady = new Bench(false, RPM, SUB_TICKS);
        var steadyMachine = wye(steady, true);
        var steadyFeeders = feeders(steady, steadyMachine.lines, 0.01);
        wyeLoad(steady, steadyFeeders.far, 2);
        steady.settle();
        var reference = snapshot(steady, steadyMachine, steadyFeeders);

        var bench = new Bench(true, 0, 1);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, 0.01);
        var load = wyeLoad(bench, feeders.far, 2);
        var rampTicks = 60;
        var worstPhase = 0.0;
        var worstStar = 0.0;
        for(int t = 0; t < rampTicks + 40; ++t) {
            bench.shaft.rpm = RPM * Math.min(1f, (float) t / rampTicks);
            bench.subTicks = Math.max(1, machine.windings[0].requiredSubTicks());
            if(t == 0)
                Assertions.assertEquals(1, bench.subTicks, "A machine at standstill should ask for a single sub-tick");
            bench.net.network.prepare(bench.subTicks);
            for(int sub = 0; sub < bench.subTicks; ++sub) {
                bench.net.network.singleTick();
                for(var node : new IElectricNode[]{ machine.lines[0], machine.lines[1], machine.lines[2],
                        machine.neutral, load.star, feeders.far[0] })
                    Assertions.assertTrue(Double.isFinite(node.getVoltage()),
                            "Node voltage is " + node.getVoltage() + " at tick " + t + " sub-tick " + sub);
                worstPhase = Math.max(worstPhase, Math.abs(machine.lines[0].getVoltage() - machine.neutral.getVoltage()));
                worstStar = Math.max(worstStar, Math.abs(load.star.getVoltage() - machine.neutral.getVoltage()));
            }
            bench.shaft.advance();
        }
        Assertions.assertEquals(AcSampling.subTicksFor(bench.frequency(), 32, 64), bench.subTicks,
                "At full speed the winding should ask for the rate the frequency needs");
        System.out.printf("ramp: worst phase %.4f of E %.4f, worst star %.3g%n", worstPhase, E, worstStar);
        Assertions.assertTrue(worstPhase <= E * 1.001,
                "A winding's terminal voltage cannot exceed the EMF behind it, got " + worstPhase + " V against " + E);
        Assertions.assertEquals(0, worstStar, E * 1e-6, "A balanced load's star point should never leave the machine's");

        bench.subTicks = SUB_TICKS;
        bench.settle();
        var arrived = snapshot(bench, machine, feeders);
        System.out.printf("ramp: arrived %.5f A at %.4f deg, steady %.5f A at %.4f deg%n", arrived.magnitude, arrived.degrees, reference.magnitude, reference.degrees);
        Assertions.assertEquals(reference.magnitude, arrived.magnitude, reference.magnitude * 1e-4,
                "After the ramp the machine should carry the same current as one that was always at speed");
        Assertions.assertEquals(reference.degrees, arrived.degrees, 0.01);
        Assertions.assertEquals(0, arrived.neutralSum, reference.magnitude * 1e-9);
    }

    @Test
    void aLoadAddedToARunningWyeIsSeenAndLeavesNoTraceWhenItGoes() {
        // Game-like island, so the frozen ticks after each change are in play.
        var bench = new Bench(true, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, 0.01);
        wyeLoad(bench, feeders.far, 2);
        bench.settle();
        var before = snapshot(bench, machine, feeders);

        // A single-phase load across two lines: the kind of thing a player hangs on a three-phase supply.
        var extra = bench.net.W(1f, feeders.far[0], feeders.far[1]);
        bench.ticks(3);
        var during = snapshot(bench, machine, feeders);
        System.out.println("extra load: line magnitudes " + java.util.Arrays.toString(during.lineMagnitudes)
                + " before " + before.magnitude + " neutral sum " + during.neutralSum);
        Assertions.assertTrue(Math.abs(during.lineMagnitudes[0] - during.lineMagnitudes[2]) > 0.2 * before.magnitude,
                "Line 1 should feel the new load, and line 3 should not: " + java.util.Arrays.toString(during.lineMagnitudes));
        // Line 3's current is set by its own branch: a load between lines 1 and 2 is a dipole that
        // the symmetric star leaves alone. Hung from line 1 to the star point instead, it would move
        // the star and change line 3 as well, so this is what tells the two apart.
        Assertions.assertEquals(before.lineMagnitudes[2], during.lineMagnitudes[2], before.magnitude * 1e-6,
                "Line 3 should not feel a load hung between lines 1 and 2");
        Assertions.assertTrue(during.neutralSum < 1e-6 * before.magnitude + 1e-9,
                "Three wires and no neutral still sum to zero with the extra load on, got " + during.neutralSum);

        bench.net.network.removeWire(extra);
        bench.settle();
        var after = snapshot(bench, machine, feeders);
        System.out.printf("extra load gone: %.7f A at %.5f deg against %.7f A at %.5f deg%n", after.magnitude, after.degrees, before.magnitude, before.degrees);
        Assertions.assertEquals(before.magnitude, after.magnitude, before.magnitude * 1e-6,
                "Removing the load should put the currents back exactly");
        Assertions.assertEquals(before.degrees, after.degrees, 1e-3);
        Assertions.assertTrue(bench.net.network.isConverged());
    }

    @Test
    void aWindingLostFromARunningWyeLeavesTwoInSeriesAndComesBackInStep() {
        // Single-phasing: one winding of the star goes, and the load is left across the other two
        // through the star point, with the third line dead. That is a fault a player can cause by
        // breaking a block, and the island has to hold together through it and out the other side.
        var bench = new Bench(true, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, 0.01);
        wyeLoad(bench, feeders.far, 2);
        bench.settle();
        var before = snapshot(bench, machine, feeders);

        bench.net.network.removeNode(machine.windings[2]);
        bench.ticks(3);
        var during = snapshot(bench, machine, feeders);

        // Two windings in series round the loop: (E1 - E2) over twice one branch's impedance.
        var branch = windingImpedance(RPM).plus(C.real(2.01));
        var expected = ROOT3 * E / (2 * branch.abs());
        System.out.println("single phasing: " + java.util.Arrays.toString(during.lineMagnitudes) + " expected " + expected);
        Assertions.assertEquals(expected, during.lineMagnitudes[0], expected * 0.01,
                "With one winding gone the other two drive a series loop");
        Assertions.assertEquals(during.lineMagnitudes[0], during.lineMagnitudes[1], expected * 1e-6);
        Assertions.assertEquals(0, during.lineMagnitudes[2], expected * 1e-6, "and the third line carries nothing");
        Assertions.assertEquals(0, during.neutralSum, expected * 1e-9);

        // Put a winding back at the same place on the same shaft.
        bench.winding(240, machine.lines[2], machine.neutral);
        bench.settle();
        var after = snapshot(bench, machine, feeders);
        System.out.printf("winding back: %.7f A at %.5f deg against %.7f A at %.5f deg%n", after.magnitude, after.degrees, before.magnitude, before.degrees);
        Assertions.assertEquals(before.magnitude, after.magnitude, before.magnitude * 1e-6,
                "A replaced winding should leave the machine exactly as it was");
        Assertions.assertEquals(before.degrees, after.degrees, 1e-3);
        Assertions.assertEquals(before.lineMagnitudes[2], after.lineMagnitudes[2], before.magnitude * 1e-6);
    }

    // ------------------------------------------------------------------------------------------
    // The whole chain: generator, step-up bank, line, step-down bank, load
    // ------------------------------------------------------------------------------------------

    // The shipped smallCoreAl, smallCoreK and transformerMutualInductanceMultiplier.
    private static final double CORE_AL = 1.5;
    private static final double CORE_K = 0.9999f;
    private static final float MUTUAL_MULTIPLIER = 10;

    /**
     * Stamps one transformer as {@code TransformerBlockEntity.buildCircuit} does for two defined
     * coils, splitting off. Identical to {@code ThreePhaseTransmissionTest.transformer} apart from
     * the bench it builds on, including the swap of the two coils when the primary has more turns.
     */
    private static void transformer(Bench bench, int primaryTurns, int secondaryTurns,
                                    IElectricNode p1, IElectricNode p2, IElectricNode s1, IElectricNode s2) {
        double primaryInductance = primaryTurns * primaryTurns * CORE_AL;
        double secondaryInductance = secondaryTurns * secondaryTurns * CORE_AL;
        if(primaryTurns > secondaryTurns) {
            var turns = primaryTurns;
            primaryTurns = secondaryTurns;
            secondaryTurns = turns;
            var inductance = primaryInductance;
            primaryInductance = secondaryInductance;
            secondaryInductance = inductance;
            var n1 = p1;
            var n2 = p2;
            p1 = s1;
            p2 = s2;
            s1 = n1;
            s2 = n2;
        }
        float ratio = (float) secondaryTurns / primaryTurns;
        double mutualInductance = CORE_K * primaryInductance;
        float primaryStray = (float) (primaryInductance - mutualInductance);
        float secondaryStray = (float) (secondaryInductance - ratio * ratio * mutualInductance);

        var t = bench.node();
        bench.net.W(primaryStray, p1, t);
        bench.net.W((float) mutualInductance * MUTUAL_MULTIPLIER, t, p2);
        bench.net.TR(ratio, secondaryStray, t, p2, s1, s2);
    }

    private enum Connection { STAR, DELTA }

    /** A bank's output lines, and the star points of its two sides where it has them. */
    private record Bank(IElectricNode[] lines, IElectricNode primaryStar, IElectricNode secondaryStar) { }

    /**
     * Three transformers between two sets of lines. A star side ties one end of each coil to a node
     * that nothing else touches; a delta side runs coil k from line k to line k+1.
     */
    private static Bank bank(Bench bench, IElectricNode[] in, Connection primary, Connection secondary,
                             int primaryTurns, int secondaryTurns) {
        var out = new IElectricNode[]{ bench.node(), bench.node(), bench.node() };
        var primaryStar = primary == Connection.STAR ? bench.node() : null;
        var secondaryStar = secondary == Connection.STAR ? bench.node() : null;
        for(int k = 0; k < 3; ++k)
            transformer(bench, primaryTurns, secondaryTurns,
                    in[k], primary == Connection.STAR ? primaryStar : in[(k + 1) % 3],
                    out[k], secondary == Connection.STAR ? secondaryStar : out[(k + 1) % 3]);
        return new Bank(out, primaryStar, secondaryStar);
    }

    private static final int STEP = 4;
    private static final double LEAD_OHMS = 0.001;

    /**
     * A wye alternator with no neutral and no earth, a 10:40 delta-star bank, three conductors of
     * {@code lineOhms} each, a 40:10 star-delta bank and a delta load. Nothing in it is referenced to
     * ground but the one G_MIN shunt, and two star points float.
     */
    private record Chain(Bench bench, Machine machine, Feeders leads, Bank up, Feeders line, Bank down, Load load) { }

    private static Chain chain(double lineOhms, double loadOhms) {
        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var leads = feeders(bench, machine.lines, LEAD_OHMS);
        var up = bank(bench, leads.far, Connection.DELTA, Connection.STAR, 10, 10 * STEP);
        var line = feeders(bench, up.lines, lineOhms);
        var down = bank(bench, line.far, Connection.STAR, Connection.DELTA, 10 * STEP, 10);
        var load = deltaLoad(bench, down.lines, loadOhms);
        return new Chain(bench, machine, leads, up, line, down, load);
    }

    @Test
    void aWyeGeneratorFeedsADeltaLoadThroughAStepUpBankALongLineAndAStepDownBank() {
        var chain = chain(5, 10);
        var bench = chain.bench;
        bench.settle();
        var cycles = bench.cyclesFor(CYCLES);
        var starUp = chain.up.secondaryStar;
        var starDown = chain.down.primaryStar;
        var s = bench.record(cycles,
                across(chain.machine.lines[0], chain.machine.lines[1]),          // 0 generator line to line
                across(chain.down.lines[0], chain.down.lines[1]),                // 1 load line to line
                across(chain.up.lines[0], starUp),                               // 2 line phase voltage, sending end
                across(chain.line.far[0], starDown),                             // 3 line phase voltage, receiving end
                chain.line.wires[0]::current, chain.line.wires[1]::current, chain.line.wires[2]::current, // 4-6
                () -> sum3(currents(chain.line.wires)),                          // 7
                across(starDown, starUp),                                        // 8 the two star points
                volts(starUp),                                                   // 9
                chain.load.wires[0]::current, chain.load.wires[1]::current, chain.load.wires[2]::current, // 10-12
                chain.leads.wires[0]::current, chain.leads.wires[1]::current, chain.leads.wires[2]::current, // 13-15
                volts(chain.machine.lines[0]), volts(chain.machine.lines[1]), volts(chain.machine.lines[2])); // 16-18

        var generator = fit(s[0], cycles);
        var received = fit(s[1], cycles);
        var sending = fit(s[2], cycles);
        var arriving = fit(s[3], cycles);
        System.out.printf("generator line %.4f, received line %.4f (%.5f), shift %.4f deg%n",
                generator.magnitude(), received.magnitude(), received.magnitude() / generator.magnitude(),
                received.degreesFrom(generator));
        System.out.printf("line phase voltage: sending %.4f arriving %.4f%n", sending.magnitude(), arriving.magnitude());
        System.out.printf("star points: %.3g apart, up star %.3g above ground%n", maxAbs(s[8]), maxAbs(s[9]));

        // The two ratios cancel end to end, which would hide a ratio wrong by the same factor on both
        // banks, so look at the middle of the chain too. A delta primary puts the generator's LINE
        // voltage across each coil, so the star secondary's phase voltage is four times that; and the
        // delta secondary's coil sees a quarter of the line's phase voltage, which is the load's line
        // voltage. Each sits a fraction of a percent under, the drop in the coils and the line.
        Assertions.assertEquals(4 * generator.magnitude(), sending.magnitude(), 0.01 * 4 * generator.magnitude(),
                "The step-up bank should make four times the generator's line voltage across each star coil");
        Assertions.assertEquals(arriving.magnitude() / 4, received.magnitude(), 0.01 * arriving.magnitude() / 4,
                "and the step-down bank should give the load a quarter of the arriving phase voltage");
        Assertions.assertTrue(arriving.magnitude() < sending.magnitude(), "The line should drop some of it");

        // Ratio 1:4 up and 4:1 down cancels; the two root threes (delta-star up, star-delta down) cancel too.
        Assertions.assertEquals(1.0, received.magnitude() / generator.magnitude(), 0.05,
                "End to end the bank pair should hand the load about the voltage the generator made");
        // Dyn11 moves the phase +30, Yd1 moves the line voltage -30: together, nothing.
        Assertions.assertEquals(0, received.degreesFrom(generator), 0.5,
                "and should shift nothing, the two thirty degree shifts cancelling");

        var i1 = fit(s[4], cycles);
        var i2 = fit(s[5], cycles);
        var i3 = fit(s[6], cycles);
        assertBalanced("line", i1, i2, i3, 1e-4);
        Assertions.assertEquals(0, maxAbs(s[7]), i1.magnitude() * 1e-9, "The three line conductors sum to zero");

        // Nothing floats off: both star points sit where the balanced set puts them.
        Assertions.assertEquals(0, maxAbs(s[8]), E * 1e-6, "The two star points should coincide");
        Assertions.assertTrue(bench.net.network.isConverged());

        // Efficiency at the terminals of the machine: mean of the sum of v * i over the three leads,
        // against the power in the load legs.
        var pIn = 0.0;
        for(int n = 0; n < s[0].length; ++n)
            pIn += s[16][n] * s[13][n] + s[17][n] * s[14][n] + s[18][n] * s[15][n];
        pIn /= s[0].length;
        var pOut = 0.0;
        for(int k = 0; k < 3; ++k) {
            var rms = PhasorFit.rms(s[10 + k]);
            pOut += rms * rms * 10;
        }
        System.out.printf("power in %.3f W, power out %.3f W, efficiency %.5f%n", pIn, pOut, pOut / pIn);
        Assertions.assertTrue(pIn > 0, "Power should flow out of the machine, got " + pIn);
        Assertions.assertTrue(pOut / pIn < 1, "and the load cannot get more than the machine gave");
    }

    /** Efficiency of the chain at its terminals, and the load voltage, over whole cycles. */
    private record Delivery(double powerIn, double powerOut, double loadLineVoltage) {
        double efficiency() {
            return powerOut / powerIn;
        }
    }

    /**
     * Mean power leaving the machine's three leads against mean power in three load legs of
     * {@code legOhms}. The leads are 1 milliohm, so their voltage is the machine's terminal voltage
     * and their current the line current, and no reference is needed because the three sum to zero.
     */
    private static Delivery delivery(Bench bench, Feeders leads, Machine machine, Load load, double legOhms,
                                     IElectricNode[] loadLines) {
        bench.settle();
        var cycles = bench.cyclesFor(CYCLES);
        var s = bench.record(cycles,
                volts(machine.lines[0]), volts(machine.lines[1]), volts(machine.lines[2]),
                leads.wires[0]::current, leads.wires[1]::current, leads.wires[2]::current,
                load.wires[0]::current, load.wires[1]::current, load.wires[2]::current,
                across(loadLines[0], loadLines[1]));
        var powerIn = 0.0;
        for(int n = 0; n < s[0].length; ++n)
            powerIn += s[0][n] * s[3][n] + s[1][n] * s[4][n] + s[2][n] * s[5][n];
        powerIn /= s[0].length;
        var powerOut = 0.0;
        for(int k = 0; k < 3; ++k) {
            var rms = PhasorFit.rms(s[6 + k]);
            powerOut += rms * rms * legOhms;
        }
        return new Delivery(powerIn, powerOut, fit(s[9], cycles).magnitude());
    }

    @Test
    void theBanksAreWhatMakeALongLineWorthLaying() {
        // The same three 5 ohm conductors carrying the same delta load, once through a 10:40 up and
        // 40:10 down pair of banks and once straight from the machine. The step-up divides the line
        // current by four and so the conductor loss by sixteen, which is the whole reason to transmit
        // at a higher voltage; it is worth a test because it is the reason a player builds any of this.
        var chain = chain(5, 10);
        var withBanks = delivery(chain.bench, chain.leads, chain.machine, chain.load, 10, chain.down.lines);

        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var leads = feeders(bench, machine.lines, LEAD_OHMS);
        var line = feeders(bench, leads.far, 5);
        var load = deltaLoad(bench, line.far, 10);
        var direct = delivery(bench, leads, machine, load, 10, line.far);

        System.out.printf("with banks: %.3f W in, %.3f W out, %.5f, load line voltage %.4f%n",
                withBanks.powerIn, withBanks.powerOut, withBanks.efficiency(), withBanks.loadLineVoltage);
        System.out.printf("direct:     %.3f W in, %.3f W out, %.5f, load line voltage %.4f%n",
                direct.powerIn, direct.powerOut, direct.efficiency(), direct.loadLineVoltage);
        Assertions.assertTrue(withBanks.efficiency() > 0.90 && withBanks.efficiency() < 1,
                "Through the banks most of the power should arrive, got " + withBanks.efficiency());
        Assertions.assertTrue(direct.efficiency() < 0.5,
                "Straight down the same conductors most of it should be lost in them, got " + direct.efficiency());
        Assertions.assertTrue(withBanks.loadLineVoltage > 1.5 * direct.loadLineVoltage,
                "and the load should see far more voltage with the banks");
    }

    // ------------------------------------------------------------------------------------------
    // Three conductors that are not the same length
    // ------------------------------------------------------------------------------------------

    private static final C ROTATE = C.polar(1, 120);

    /**
     * Negative-sequence over positive-sequence line-to-line voltage, in percent: the ratio the
     * standards call voltage unbalance. Line-to-line, because with no neutral there is no phase
     * voltage to be unbalanced about, and it has no zero-sequence part.
     */
    private static double unbalancePercent(C ab, C bc, C ca) {
        var a2 = ROTATE.times(ROTATE);
        var positive = ab.plus(ROTATE.times(bc)).plus(a2.times(ca));
        var negative = ab.plus(a2.times(bc)).plus(ROTATE.times(ca));
        return 100 * negative.abs() / positive.abs();
    }

    /** The NEMA definition: the largest deviation from the mean magnitude, over the mean. */
    private static double nemaPercent(C... lineToLine) {
        var mean = (lineToLine[0].abs() + lineToLine[1].abs() + lineToLine[2].abs()) / 3;
        var worst = 0.0;
        for(var v : lineToLine)
            worst = Math.max(worst, Math.abs(v.abs() - mean));
        return 100 * worst / mean;
    }

    /** What the load sees, from the solver and from Millman's theorem on paper. */
    private record Unequal(C[] measured, C[] paper) {
        double measuredUnbalance() {
            return unbalancePercent(measured[0], measured[1], measured[2]);
        }

        double paperUnbalance() {
            return unbalancePercent(paper[0], paper[1], paper[2]);
        }
    }

    private static Unequal unequalConductors(double[] feederOhms, double loadOhms) {
        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var feeders = feeders(bench, machine.lines, feederOhms);
        wyeLoad(bench, feeders.far, loadOhms);
        bench.settle();
        var cycles = bench.cyclesFor(CYCLES);
        var s = bench.record(cycles,
                across(feeders.far[0], feeders.far[1]),
                across(feeders.far[1], feeders.far[2]),
                across(feeders.far[2], feeders.far[0]));
        var measured = new C[]{ phasor(fit(s[0], cycles)), phasor(fit(s[1], cycles)), phasor(fit(s[2], cycles)) };

        var num = C.real(0);
        var den = C.real(0);
        var z = new C[3];
        var e = new C[3];
        for(int k = 0; k < 3; ++k) {
            e[k] = C.polar(E, -120 * k);
            z[k] = windingImpedance(RPM).plus(C.real(feederOhms[k] + loadOhms));
            var y = C.real(1).div(z[k]);
            num = num.plus(y.times(e[k]));
            den = den.plus(y);
        }
        var star = num.div(den);
        var v = new C[3];
        for(int k = 0; k < 3; ++k)
            v[k] = e[k].minus(star).div(z[k]).times(C.real(loadOhms));
        return new Unequal(measured, new C[]{ v[0].minus(v[1]), v[1].minus(v[2]), v[2].minus(v[0]) });
    }

    @Test
    void threeConductorsOfDifferentLengthUnbalanceTheLoadByWhatMillmanSays() {
        // Wire resistance is proportional to length (resistancePerItem times the items used), so
        // runs of 100, 120 and 150 blocks are 1 : 1.2 : 1.5 in ohms. Nobody lays three identical
        // ones. The load is a 2 ohm wye and the middle case is the one a player is likely to build.
        var loadOhms = 2.0;
        for(var base : new double[]{ 0.05, 0.25, 1.0 }) {
            var equal = unequalConductors(new double[]{ base, base, base }, loadOhms);
            var different = unequalConductors(new double[]{ base, 1.2 * base, 1.5 * base }, loadOhms);
            System.out.printf("base %.2f ohm: equal %.5f %%, 1:1.2:1.5 measured %.4f %% paper %.4f %%, NEMA %.4f %%%n",
                    base, equal.measuredUnbalance(), different.measuredUnbalance(), different.paperUnbalance(),
                    nemaPercent(different.measured));
            Assertions.assertEquals(0, equal.measuredUnbalance(), 1e-4,
                    "Identical conductors should leave the line voltages perfectly balanced");
            Assertions.assertEquals(different.paperUnbalance(), different.measuredUnbalance(),
                    Math.max(0.02, different.paperUnbalance() * 0.02),
                    "The solver's unbalance should be the paper solution's, " + base + " ohm base run");
            Assertions.assertTrue(different.measuredUnbalance() > 0.1,
                    "Unequal runs must actually unbalance the load for this test to mean anything");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Split transmission lines: what a one-sub-tick delay does to three phases
    // ------------------------------------------------------------------------------------------

    /** The line currents and the load's line-to-line voltages of a floating wye into a wye load. */
    private record LineRun(C[] current, C[] loadLine) { }

    /**
     * Wye machine, three conductors of {@code ohms} each, wye load of 2 ohms. When {@code split} is
     * true the conductors are {@link TransmissionLinePort} pairs joining two islands stepped in
     * lockstep, as {@code TransmissionLine.makePortPair} builds them; otherwise they are plain wires
     * inside one island, which is what a line is when {@code splittingTransmissionLines} is off or
     * the line is under {@code transmissionLineThreshold}.
     */
    private static LineRun overLines(double[] ohms, boolean split, int subTicks) {
        var machineSide = new Bench(false, RPM, subTicks);
        var machine = wye(machineSide, true);
        var leads = feeders(machineSide, machine.lines, LEAD_OHMS);
        var loadSide = split ? new Network(true) : machineSide.net;

        var far = new IElectricNode[3];
        for(int k = 0; k < 3; ++k) {
            far[k] = loadSide.N();
            if(split) {
                var port1 = new TransmissionLinePort(leads.far[k], (float) ohms[k], null);
                var port2 = new TransmissionLinePort(far[k], (float) ohms[k], null);
                port1.other = port2;
                port2.other = port1;
                machineSide.net.network.addNode(port1);
                loadSide.network.addNode(port2);
            } else {
                machineSide.net.W((float) ohms[k], leads.far[k], far[k]);
            }
        }
        var star = loadSide.N();
        for(int k = 0; k < 3; ++k)
            loadSide.W(2f, far[k], star);

        var cycles = machineSide.cyclesFor(8);
        var ticks = machineSide.ticksFor(cycles);
        for(int t = 0; t < ticks; ++t) {
            step(machineSide, loadSide, split, subTicks, null);
        }
        var samples = new double[6][ticks * subTicks];
        var at = new int[]{ 0 };
        var probes = new DoubleSupplier[]{
                leads.wires[0]::current, leads.wires[1]::current, leads.wires[2]::current,
                across(far[0], far[1]), across(far[1], far[2]), across(far[2], far[0]) };
        for(int t = 0; t < ticks; ++t)
            step(machineSide, loadSide, split, subTicks, () -> {
                for(int p = 0; p < probes.length; ++p) {
                    var value = probes[p].getAsDouble();
                    if(!Double.isFinite(value))
                        Assertions.fail("Probe " + p + " is " + value);
                    samples[p][at[0]] = value;
                }
                ++at[0];
            });
        var fitted = new C[6];
        for(int p = 0; p < 6; ++p)
            fitted[p] = phasor(fit(samples[p], cycles));
        return new LineRun(new C[]{ fitted[0], fitted[1], fitted[2] }, new C[]{ fitted[3], fitted[4], fitted[5] });
    }

    /** One world tick of both islands in lockstep, with an optional callback after every sub-tick. */
    private static void step(Bench machineSide, Network loadSide, boolean split, int subTicks, Runnable afterSubTick) {
        machineSide.net.network.prepare(subTicks);
        if(split)
            loadSide.network.prepare(subTicks);
        for(int s = 0; s < subTicks; ++s) {
            machineSide.net.network.singleTick();
            if(split)
                loadSide.network.singleTick();
            if(afterSubTick != null)
                afterSubTick.run();
        }
        machineSide.shaft.advance();
    }

    @Test
    void threeSplitLinesOfEqualLengthDelayEveryPhaseTheSameAndKeepTheSequence() {
        var ohms = new double[]{ 1, 1, 1 };
        var direct = overLines(ohms, false, SUB_TICKS);
        var split = overLines(ohms, true, SUB_TICKS);
        for(int k = 0; k < 3; ++k)
            System.out.printf("equal 1 ohm, phase %d: |I| direct %.4f split %.4f (%.3f%%), phase shift %.3f deg%n", k + 1,
                    direct.current[k].abs(), split.current[k].abs(),
                    100 * (split.current[k].abs() / direct.current[k].abs() - 1),
                    PhasorFit.wrapDegrees(split.current[k].degrees() - direct.current[k].degrees()));
        var shifts = new double[3];
        for(int k = 0; k < 3; ++k)
            shifts[k] = PhasorFit.wrapDegrees(split.current[k].degrees() - direct.current[k].degrees());
        Assertions.assertEquals(shifts[0], shifts[1], 0.05, "Phases 1 and 2 should be delayed alike");
        Assertions.assertEquals(shifts[0], shifts[2], 0.05, "Phases 1 and 3 should be delayed alike");
        Assertions.assertTrue(Math.abs(shifts[0]) > 1, "The split should cost some phase, or this test guards nothing, got " + shifts[0]);
        Assertions.assertEquals(-120, PhasorFit.wrapDegrees(split.current[1].degrees() - split.current[0].degrees()), 0.05);
        Assertions.assertEquals(0, unbalancePercent(split.loadLine[0], split.loadLine[1], split.loadLine[2]), 0.01,
                "Equal delays keep the set balanced, only rotated");
    }

    @Test
    void splitLinesOfDifferentResistanceDelayEachPhaseByADifferentAngle() {
        // A split line always costs exactly one sub-tick, however long it is, so three split lines are
        // never delayed by different TIMES. What differs is the ANGLE that one sub-tick becomes once
        // it has been through the rest of the circuit, and that depends on the conductor's own
        // resistance: the shorter the line, the larger the error. The set here is the sort of thing
        // a player lays -- runs of 1.0, 1.2 and 1.5 ohm, 1.3 to 2 km of copper wire.
        var ohms = new double[]{ 1, 1.2, 1.5 };
        var direct = overLines(ohms, false, SUB_TICKS);
        var split = overLines(ohms, true, SUB_TICKS);
        var shifts = new double[3];
        for(int k = 0; k < 3; ++k) {
            shifts[k] = PhasorFit.wrapDegrees(split.current[k].degrees() - direct.current[k].degrees());
            System.out.printf("%.1f ohm, phase %d: |I| direct %.4f split %.4f (%.3f%%), shift %.3f deg%n", ohms[k], k + 1,
                    direct.current[k].abs(), split.current[k].abs(),
                    100 * (split.current[k].abs() / direct.current[k].abs() - 1), shifts[k]);
        }
        var spread = Math.max(shifts[0], Math.max(shifts[1], shifts[2])) - Math.min(shifts[0], Math.min(shifts[1], shifts[2]));
        System.out.printf("load unbalance: direct %.4f %%, split %.4f %%; spread of phase shifts %.3f deg%n",
                unbalancePercent(direct.loadLine[0], direct.loadLine[1], direct.loadLine[2]),
                unbalancePercent(split.loadLine[0], split.loadLine[1], split.loadLine[2]), spread);
        for(int k = 0; k < 3; ++k)
            Assertions.assertTrue(Math.abs(shifts[k]) > 2, "Every split conductor should be visibly shifted, phase " + (k + 1) + " by " + shifts[k]);
        Assertions.assertTrue(spread > 1,
                "Unequal conductors should be shifted by unequal angles, spread only " + spread + " deg");
        Assertions.assertTrue(shifts[0] > shifts[1] && shifts[1] > shifts[2],
                "and the shorter the run the larger the shift: " + java.util.Arrays.toString(shifts));
    }

    @Test
    void aSplitLineJustAboveTheThresholdIsFarWorseThanALongOne() {
        // transmissionLineThreshold is 0.2 ohm, so the lines that split first are the short ones, and
        // they are where the port model is worst: with so little resistance in the port the exchange
        // barely damps. Just above the threshold the current is wrong by tens of percent and tens of
        // degrees; at 1 ohm it is about five percent and seven degrees.
        var near = overLines(new double[]{ 0.25, 0.25, 0.25 }, true, SUB_TICKS);
        var nearDirect = overLines(new double[]{ 0.25, 0.25, 0.25 }, false, SUB_TICKS);
        var far = overLines(new double[]{ 1, 1, 1 }, true, SUB_TICKS);
        var farDirect = overLines(new double[]{ 1, 1, 1 }, false, SUB_TICKS);
        var nearShift = PhasorFit.wrapDegrees(near.current[0].degrees() - nearDirect.current[0].degrees());
        var farShift = PhasorFit.wrapDegrees(far.current[0].degrees() - farDirect.current[0].degrees());
        var nearError = near.current[0].abs() / nearDirect.current[0].abs() - 1;
        var farError = far.current[0].abs() / farDirect.current[0].abs() - 1;
        System.out.printf("0.25 ohm: %.3f deg, %.3f %%; 1 ohm: %.3f deg, %.3f %%%n", nearShift, 100 * nearError, farShift, 100 * farError);
        Assertions.assertTrue(nearShift > 2 * farShift, "A 0.25 ohm split line should be shifted far more than a 1 ohm one, got "
                + nearShift + " against " + farShift);
        Assertions.assertTrue(nearError > 4 * farError, "and its magnitude should be further out, got " + nearError + " against " + farError);
    }

    // ------------------------------------------------------------------------------------------
    // What a one-branch wattmeter reads on a three-phase line
    // ------------------------------------------------------------------------------------------

    /** Mean power the meters read over whole cycles, and the power the load really took. */
    private record MeterRun(double[] readings, double loadPower) { }

    /**
     * A floating wye machine into a wye load with a {@link WattmeterWire} in line 1, built the way
     * {@code PowerGaugeBlockEntity} builds one: terminal 0 on the supply side, terminal 1 to the
     * load, terminal 2 the return of the voltage branch across 0 and 2. {@code sense} says where a
     * player has wired terminal 2: to the load's star point, or to line 2. With {@code second} a
     * second meter goes in line 3 with its own terminal 2 on line 2, which is the two-wattmeter
     * connection for a three-wire system.
     */
    private static MeterRun metered(boolean senseToStar, boolean second, double... loadOhms) {
        var bench = new Bench(false, RPM, SUB_TICKS);
        var machine = wye(bench, true);
        var lines = new IElectricNode[]{ bench.node(), machine.lines[1], bench.node() };
        var star = bench.node();

        var shunt = bench.net.W(100_000f, machine.lines[0], senseToStar ? star : machine.lines[1]);
        var meter = new WattmeterWire(0.01, shunt, machine.lines[0], lines[0]);
        bench.net.network.addWire(meter);
        WattmeterWire meter3 = null;
        if(second) {
            var shunt3 = bench.net.W(100_000f, machine.lines[2], machine.lines[1]);
            meter3 = new WattmeterWire(0.01, shunt3, machine.lines[2], lines[2]);
            bench.net.network.addWire(meter3);
        } else {
            bench.net.W(0.01f, machine.lines[2], lines[2]);
        }
        var legs = new ElectricWire[3];
        for(int k = 0; k < 3; ++k)
            legs[k] = bench.net.W((float) loadOhms[loadOhms.length == 1 ? 0 : k], lines[k], star);

        bench.settle();
        var ticks = bench.ticksFor(bench.cyclesFor(CYCLES));
        var sums = new double[2];
        var powerSamples = new double[3];
        var loadPower = 0.0;
        var meters = new WattmeterWire[]{ meter, meter3 };
        // The gauge drains once per world tick, from electricalTick(); do the same.
        meter.drainRealPower();
        if(meter3 != null)
            meter3.drainRealPower();
        for(int t = 0; t < ticks; ++t) {
            bench.tick();
            for(int m = 0; m < 2; ++m)
                if(meters[m] != null)
                    sums[m] += meters[m].drainRealPower();
            for(int k = 0; k < 3; ++k)
                powerSamples[k] += legs[k].power();
        }
        for(int k = 0; k < 3; ++k)
            loadPower += powerSamples[k] / ticks;
        return new MeterRun(new double[]{ sums[0] / ticks, sums[1] / ticks }, loadPower);
    }

    @Test
    void oneWattmeterOnAThreePhaseLineReadsAThirdOrAHalfNeverTheTotal() {
        // The power gauge and energy meter have one current branch and one voltage branch. On a
        // three-phase line that is one phase's current times one voltage the player chose, so:
        //   * terminal 2 on the star point: one phase's power, a third of a balanced load's;
        //   * terminal 2 on another line: I1 * V12, which for a resistive balanced load is
        //     sqrt(3) * V * I * cos 30 = half the total;
        // and neither is the total. Not fixed: a meter that knew about three phases would need a
        // second current branch, and the two-wattmeter connection below already does it with two.
        var star = metered(true, false, 2);
        var lineToLine = metered(false, false, 2);
        System.out.printf("total %.4f W; sense to star %.4f W (%.4f); sense to line 2 %.4f W (%.4f)%n", star.loadPower,
                star.readings[0], star.readings[0] / star.loadPower, lineToLine.readings[0],
                lineToLine.readings[0] / lineToLine.loadPower);
        Assertions.assertEquals(1.0 / 3, star.readings[0] / star.loadPower, 0.005,
                "Terminal 2 on the star point should read one phase's power");
        Assertions.assertEquals(0.5, lineToLine.readings[0] / lineToLine.loadPower, 0.005,
                "Terminal 2 on another line should read half of a resistive balanced load");
    }

    @Test
    void twoWattmetersWithTheirSenseTerminalsOnTheThirdLineReadTheTotalEvenUnbalanced() {
        // Blondel: on three wires the power is the sum of two line currents times their voltages to
        // the third line, whatever the load. This is what a player with two gauges can build.
        var run = metered(false, true, 10, 100, 100);
        var sum = run.readings[0] + run.readings[1];
        System.out.printf("unbalanced 10/100/100: total %.4f W, meters %.4f + %.4f = %.4f%n", run.loadPower,
                run.readings[0], run.readings[1], sum);
        // The meters' own 10 milliohm series branches take a little.
        Assertions.assertEquals(run.loadPower, sum, run.loadPower * 0.01,
                "Two meters should add up to the load's power");
        Assertions.assertTrue(Math.abs(run.readings[0] - run.readings[1]) > 0.1 * run.loadPower,
                "and on this load neither one alone is close to half of it, or the test is not unbalanced enough");
    }

    @Test
    void aNeutralWireOnABalancedLoadCarriesNothingAndChangesNothing() {
        // Four wires against three, on a balanced load: the neutral is the sum of three currents that
        // cancel, so it is zero at every instant and every other number is what the floating star gave.
        var floatingCurrent = 0.0;
        double[] neutralPeak = new double[1];
        for(int wired = 0; wired < 2; ++wired) {
            var bench = new Bench(false, RPM, SUB_TICKS);
            var machine = wye(bench, true);
            var feeders = feeders(bench, machine.lines, 0.01);
            var load = wyeLoad(bench, feeders.far, 2);
            var neutral = wired == 1 ? bench.net.W(0.01f, machine.neutral, load.star) : null;
            bench.settle();
            var cycles = bench.cyclesFor(CYCLES);
            var s = bench.record(cycles, feeders.wires[0]::current, () -> neutral == null ? 0.0 : neutral.current());
            var magnitude = fit(s[0], cycles).magnitude();
            if(wired == 0)
                floatingCurrent = magnitude;
            else {
                neutralPeak[0] = maxAbs(s[1]);
                Assertions.assertEquals(floatingCurrent, magnitude, floatingCurrent * 1e-6,
                        "A neutral wire should not change a balanced line current");
            }
        }
        Assertions.assertEquals(0, neutralPeak[0], floatingCurrent * 1e-6,
                "A balanced load should send nothing down the neutral, got " + neutralPeak[0]);
    }
}
