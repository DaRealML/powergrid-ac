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
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.CRSeriesWire;

/**
 * The stiff branch must not ring. This is a constraint on the integration scheme, not on any one
 * component, and it is the property that decides which schemes are admissible here.
 *
 * <h2>Why a capacitor across a supply is the worst case in the mod</h2>
 * {@code CapacitorComponent} builds {@code new CRSeriesWire(capacitance, 0.01f, ...)}. Wired
 * straight across a supply — which is what a smoothing capacitor is — the branch's only resistance
 * is that 0.01 ohm parasitic, giving a time constant near a <em>microsecond</em> against a solver
 * step of 3.125 ms at best and 50 ms on a direct-current island. Every scheme's damping factor for
 * a mode of time constant {@code tau} tends to a constant as {@code dt/tau} grows, and whether that
 * constant is near zero or near minus one is the whole question:
 *
 * <ul>
 *   <li>backward Euler tends to 0 — the mode is annihilated in one step (it is L-stable);</li>
 *   <li>the trapezoidal rule tends to −1 — the mode alternates sign and never decays. Measured, a
 *       100 uF capacitor across a supply rings at about six amps and is still at half that after a
 *       thousand steps, which is sixty world ticks of visible garbage on an ordinary circuit.</li>
 * </ul>
 *
 * <p>The true steady-state current here is zero, because a capacitor passes no direct current. So
 * <em>any</em> alternating content in this branch is a pure artefact of the integration and can be
 * measured without having to model what the right answer would be.
 *
 * <p>These tests are deliberately scheme-agnostic: they assert the property, not the scheme. A
 * change of integration that keeps this property passes them untouched, and one that does not has
 * to argue with them.
 */
public class StiffDampingTest extends TestHelper {
    /** What {@code CapacitorComponent} really builds. */
    private static final double CAPACITANCE = 100e-6;
    private static final float PARASITIC = 0.01f;

    private static final float SUPPLY = 100;

    /**
     * Alternating-sign content of a sampled signal.
     * <p>
     * The mean absolute second difference: zero for anything smooth or monotone, and equal to the
     * amplitude for a signal alternating between two values. That distinguishes a numerical ring
     * from a fast but honest transient, which a peak-to-peak measure would not.
     */
    private static double ringAmplitude(double[] x, int from, int to) {
        var sum = 0.0;
        var n = 0;
        for(int k = from + 1; k < to - 1; ++k, ++n)
            sum += Math.abs(x[k - 1] - 2 * x[k] + x[k + 1]);
        return n == 0 ? 0 : sum / n / 4;
    }

    /** Samples the branch current of a capacitor placed across the supply behind {@code series} ohms. */
    private static double[] run(double seriesOhms, int subTicks, int steps) {
        var net = new Network();
        var hot = new FloatingNode();
        net.network.addNode(hot);
        net.network.addNode(new VoltageSourceCoupling(hot, null, 0.001f, SUPPLY));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));

        var terminal = hot;
        if(seriesOhms > 0) {
            var mid = net.N();
            net.network.addWire(new ElectricWire((float) seriesOhms, hot, mid));
            terminal = mid;
        }
        var cap = new CRSeriesWire(CAPACITANCE, PARASITIC, terminal, ground);
        net.network.addWire(cap);

        var samples = new double[steps];
        var k = 0;
        while(k < steps) {
            net.network.prepare(subTicks);
            for(int s = 0; s < subTicks && k < steps; ++s, ++k) {
                net.network.singleTick();
                samples[k] = cap.current();
            }
        }
        return samples;
    }

    @Test
    void aCapacitorAcrossASupplyDoesNotRing() {
        // The stiffest thing the mod can build, at the AC sub-tick ceiling.
        var x = run(0, 16, 4000);

        Assertions.assertTrue(ringAmplitude(x, 100, 1000) < 1e-3,
                "A capacitor across a supply should be settled within a hundred steps, ring was "
                        + ringAmplitude(x, 100, 1000) + " A");
        Assertions.assertTrue(ringAmplitude(x, 3000, 4000) < 1e-6,
                "Nothing should still be alternating after three thousand steps, ring was "
                        + ringAmplitude(x, 3000, 4000) + " A");

        // A capacitor passes no direct current, so the settled current is the leakage and nothing
        // else. ITimeAwareWire bleeds 1e-5 of the stored state per world tick on purpose, so a
        // 100 uF capacitor holding 100 V loses 1e-7 C every 50 ms and draws 2 uA to replace it —
        // which is what this reads. The bound is set an order above that so it catches a ring
        // without re-asserting the leakage rate, which is not this test's business.
        for(int k = 2000; k < 4000; ++k)
            Assertions.assertEquals(0, x[k], 2e-5,
                    "A settled capacitor on a steady supply should carry only leakage, sample " + k);
    }

    @Test
    void aDirectCurrentIslandDoesNotRingEither() {
        // Islands with no alternator step once per world tick, so dt is fifty milliseconds and the
        // branch is sixteen times stiffer relative to the step. That also means any ring here lasts
        // sixteen times longer in world time, which is why it is worth a test of its own.
        var x = run(0, 1, 400);

        Assertions.assertTrue(ringAmplitude(x, 20, 100) < 1e-2,
                "A direct-current island should settle within twenty ticks, ring was "
                        + ringAmplitude(x, 20, 100) + " A");
        Assertions.assertTrue(ringAmplitude(x, 100, 400) < 1e-6,
                "Nothing should still be alternating after a hundred world ticks, ring was "
                        + ringAmplitude(x, 100, 400) + " A");
    }

    @Test
    void throwingASwitchDoesNotStartOne() {
        // The other way to excite a stiff mode: a discontinuity rather than energisation.
        var net = new Network();
        var hot = new FloatingNode();
        net.network.addNode(hot);
        net.network.addNode(new VoltageSourceCoupling(hot, null, 0.001f, SUPPLY));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var mid = net.N();
        var sw = new SwitchedWire(20f, hot, mid, true);
        net.network.addWire(sw);
        var cap = new CRSeriesWire(CAPACITANCE, PARASITIC, mid, ground);
        net.network.addWire(cap);

        for(int t = 0; t < 40; ++t)
            net.network.calculate(16);

        var samples = new double[2000];
        var k = 0;
        sw.setState(false);
        while(k < samples.length) {
            net.network.prepare(16);
            for(int s = 0; s < 16 && k < samples.length; ++s, ++k) {
                net.network.singleTick();
                samples[k] = cap.potentialDifference();
            }
            if(k == 16 * 20)
                sw.setState(true);
        }

        Assertions.assertTrue(ringAmplitude(samples, 600, 2000) < 1e-4,
                "Opening and closing a switch should not leave a standing oscillation, ring was "
                        + ringAmplitude(samples, 600, 2000) + " V");
    }
}
