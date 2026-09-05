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
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.ArcWire;
import org.patryk3211.powergrid.electricity.sim.special.PNJunctionWire;

/**
 * An arc has to behave like an arc rather than like a resistor with a threshold.
 *
 * <p>The three claims that matter are that its voltage barely depends on its current, that it puts
 * itself out at an alternating supply's current zero, and that it does not put itself out on a
 * steady one. The last two are the same code path asked two different questions, which is the
 * point: the asymmetry between AC and DC switchgear is not a rule anyone wrote down here, it falls
 * out of a current that does or does not change sign.
 */
public class ArcWireTest extends TestHelper {
    /**
     * Half a millimetre: contacts that have only just parted.
     * <p>
     * The gap has to be small enough that the test supply can actually break it down. Air stands
     * off about 3 kV per millimetre, so 0.5 mm withstands 1.5 kV and a 3 kV supply strikes it,
     * while the 2 mm this test first used withstands 6 kV and 3 kV could never have lit it.
     */
    private static final float GAP = 0.0005f;

    private static final float ELECTRODE_FALL = 30;
    private static final float GRADIENT = 5000;
    private static final float CHANNEL_CONDUCTANCE = 50;

    /** Dry air, near enough: 3 kV per millimetre. */
    private static final float DIELECTRIC_STRENGTH = 3e6f;

    /** Long enough to matter at 20 Hz, short enough that an arc reignites through a zero. */
    private static final float DEIONISATION = 0.002f;

    private static ArcWire arc(float gap, FloatingNode a, FloatingNode b) {
        return new ArcWire(ELECTRODE_FALL, GRADIENT, CHANNEL_CONDUCTANCE, DIELECTRIC_STRENGTH,
                DEIONISATION, gap, a, b);
    }

    /** Supply, series resistance, arc to ground. The resistance is the ballast an arc needs. */
    private static final class Rig {
        final Network net = new Network();
        final ArcWire arc;
        final FloatingNode ground;

        Rig(double amplitude, double frequency, double series, float gap) {
            var hot = new FloatingNode();
            net.network.addNode(hot);
            if(frequency > 0)
                net.network.addNode(new ACVoltageSourceCoupling(hot, null, 0.001f,
                        (float) amplitude, (float) frequency));
            else
                net.network.addNode(new VoltageSourceCoupling(hot, null, 0.001f, (float) amplitude));
            ground = net.N();
            net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));

