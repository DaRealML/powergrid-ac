package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.ThyratronWire;

/**
 * A thyratron on an alternating anode supply: the phase-control case it exists for.
 *
 * <p>{@link ThyratronTest} pins the steady-state behaviour, one solve per call. Under this fork's
 * solver an alternating island is stepped many times per world tick and {@code preSolve()} runs at
 * every one of them, so a rule written as "two calls" means two sub-ticks, and how long that is
 * depends on the sub-tick count rather than on the tube.
 *
 * <p>The reference is worked out here from the lit-state model {@code V = Vh + I/G} and the strike
 * rule, not by calling the wire: the tube strikes when the supply passes
 * {@code max(Vh, Vb - mu*Vg)} and goes out when the current falls to the holding current, which
 * with a series resistor R is the supply passing {@code Vh + Ih*(R + 1/G)} on its way down.
 */
public class ThyratronAcTest extends TestHelper {
    // The component's own defaults (ThyratronComponent): mu 20, Vb 60, Vh = 0.2 Vb clamped to 8..16,
    // Ih = 0.5 / Vb, and a discharge conductance of 50 S.
    private static final float MU = 20, VB = 60, VH = 12, IH = 0.5f / 60, G_ON = 50;

    private static final double FREQUENCY = 50;
    private static final double PEAK = 150;
    private static final double LOAD = 10;

    private static final int SETTLE_TICKS = 20;
    // A whole number of 5-cycle patterns: at 50 Hz the sample phases repeat every two world ticks.
    private static final int MEASURE_TICKS = 40;

    /** What the tube did at every sub-tick of the measured window. */
    private static final class Trace {
        final int subTicks;
        final boolean[] lit;
        final double[] current;
        final boolean litBefore;

        Trace(int subTicks, boolean[] lit, double[] current, boolean litBefore) {
            this.subTicks = subTicks;
            this.lit = lit;
            this.current = current;
            this.litBefore = litBefore;
        }

        double cycles() {
            return MEASURE_TICKS * AcSampling.TICK_SECONDS * FREQUENCY;
        }

        double degreesPerSubTick() {
            return 360 * FREQUENCY * AcSampling.TICK_SECONDS / subTicks;
        }

        /** Share of the cycle the tube spent lit, in degrees. */
        double conductionDegrees() {
            var count = 0;
            for(var l : lit)
                if(l)
                    ++count;
            return 360.0 * count / lit.length;
        }

        int strikes() {
            var count = 0;
            var previous = litBefore;
            for(var l : lit) {
                if(l && !previous)
                    ++count;
                previous = l;
            }
            return count;
        }

        int extinctions() {
            var count = 0;
            var previous = litBefore;
            for(var l : lit) {
                if(!l && previous)
                    ++count;
                previous = l;
            }
            return count;
        }

        /** Most consecutive sub-ticks the tube stayed lit while its current ran the wrong way. */
        int longestReverseRun() {
            var longest = 0;
            var run = 0;
            for(int i = 0; i < lit.length; ++i) {
                run = lit[i] && current[i] < 0 ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            return longest;
        }
    }

    /** Supply, series resistance, thyratron to ground, grid held at a fixed voltage. */
    private static final class Rig {
        final Network net = new Network();
        final ThyratronWire tube;

        Rig(double gridVolts) {
            this(gridVolts, FREQUENCY);
        }

        Rig(double gridVolts, double frequency) {
            var ground = net.N();
            net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
            var hot = net.N();
            net.network.addNode(new ACVoltageSourceCoupling(hot, null, 0.001f, PEAK, frequency));
            var anode = net.N();
            net.W((float) LOAD, hot, anode);
            var grid = net.V((float) gridVolts);
            tube = new ThyratronWire(MU, VB, VH, IH, G_ON, ground, anode, grid);
            net.network.addWire(tube);
        }

