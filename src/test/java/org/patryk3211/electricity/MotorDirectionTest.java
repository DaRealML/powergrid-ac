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
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * Why a motor stopped lurching back and forth on an alternating supply.
 *
 * <h2>The defect</h2>
 * {@code ElectricMotorBlockEntity.tick} runs once per world tick and took its direction from
 * {@code Math.signum} of the instantaneous coil current. On a steady supply that is the polarity.
 * On an alternating one it is whichever point of the waveform that tick happened to land on, so
 * the motor was told to drive forwards, then backwards, then forwards again, and the five-tick
 * average it feeds came out near a standstill with visible jitter.
 *
 * <h2>The rule that replaced it</h2>
 * Magnitude from {@code rmsCurrent()}, direction from {@code meanCurrent()} — accumulated across
 * every solver sub-tick rather than sampled once — and the direction only changes when the mean is
 * a real fraction of the RMS. A steady supply is unchanged exactly; a symmetric alternating one
 * cannot express a direction and so keeps the one it had.
 *
 * <h2>What these tests can and cannot reach</h2>
 * The block entity needs Minecraft to instantiate, so what is exercised here is the wire-level
 * quantities the rule is built on and the rule's own arithmetic, mirrored in
 * {@link #directionWouldChange}. The mirror is a reimplementation and is called out as such: it
 * pins the <em>decision</em>, not the block entity's use of it.
 */
public class MotorDirectionTest extends TestHelper {
    private static final double FREQUENCY = 20;
    private static final int SUB_TICKS = 64;
    private static final double AMPLITUDE = 10;

    /** Nominal motor resistance and the shipped time constant. */
    private static final double R = 25.6;
    private static final double TAU = 0.01;

    /** Mirrors ElectricMotorBlockEntity's rule. Kept in step with it by hand. */
    private static final double DIRECTION_TAU = 0.5;
    private static final double TICK_SECONDS = 0.05;
    private static final double DIRECTION_BIAS = 0.3;

    /** The block entity's direction rule, stepped one world tick. Returns the new DC estimate. */
    private static double stepDc(double dc, LRSeriesWire coil) {
        var alpha = TICK_SECONDS / (DIRECTION_TAU + TICK_SECONDS);
        return dc + alpha * (coil.meanCurrent() - dc);
    }

    private static boolean wouldSetDirection(double dc, LRSeriesWire coil) {
        return Math.abs(dc) > coil.rmsCurrent() * DIRECTION_BIAS;
    }

    /** A motor coil across an alternating supply, settled. */
    private static LRSeriesWire settledCoil(double frequency, double dcOffset, int ticks) {
        var net = new Network();
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f,
                (float) AMPLITUDE, (float) frequency);
        source.setDcOffset((float) dcOffset);
        net.network.addNode(terminal);
        net.network.addNode(source);
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var coil = new LRSeriesWire(R * TAU, R, terminal, ground);
        net.network.addWire(coil);
        for(int i = 0; i < ticks; ++i)
            net.network.calculate(SUB_TICKS);
        return coil;
    }

    @Test
    void alternatingSupplyCarriesRealCurrentButNoDirection() {
        var coil = settledCoil(FREQUENCY, 0, 40);

        Assertions.assertTrue(coil.rmsCurrent() > 0.1,
                "A motor on an alternating supply is drawing real current");
        var net = coil.getNetwork();
        var dc = 0.0;
        for(int t = 0; t < 60; ++t) {
            net.calculate(SUB_TICKS);
            dc = stepDc(dc, coil);
        }
        Assertions.assertFalse(wouldSetDirection(dc, coil),
                "No direct component means the motor must keep the direction it had");
    }

    @Test
    void noSymmetricSupplyAtAnyFrequencyEverSetsADirection() {
        // The frequencies an alternator actually produces: 272 rpm over 1..16 pole pairs is
        // 4.5 to 72.5 Hz. Only 20, 40 and 60 divide the 50 ms world tick into whole cycles, so
        // every other entry here has a per-tick mean that is NOT zero -- which is precisely what
        // broke the first version of this rule.
        for(var frequency : new double[] { 4.53, 9, 13.6, 18.13, 20, 27, 36.27, 45, 72.53 }) {
            var coil = settledCoil(frequency, 0, 20);
            var net = coil.getNetwork();
            var dc = 0.0;
            var reversals = 0;
            for(int t = 0; t < 80; ++t) {
                net.calculate(SUB_TICKS);
                dc = stepDc(dc, coil);
                if(wouldSetDirection(dc, coil))
                    ++reversals;
            }
            Assertions.assertEquals(0, reversals,
                    String.format("A symmetric %.2f Hz supply must never set a direction "
                            + "(dc=%.4f, rms=%.4f)", frequency, dc, coil.rmsCurrent()));
        }
    }

    @Test
    void theInstantaneousSignFlipsWhereTheFilteredComponentDoesNot() {
        // The regression, stated directly. This is what made the shaft lurch: sampled once per
        // world tick exactly as tick() does, the instantaneous sign alternates, while the mean
        // stays pinned at zero and the new rule therefore never reverses.
        // 9 Hz is deliberately not a whole number of cycles per 50 ms tick, so consecutive
        // ticks land on different points of the waveform -- which is exactly the situation the
        // old rule mishandled.
        var coil = settledCoil(9, 0, 40);
        var net = coil.getNetwork();

        var signChanges = 0;
        var previous = 0.0;
        var directionChanges = 0;
        var dc = 0.0;
        for(int t = 0; t < 40; ++t) {
            net.calculate(SUB_TICKS);
            var instantaneous = Math.signum(coil.current());
            if(t > 0 && instantaneous != previous)
                ++signChanges;
            previous = instantaneous;
            dc = stepDc(dc, coil);
            if(wouldSetDirection(dc, coil))
                ++directionChanges;
        }

        Assertions.assertTrue(signChanges > 4,
                "The old rule should flip repeatedly on AC, got " + signChanges + " flips");
        Assertions.assertEquals(0, directionChanges,
                "The new rule must never reverse on a symmetric supply");
    }

    @Test
    void steadySupplyIsUnchangedAndStillReverses() {
        // The non-regression. On DC the new quantities collapse onto the old ones exactly:
        // rmsCurrent() is |I| and meanCurrent() is I, so the magnitude term V*V is the same
        // square and the direction is the same signum.
        for(var volts : new double[] { 10, -10 }) {
            var net = new Network();
            var supply = net.V((float) volts);
            var ground = net.V(0);
            var coil = new LRSeriesWire(R * TAU, R, supply, ground);
            net.network.addWire(coil);

            for(int i = 0; i < 20; ++i)
                net.network.calculate(1);

            Assertions.assertEquals(coil.current(), coil.meanCurrent(), 1e-12,
                    "On a steady supply the mean is the current");
            Assertions.assertEquals(Math.abs(coil.current()), coil.rmsCurrent(), 1e-12,
                    "On a steady supply the RMS is the magnitude");
            var dc = 0.0;
            for(int t = 0; t < 20; ++t)
                dc = stepDc(dc, coil);
            Assertions.assertTrue(wouldSetDirection(dc, coil),
                    "A steady supply must always be able to set a direction");
            Assertions.assertEquals(Math.signum(volts), Math.signum(coil.meanCurrent()), 1e-12,
                    "Reversing the supply must still reverse the motor");

            // The magnitude the motor squares is identical to what the old expression squared.
            var oldV = coil.current() * coil.getResistance();
            var newV = coil.rmsCurrent() * coil.getResistance();
            Assertions.assertEquals(oldV * oldV, newV * newV, Math.abs(oldV * oldV) * 1e-12,
                    "The speed magnitude must be bit-for-bit what it was on DC");
        }
    }

    @Test
    void anOffsetSupplyKeepsItsBias() {
        // A half-rectified or offset supply is one-directional, and must be able to say so.
        var coil = settledCoil(FREQUENCY, AMPLITUDE, 40);

        var net = coil.getNetwork();
        var dc = 0.0;
        for(int t = 0; t < 40; ++t) {
            net.calculate(SUB_TICKS);
            dc = stepDc(dc, coil);
        }
        Assertions.assertTrue(dc > 0,
                "A positive offset should give a positive direct component");
        Assertions.assertTrue(wouldSetDirection(dc, coil),
                "An offset supply is one-directional and must be able to set a direction");
    }
}