            var mid = net.N();
            net.network.addWire(new ElectricWire((float) series, hot, mid));
            arc = arc(gap, mid, ground);
            net.network.addWire(arc);
        }

        /** Step whole world ticks, returning the peak arc current seen while conducting. */
        double run(int ticks, int subTicks) {
            var peak = 0.0;
            for(int t = 0; t < ticks; ++t) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s) {
                    net.network.singleTick();
                    if(arc.isStruck())
                        peak = Math.max(peak, Math.abs(arc.current()));
                }
            }
            return peak;
        }
    }

    @Test
    void theArcVoltageBarelyDependsOnTheCurrent() {
        // The defining property. Two circuits differing only in ballast, so the arc carries very
        // different currents; a resistor would drop a proportionally different voltage and an arc
        // drops nearly the same one.
        var light = new Rig(3000, 0, 200, GAP);
        var heavy = new Rig(3000, 0, 20, GAP);
        light.run(40, 8);
        heavy.run(40, 8);

        Assertions.assertTrue(light.arc.isStruck() && heavy.arc.isStruck(),
                "3 kV across a 0.5 mm gap should strike; it withstands only "
                        + light.arc.breakdownVoltage() + " V");

        var lightCurrent = Math.abs(light.arc.current());
        var heavyCurrent = Math.abs(heavy.arc.current());
        Assertions.assertTrue(heavyCurrent > lightCurrent * 5,
                "The heavier circuit should carry much more current, got "
                        + String.format("%.2f A against %.2f A", heavyCurrent, lightCurrent));

        // The voltages, against a resistor's would-be ratio of the currents.
        var lightVolts = Math.abs(light.arc.potentialDifference());
        var heavyVolts = Math.abs(heavy.arc.potentialDifference());
        Assertions.assertEquals(lightVolts, heavyVolts, lightVolts * 0.25,
                "An arc's voltage should barely move with current, got "
                        + String.format("%.1f V at %.2f A and %.1f V at %.2f A",
                                lightVolts, lightCurrent, heavyVolts, heavyCurrent));

        // And it should sit near the modelled arc voltage rather than near i*R.
        Assertions.assertEquals(light.arc.arcVoltage(), lightVolts, light.arc.arcVoltage() * 0.5,
                "The drop should be about the arc voltage of " + light.arc.arcVoltage() + " V");
    }

    @Test
    void anAlternatingArcGoesOutAtEveryCurrentZero() {
        // Two zeros per cycle, so a 20 Hz supply over ten world ticks offers about twenty of them.
        // The arc reignites through each one while the gap is hot, which is what makes a real AC
        // arc look continuous while in fact restriking constantly.
        var rig = new Rig(3000, 20, 200, GAP);
        rig.run(20, 32);
        rig.arc.drainRestrikes();
        rig.run(10, 32);

        var restrikes = rig.arc.drainRestrikes();
        var cycles = 20 * (10 * AcSampling.TICK_SECONDS);
        Assertions.assertEquals(2 * cycles, restrikes, 0.25 * 2 * cycles,
                "Expected about " + (int) (2 * cycles) + " reignitions in ten world ticks at 20 Hz, got "
                        + restrikes);
    }

    @Test
    void aSteadyArcNeverGoesOutOnItsOwn() {
        // The same code, the same gap, the same everything but the supply. A direct current has no
        // zero to offer, so the extinction branch is never taken -- which is exactly why breaking a
        // DC circuit under load is the hard case and why DC switchgear is derated.
        var rig = new Rig(3000, 0, 200, GAP);
        rig.run(20, 32);
        Assertions.assertTrue(rig.arc.isStruck(), "The arc should have struck on 3 kV");
        rig.arc.drainRestrikes();

        rig.run(60, 32);
        Assertions.assertTrue(rig.arc.isStruck(), "A steady arc must still be burning");
        Assertions.assertEquals(0, rig.arc.drainRestrikes(),
                "A steady supply offers no current zero, so the arc should never have relit");
    }

    @Test
    void aGapWideEnoughToRecoverClearsTheArc() {
        // What a switch is for. Widen the contacts while the arc burns and the gap's recovery
        // voltage climbs past what the circuit can offer at the next zero, and the arc is cleared.
        var rig = new Rig(3000, 20, 200, GAP);
        rig.run(20, 32);
        Assertions.assertTrue(rig.arc.isStruck(), "The arc should be burning before the gap opens");

        rig.arc.setGap(0.05f);
        rig.run(20, 32);

        Assertions.assertFalse(rig.arc.isStruck(),
                "A 50 mm gap withstands " + rig.arc.breakdownVoltage()
                        + " V and should have cleared a 3 kV arc");
    }

    @Test
    void theArcAccountsForTheEnergyItDissipates() {
        // What the flash actually costs, and what a block entity would feed into its thermal model.
        var rig = new Rig(3000, 0, 200, GAP);
        rig.run(40, 8);
        rig.arc.drainEnergy();

        var ticks = 20;
        rig.run(ticks, 8);
        var joules = rig.arc.drainEnergy();

        var expected = rig.arc.arcVoltage() * Math.abs(rig.arc.current())
                * ticks * AcSampling.TICK_SECONDS;
        Assertions.assertEquals(expected, joules, expected * 0.1,
                "Energy should be the arc voltage times the current times the time, got "
                        + String.format("%.2f J against %.2f", joules, expected));
        Assertions.assertEquals(0, rig.arc.drainEnergy(), 1e-12,
                "Draining should reset the accumulator");
    }

    @Test
    void quenchingPutsTheArcOutAndKeepsItOut() {
        // What a closing contact does. A zero-length gap withstands zero volts, so an arc left to
        // its own devices at that moment would read as permanently broken down; the switch has to
        // say so explicitly. After quenching, the gap must also be treated as cold, or the next
        // sub-tick would restrike it through the still-hot recovery path.
        var rig = new Rig(3000, 0, 200, GAP);
        rig.run(20, 32);
        Assertions.assertTrue(rig.arc.isStruck(), "The arc should be burning before it is quenched");

        rig.arc.quench();
        Assertions.assertFalse(rig.arc.isStruck(), "Quenching should put it out at once");

        rig.arc.setGap(0f);
        rig.run(20, 32);
        Assertions.assertFalse(rig.arc.isStruck(),
                "A quenched arc across closed contacts must stay out, not relight on a zero gap");
    }

    @Test
    void anArcDoesNotCostTheIslandItsLinearFastPath() {
        // The reason the arc is stamped as a piecewise-linear Norton source rather than as a
        // Newton-iterated negative resistance. A genuine ISolverHook makes hasHooks() true, and the
        // island then pays the full Newton loop at every sub-tick instead of one triangular solve.
        var rig = new Rig(3000, 20, 200, GAP);
        rig.run(10, 32);
        Assertions.assertFalse(rig.net.network.hasHooks(),
                "An arc must not drag the island off the linear fast path");

        // The control: something that genuinely is a solver hook does flip it, so the assertion
        // above is testing the arc rather than testing that nothing ever sets the flag.
        var mid = rig.net.N();
        rig.net.network.addWire(new PNJunctionWire(1e-12, 0.01, 25, 1.0, mid, rig.ground));
        Assertions.assertTrue(rig.net.network.hasHooks(),
                "A diode is a solver hook, so this network should now have one");
    }
}
