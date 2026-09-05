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
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;
import org.patryk3211.powergrid.electricity.sim.special.WattmeterWire;

/**
 * Real power is the mean of a product, and the mean of a product is not the product of two samples.
 *
 * <h2>What this pins</h2>
 * The power gauge and the energy meter multiplied one instantaneous current by one instantaneous
 * voltage, once per world tick. For a sinusoid that product swings between zero and twice the real
 * power at twice the supply frequency, and on a reactive load it goes negative for part of every
 * cycle. Because block entities tick once per world tick and the phase advances by
 * {@code 2*pi*f*0.05} each time, a frequency dividing 20 Hz evenly samples the same point of the
 * waveform forever — and at 20, 40 and 60 Hz that point is a zero crossing.
 *
 * <p>The reactive case is the one that shows why no single-branch substitute would have done.
 * {@code rmsCurrent()} times {@code rmsVoltage()} is apparent power, which is a different and
 * larger number than real power on anything with a phase angle.
 */
public class WattmeterTest extends TestHelper {
    private static final double AMPLITUDE = 240;
    private static final double LOAD = 24;

    /** Small enough not to disturb the circuit, as a real meter's current coil is. */
    private static final double SERIES = 0.01;

    /** Large enough not to load the supply, as a real meter's voltage coil is. */
    private static final double SHUNT = 100000;

    private record Rig(Network net, WattmeterWire meter, ElectricWire shunt) { }

    /**
     * Supply, meter in series with a load, and a sense branch across the supply — the topology
     * {@code PowerGaugeBlockEntity} builds.
     */
    private static Rig rig(double frequency, boolean reactive) {
        var net = new Network();
        var hot = new FloatingNode();
        net.network.addNode(hot);
        if(frequency > 0)
            net.network.addNode(new ACVoltageSourceCoupling(hot, null, 0.001f,
                    (float) AMPLITUDE, (float) frequency));
        else
            net.network.addNode(new VoltageSourceCoupling(hot, null, 0.001f, (float) AMPLITUDE));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));

        var shunt = new ElectricWire((float) SHUNT, hot, ground);
        net.network.addWire(shunt);

        var mid = net.N();
        var meter = new WattmeterWire(SERIES, shunt, hot, mid);
        net.network.addWire(meter);

        if(reactive)
            // The shipped motor coil: 25.6 ohm with a 10 ms time constant.
            net.network.addWire(new LRSeriesWire(0.256, 25.6, mid, ground));
        else
            net.network.addWire(new ElectricWire((float) LOAD, mid, ground));
        return new Rig(net, meter, shunt);
    }

    /** Steps whole world ticks, returning the meter's reading and the naive product on the last one. */
    private static double[] run(Rig rig, double frequency, int ticks) {
        var subTicks = frequency > 0 ? AcSampling.subTicksFor(frequency, 32, 32) : 1;
        for(int t = 0; t < 40; ++t) {
            rig.net().network.calculate(subTicks);
            rig.meter().drainRealPower();
        }
        double real = 0, naive = 0;
        for(int t = 0; t < ticks; ++t) {
            rig.net().network.calculate(subTicks);
            real += rig.meter().drainRealPower();
            naive += rig.meter().current() * rig.shunt().potentialDifference();
        }
        return new double[]{ real / ticks, naive / ticks };
    }

    @Test
    void atTwentyHertzTheNaiveProductReadsNothingAndTheMeterReadsTheTruth() {
        var rig = rig(20, false);
        var r = run(rig, 20, 200);

        // A 24 ohm load on 240 V peak takes 240^2 / (2 * 24) = 1200 W.
        var expected = AMPLITUDE * AMPLITUDE / (2 * LOAD);
        Assertions.assertEquals(expected, r[0], expected * 0.05,
                "The meter should read the real power of about " + (int) expected + " W, got " + r[0]);
        Assertions.assertEquals(0, r[1], 1,
                "One sample per tick at 20 Hz lands on a zero crossing every time, got " + r[1]);
    }

    @Test
    void itReadsRealPowerRatherThanApparentOnAReactiveLoad() {
        // The case that rules out rmsCurrent() * rmsVoltage(). A coil with a phase angle takes less
        // real power than it takes volt-amps, and a wattmeter shows the former.
        var frequency = 72.533;
        var rig = rig(frequency, true);
        var r = run(rig, frequency, 200);

        var omega = AcSampling.TWO_PI * frequency;
        var magnitude = Math.hypot(25.6, omega * 0.256);
        var currentPeak = AMPLITUDE / magnitude;
        var realPower = 0.5 * currentPeak * currentPeak * 25.6;
        var apparentPower = 0.5 * AMPLITUDE * currentPeak;

        Assertions.assertTrue(apparentPower > realPower * 2,
                "This load should be strongly reactive for the test to mean anything, got "
                        + apparentPower + " VA against " + realPower + " W");
        Assertions.assertEquals(realPower, r[0], realPower * 0.25,
                "The meter should read real power of about " + String.format("%.1f", realPower)
                        + " W, not the apparent " + String.format("%.1f", apparentPower)
                        + " VA, got " + String.format("%.1f", r[0]));
    }

    @Test
    void aSteadySupplyReadsExactlyTheProduct() {
        // The non-regression. With no sub-ticks there is one sample per tick and it is the whole
        // truth, so the meter and the naive product agree exactly and every direct-current circuit
        // reads as it always did.
        var rig = rig(0, false);
        var r = run(rig, 0, 20);

        Assertions.assertEquals(r[1], r[0], Math.abs(r[1]) * 1e-9,
                "On a steady supply the mean and the sample must be the same number");
        var expected = AMPLITUDE * AMPLITUDE / LOAD;
        Assertions.assertEquals(expected, r[0], expected * 0.05,
                "And both should be the ohmic answer of about " + (int) expected + " W");
    }

    @Test
    void drainingStartsAFreshAverage() {
        // Each drain must follow a solve. Draining twice with no tick in between is not a test of
        // the reset -- the second call finds no samples and returns the instantaneous product
        // instead, which at 20 Hz is a zero crossing and nothing like the average.
        var rig = rig(20, false);
        var subTicks = AcSampling.subTicksFor(20, 32, 32);
        run(rig, 20, 10);

        rig.net().network.calculate(subTicks);
        var first = rig.meter().drainRealPower();
        rig.net().network.calculate(subTicks);
        var second = rig.meter().drainRealPower();

        Assertions.assertEquals(first, second, Math.abs(first) * 0.1,
                "Consecutive drains on a steady load should agree, got " + first + " then " + second);

        // And with no tick in between, the accumulator is genuinely empty rather than repeating
        // the previous answer.
        var empty = rig.meter().drainRealPower();
        Assertions.assertNotEquals(second, empty,
                "A drain with no samples should not repeat the last average");
    }
}
