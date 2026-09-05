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

/**
 * Why resistive heating and shock damage are computed from an RMS current.
 *
 * <h2>The defect this pins</h2>
 * {@code BaseWireEntity.temperatureUpdate} and {@code EntityWireInteraction} both read one
 * <em>instantaneous</em> current per world tick, because entities tick after the solve. Heating
 * goes as the mean of the current squared and physiological effect goes as RMS, and a single
 * sample of a sinusoid is neither.
 *
 * <p>What makes it more than sampling noise is that the sample is not taken at a random phase. The
 * phase advances by {@code 2*pi*f*0.05} per world tick, so a frequency that divides 20 Hz evenly
 * advances a whole number of cycles and lands on exactly the same point of the waveform every tick,
 * forever. That point is a zero crossing, so a wire carrying ten amps peak at 10, 20, 40 or 60 Hz
 * measured <b>zero</b> heating and could never burn, and grabbing it was perfectly safe.
 *
 * <p>These tests work on the solver, not on the entities — {@code BaseWireEntity} needs Minecraft
 * and cannot be loaded here — so what is pinned is the claim the fix rests on rather than the
 * entity code itself.
 */
public class WireHeatingTest extends TestHelper {
    private static final double AMPLITUDE = 240;
    private static final double RESISTANCE = 24;

    /** Peak current, and therefore a true mean-square of half its square. */
    private static final double PEAK = AMPLITUDE / RESISTANCE;

    private record Sampled(double instantaneous, double meanSquare) { }

    /**
     * Run a resistive wire and collect both readings once per world tick, exactly where an entity
     * would: after the tick's solve has completed.
     */
    private static Sampled run(double frequency, int ticks) {
        var net = new Network();
        var hot = new FloatingNode();
        net.network.addNode(hot);
        net.network.addNode(new ACVoltageSourceCoupling(hot, null, 0.001f,
                (float) AMPLITUDE, (float) frequency));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var wire = new ElectricWire((float) RESISTANCE, hot, ground);
        net.network.addWire(wire);

        var subTicks = frequency > 0 ? AcSampling.subTicksFor(frequency, 32, 32) : 1;
        for(int t = 0; t < 40; ++t)
            net.network.calculate(subTicks);

        double instantaneous = 0, meanSquare = 0;
        for(int t = 0; t < ticks; ++t) {
            net.network.calculate(subTicks);
            var i = wire.current();
            instantaneous += i * i;
            var rms = wire.rmsCurrent();
            meanSquare += rms * rms;
        }
        return new Sampled(instantaneous / ticks, meanSquare / ticks);
    }

    @Test
    void aWireAtTwentyHertzSampledOncePerTickReadsNoHeatingAtAll() {
        // The worst case, and not a contrived one: 20 Hz is one electrical cycle per world tick, so
        // the sample lands on the same zero crossing every time.
        var sampled = run(20, 400);

        Assertions.assertEquals(0, sampled.instantaneous(), 1e-6,
                "One sample per tick at 20 Hz should land on a zero crossing every time, got "
                        + sampled.instantaneous());
        Assertions.assertEquals(PEAK * PEAK / 2, sampled.meanSquare(), PEAK * PEAK * 0.01,
                "The RMS reading should be the true mean-square of "
                        + (PEAK * PEAK / 2) + " A^2, got " + sampled.meanSquare());
    }

    @Test
    void theSameIsTrueOfEveryFrequencyThatDividesTheWorldTick() {
        for(var frequency : new double[] { 10, 20, 40, 60 }) {
            var sampled = run(frequency, 200);
            Assertions.assertEquals(0, sampled.instantaneous(), 1e-6,
                    frequency + " Hz divides the 20 Hz world tick, so the instantaneous sample "
                            + "should sit on a zero crossing, got " + sampled.instantaneous());
            Assertions.assertEquals(PEAK * PEAK / 2, sampled.meanSquare(), PEAK * PEAK * 0.02,
                    "The RMS reading should still be right at " + frequency + " Hz");
        }
    }

    @Test
    void anAlternatorFrequencyAveragesOutButOnlyOverTime() {
        // Away from those exact divisors the phase walks, so the error does average away given
        // enough ticks. That is why this was survivable rather than obvious -- and it is no help to
        // an overheat rule that needs the temperature to rise for consecutive ticks.
        var sampled = run(72.533, 400);
        Assertions.assertEquals(sampled.meanSquare(), sampled.instantaneous(),
                sampled.meanSquare() * 0.05,
                "Over four hundred ticks at 72.5 Hz the two should agree on average");

        // But tick by tick they do not. The instantaneous square ranges over the whole waveform
        // while the mean-square barely moves.
        var net = new Network();
        var hot = new FloatingNode();
        net.network.addNode(hot);
        net.network.addNode(new ACVoltageSourceCoupling(hot, null, 0.001f, (float) AMPLITUDE, 72.533f));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var wire = new ElectricWire((float) RESISTANCE, hot, ground);
        net.network.addWire(wire);
        var subTicks = AcSampling.subTicksFor(72.533, 32, 32);
        for(int t = 0; t < 40; ++t)
            net.network.calculate(subTicks);

        var instantMin = Double.POSITIVE_INFINITY;
        var instantMax = 0.0;
        var rmsMin = Double.POSITIVE_INFINITY;
        var rmsMax = 0.0;
        for(int t = 0; t < 200; ++t) {
            net.network.calculate(subTicks);
            var i = wire.current() * wire.current();
            instantMin = Math.min(instantMin, i);
            instantMax = Math.max(instantMax, i);
            var rms = wire.rmsCurrent() * wire.rmsCurrent();
            rmsMin = Math.min(rmsMin, rms);
            rmsMax = Math.max(rmsMax, rms);
        }

        Assertions.assertTrue(instantMax > instantMin * 50,
                "The instantaneous square should swing wildly tick to tick, got "
                        + instantMin + " to " + instantMax);
        Assertions.assertTrue(rmsMax < rmsMin * 1.1,
                "The mean-square should barely move, got " + rmsMin + " to " + rmsMax);
    }

    @Test
    void aSteadySupplyIsUnaffected() {
        // The non-regression that matters: with no sub-ticks rmsCurrent() returns the instantaneous
        // magnitude, so every direct-current circuit heats and shocks exactly as it always did.
        var net = new Network();
        var supply = net.V(240);
        var ground = net.V(0);
        var wire = new ElectricWire((float) RESISTANCE, supply, ground);
        net.network.addWire(wire);
        for(int t = 0; t < 20; ++t)
            net.network.calculate(1);

        Assertions.assertEquals(Math.abs(wire.current()), wire.rmsCurrent(), 1e-12,
                "On a steady supply the two readings must be identical");
        Assertions.assertEquals(240.0 / RESISTANCE, wire.rmsCurrent(), 1e-4,
                "And both should be the ohmic answer");
    }
}