        Trace run(int subTicks) {
            var lit = new boolean[MEASURE_TICKS * subTicks];
            var current = new double[lit.length];
            var litBefore = false;
            var index = 0;
            for(int t = 0; t < SETTLE_TICKS + MEASURE_TICKS; ++t) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s) {
                    net.network.singleTick();
                    if(t >= SETTLE_TICKS) {
                        lit[index] = tube.isLit();
                        current[index] = tube.current();
                        ++index;
                    } else {
                        litBefore = tube.isLit();
                    }
                }
            }
            return new Trace(subTicks, lit, current, litBefore);
        }
    }

    /** Conduction angle of the ideal tube: strike on the way up, go out at the holding current. */
    private static double idealConductionDegrees(double gridVolts) {
        var strike = Math.max(VH, VB - MU * gridVolts);
        var extinguish = VH + IH * (LOAD + 1.0 / G_ON);
        return Math.toDegrees(Math.PI - Math.asin(extinguish / PEAK) - Math.asin(strike / PEAK));
    }

    @Test
    void conductionAngleFollowsThePhysicsWhateverTheSubTickCount() {
        // A late strike and a late extinction both cost the same number of sub-ticks, so the
        // conduction angle should be right to within the quantisation of the sampling. It was not:
        // the extinction was held for two sub-ticks and the strike was not, which made the angle
        // too wide by one sub-tick -- 14 degrees at 64 sub-ticks, 28 at 32.
        for(var gridVolts : new double[]{-2, 0, 1}) {
            var ideal = idealConductionDegrees(gridVolts);
            for(var subTicks : new int[]{32, 64, 128}) {
                var trace = new Rig(gridVolts).run(subTicks);
                var error = trace.conductionDegrees() - ideal;
                var allowed = 0.25 * trace.degreesPerSubTick();
                Assertions.assertTrue(Math.abs(error) <= allowed,
                        String.format("Vg=%+.0f V at %d sub-ticks: conduction angle %.1f deg against %.1f deg ideal "
                                        + "(error %+.1f deg, allowed %.1f deg = a quarter of one sub-tick)",
                                gridVolts, subTicks, trace.conductionDegrees(), ideal, error, allowed));
            }
        }
    }

    @Test
    void aLitTubeDoesNotConductBackwardsForMoreThanOneSubTick() {
        // The lit state is a conductance with a current source behind it, which carries current both
        // ways: below the holding voltage it is a source feeding the supply. Only the extinction
        // rule stops that, so every sub-tick it is slow to act is a sub-tick of reverse current.
        for(var subTicks : new int[]{32, 64, 128}) {
            var trace = new Rig(0).run(subTicks);
            Assertions.assertTrue(trace.longestReverseRun() <= 1,
                    "At " + subTicks + " sub-ticks the tube stayed lit for " + trace.longestReverseRun()
                            + " consecutive sub-ticks with a reversed current");
        }
    }

    @Test
    void phaseControlWidensTheConductionAngleAsTheGridRises() {
        // The reason to build one. Grid voltages far enough apart that every step is more than a
        // sub-tick of conduction at 64 sub-ticks (14 degrees).
        var previous = 0.0;
        for(var gridVolts : new double[]{-4, -2, 0, 3}) {
            var degrees = new Rig(gridVolts).run(64).conductionDegrees();
            Assertions.assertTrue(degrees > previous,
                    String.format("Vg=%+.0f V conducts %.1f deg, no more than the %.1f deg of the lower grid voltage",
                            gridVolts, degrees, previous));
            previous = degrees;
        }
    }

    @Test
    void theTubeStrikesAndGoesOutOnEveryCycle() {
        // A thyratron that stayed lit, or never relit, would pass these counts by being constant.
        for(var subTicks : new int[]{64, 128}) {
            var trace = new Rig(0).run(subTicks);
            var cycles = trace.cycles();
            Assertions.assertEquals(cycles, trace.strikes(), 1,
                    "Strikes over " + cycles + " cycles at " + subTicks + " sub-ticks");
            Assertions.assertEquals(cycles, trace.extinctions(), 1,
                    "Extinctions over " + cycles + " cycles at " + subTicks + " sub-ticks");
        }
    }

    @Test
    void anIslandSteppedOncePerWorldTickKeepsItsTwoCallHold() {
        // The direct-current behaviour the two-call hold was written for: one dip of the current
        // below the holding current must not put the tube out, two in a row must. A DC island is
        // stepped once per world tick, so that is 100 ms of hold -- the thing the sub-tick fix must
        // leave alone.
        var net = new Network();
        var supply = net.V(50f);
        var ground = net.V(0);
        var anode = net.N();
        var grid = net.V(1f);
        var tube = new ThyratronWire(MU, VB, VH, IH, G_ON, ground, anode, grid);
        net.network.addWire(tube);
        net.W(10f, supply, anode);

        for(int i = 0; i < 5; ++i)
            net.calculate();
        Assertions.assertTrue(tube.isLit(), "The tube should have struck on 50 V with the grid at +1 V");

        // 12 V is the holding voltage, so the current drops to zero.
        supply.setVoltage(VH);
        net.calculate();
        net.calculate();
        Assertions.assertTrue(tube.isLit(), "One call after the current collapsed the tube should still be held lit");
        net.calculate();
        Assertions.assertFalse(tube.isLit(), "A second call below the holding current should put it out");
    }

    /**
     * ThyratronComponent ticks once per world tick and used to read {@code isLit()} there, which is
     * one sample of a tube that strikes and goes out many times inside the tick. This is the case the
     * component hands to {@code pollLit()}.
     * <p>
     * The premise is asserted too: the single sample really does miss the tube. At 20, 40 and 60 Hz a
     * tube conducting about 41 percent of the time read unlit on every tick; at 50 Hz it alternated.
     */
    @Test
    void aTubeThatConductsOnEveryCycleIsPolledLitOnEveryWorldTick() {
        var subTicks = 64;
        for(var frequency : new double[]{20, 40, 50, 60}) {
            var rig = new Rig(0, frequency);
            var ticks = 0;
            var polledLit = 0;
            var sampledLit = 0;
            for(int t = 0; t < SETTLE_TICKS + MEASURE_TICKS; ++t) {
                rig.net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s)
                    rig.net.network.singleTick();
                var sample = rig.tube.isLit();
                var polled = rig.tube.pollLit();
                if(t < SETTLE_TICKS)
                    continue;
                ++ticks;
                if(polled)
                    ++polledLit;
                if(sample)
                    ++sampledLit;
            }
            Assertions.assertEquals(ticks, polledLit,
                    "pollLit() should be true on every world tick at " + frequency + " Hz");
            Assertions.assertTrue(sampledLit < ticks,
                    "The end-of-tick sample was lit on all " + ticks + " ticks at " + frequency
                            + " Hz, so this test no longer shows the aliasing it exists to fix");
        }
    }

    @Test
    void aTubeThatNeverStrikesIsNeverPolledLit() {
        // Grid at -10 V: the strike voltage is 60 + 20 * 10 = 260 V and the supply peaks at 150 V.
        // An unlit tube still leaks OFF_CONDUCTANCE times its anode voltage, so this also checks
        // that the leakage is not mistaken for conduction.
        var subTicks = 64;
        var rig = new Rig(-10, FREQUENCY);
        for(int t = 0; t < SETTLE_TICKS + MEASURE_TICKS; ++t) {
            rig.net.network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s)
                rig.net.network.singleTick();
            Assertions.assertFalse(rig.tube.pollLit(), "An unstruck tube polled lit on world tick " + t);
        }
    }

    @Test
    void onAnIslandSteppedOncePerWorldTickPollLitIsExactlyIsLit() {
        // The direct-current case, where upstream's behaviour must not move: one sub-tick per world
        // tick, so the end-of-tick sample is the whole story, on the way in and on the way out.
        var net = new Network();
        var supply = net.V(50f);
        var ground = net.V(0);
        var anode = net.N();
        var grid = net.V(1f);
        var tube = new ThyratronWire(MU, VB, VH, IH, G_ON, ground, anode, grid);
        net.network.addWire(tube);
        net.W(10f, supply, anode);

        var sawLit = false;
        var sawUnlit = false;
        for(int i = 0; i < 12; ++i) {
            if(i == 5)
                supply.setVoltage(VH);
            net.calculate();
            var sample = tube.isLit();
            Assertions.assertEquals(sample, tube.pollLit(), "pollLit() against isLit() at step " + i);
            sawLit |= sample;
            sawUnlit |= !sample;
        }
        Assertions.assertTrue(sawLit && sawUnlit, "The run should have struck and gone out, or it proves nothing");
    }
}
