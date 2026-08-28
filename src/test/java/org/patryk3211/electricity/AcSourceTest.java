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
import org.patryk3211.powergrid.electricity.sim.special.ACCurrentSourceNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;

/** Behaviour of the two standalone alternating sources. */
public class AcSourceTest extends TestHelper {
    private static final double FREQUENCY = 20;
    private static final int SUB_TICKS = 64;

    @Test
    void currentSourceDrivesItsSetCurrentThroughAnyLoad() {
        // A current source fixes the current and lets the circuit find the voltage, so the load
        // resistance should change the voltage and leave the current alone. That is the property
        // that distinguishes it from a voltage source.
        var smallLoad = currentSourceRig(2.0, 10f);
        var largeLoad = currentSourceRig(2.0, 40f);

        Assertions.assertEquals(2.0 / Math.sqrt(2), smallLoad.rmsCurrent(), 0.05,
                "RMS current should be peak/sqrt(2) regardless of load");
        Assertions.assertEquals(2.0 / Math.sqrt(2), largeLoad.rmsCurrent(), 0.05,
                "Quadrupling the load must not change the current");

        Assertions.assertEquals(4.0, largeLoad.rmsVoltage() / smallLoad.rmsVoltage(), 0.1,
                "Voltage should scale with the load a current source is driving");
    }

    /** An AC current source pushing through a single resistor; returns the resistor. */
    private org.patryk3211.powergrid.electricity.sim.ElectricWire currentSourceRig(double amplitude, float load) {
        var net = new Network();
        var ground = net.V(0);
        var source = new ACCurrentSourceNode(amplitude, FREQUENCY);
        net.network.addNode(source);
        var R = net.W(load, source, ground);

        for(int i = 0; i < 40; ++i)
            net.network.calculate(SUB_TICKS);
        return R;
    }

    @Test
    void currentSourceReactsWithAnInductor() {
        // Driving a known current through an inductor develops v = L*di/dt across it, so the
        // voltage leads the current. Real power should be near zero, as for any reactive load.
        var net = new Network();
        var ground = net.V(0);
        var source = new ACCurrentSourceNode(1.0, FREQUENCY);
        net.network.addNode(source);
        var L = new org.patryk3211.powergrid.electricity.sim.special.InductorWire(0.05, source, ground);
        net.network.addWire(L);
        // Series path so the source always has somewhere to push.
        net.W(50f, source, ground);

        for(int i = 0; i < 60; ++i)
            net.network.calculate(SUB_TICKS);

        Assertions.assertTrue(L.rmsCurrent() > 0, "Inductor should carry current from the source");
        Assertions.assertEquals(0, L.powerFactor(), 0.2,
                "An inductor driven by a current source still carries almost no real power");
    }

    @Test
    void zeroAmplitudeWithOffsetIsASteadySource() {
        // This is how a DC supply is expressed now that both creative sources are alternating
        // components: no sine, just the offset.
        var net = new Network();
        var ground = net.V(0);
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f);
        source.setAmplitude(0);
        source.setFrequency(0);
        source.setDcOffset(12);
        net.network.addNode(terminal);
        net.network.addNode(source);
        var R = net.W(4f, terminal, ground);

        for(int i = 0; i < 20; ++i)
            net.network.calculate(1);

        Assertions.assertEquals(12.0, terminal.getVoltage(), 0.05, "Steady output should be the offset");
        Assertions.assertEquals(3.0, R.current(), 0.05, "12 V across 4 ohms is 3 A");
        Assertions.assertEquals(1, source.requiredSubTicks(),
                "A source at zero frequency should not ask for sub-ticks");
    }

    @Test
    void offsetShiftsTheWaveformWithoutChangingItsSwing() {
        // Amplitude 5 on an offset of 5 never goes negative — the shape a half-wave supply has
        // after smoothing, and a case where mean and RMS differ in a way worth getting right.
        var net = new Network();
        var ground = net.V(0);
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 5, FREQUENCY);
        source.setDcOffset(5);
        net.network.addNode(terminal);
        net.network.addNode(source);
        net.W(10f, terminal, ground);

        var min = Double.POSITIVE_INFINITY;
        var max = Double.NEGATIVE_INFINITY;
        for(int t = 0; t < 20; ++t) {
            net.network.prepare(SUB_TICKS);
            for(int i = 0; i < SUB_TICKS; ++i) {
                net.network.singleTick();
                min = Math.min(min, source.getVoltage());
                max = Math.max(max, source.getVoltage());
            }
        }

        Assertions.assertEquals(0, min, 0.1, "Trough should sit at offset minus amplitude");
        Assertions.assertEquals(10, max, 0.1, "Crest should sit at offset plus amplitude");
    }

    @Test
    void phaseOffsetsMakeABalancedThreePhaseSet() {
        // Three sources 120 degrees apart sum to zero at every instant. That is the defining
        // property of a balanced three-phase supply, and it is what the phase offset exists for.
        var net = new Network();
        var ground = net.V(0);
        var sources = new ACVoltageSourceCoupling[3];
        for(int i = 0; i < 3; ++i) {
            var terminal = new FloatingNode();
            var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 10, FREQUENCY);
            source.setPhaseOffset(i * 2 * Math.PI / 3);
            net.network.addNode(terminal);
            net.network.addNode(source);
            net.W(10f, terminal, ground);
            sources[i] = source;
        }

        var worst = 0.0;
        for(int t = 0; t < 10; ++t) {
            net.network.prepare(SUB_TICKS);
            for(int i = 0; i < SUB_TICKS; ++i) {
                net.network.singleTick();
                var sum = sources[0].getVoltage() + sources[1].getVoltage() + sources[2].getVoltage();
                worst = Math.max(worst, Math.abs(sum));
            }
        }

        Assertions.assertEquals(0, worst, 1e-9,
                "A balanced three-phase set must sum to zero at every instant");
    }

    @Test
    void sourcesRequestSubTicksForTheirFrequency() {
        var voltage = new ACVoltageSourceCoupling(new FloatingNode(), null, 0.001f, 10, 4);
        voltage.setSamplingPolicy(32, 16);
        // 4 Hz at 32 samples/cycle needs 6.4 samples per world tick, rounded up to 8.
        Assertions.assertEquals(8, voltage.requiredSubTicks());

        var current = new ACCurrentSourceNode(1, 4);
        current.setSamplingPolicy(32, 16);
        Assertions.assertEquals(8, current.requiredSubTicks(),
                "Both source types must agree on the rate for a given frequency");

        current.setSamplingPolicy(32, 4);
        Assertions.assertEquals(4, current.requiredSubTicks(), "Ceiling should be honoured");
    }

    @Test
    void retuningFrequencyDoesNotStepTheWaveform() {
        // Phase is integrated rather than evaluated from an absolute clock, so changing the
        // frequency continues from the angle already reached instead of jumping.
        var net = new Network();
        var ground = net.V(0);
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 10, FREQUENCY);
        net.network.addNode(terminal);
        net.network.addNode(source);
        net.W(10f, terminal, ground);

        for(int t = 0; t < 5; ++t)
            net.network.calculate(SUB_TICKS);

        var before = source.getPhase();
        source.setFrequency(FREQUENCY * 3);
        net.network.prepare(SUB_TICKS);
        net.network.singleTick();
        var after = source.getPhase();

        // One sub-tick at the new frequency, and nothing else. Compared as a wrapped difference
        // because the phase lives in [0, 2*pi) and the step may cross the wrap point.
        // Tolerance is 1e-7 rather than 1e-9 because ElectricalNetwork.getDeltaTime() computes
        // 0.05f / multiTick in float, so dt carries float precision into a double accumulator.
        // A genuine discontinuity would be a whole step, not a part in 1e8.
        var step = 2 * Math.PI * FREQUENCY * 3 * (0.05 / SUB_TICKS);
        var advanced = org.patryk3211.powergrid.electricity.sim.special.AcSampling.wrapAngle(after - before);
        Assertions.assertEquals(step, advanced, 1e-7,
                "Phase should continue from where it was, advancing at the new rate");
    }

    @Test
    void groundReferencedSourceStillSolves() {
        // A source with a null negative terminal is referenced to the solver's datum. Worth
        // pinning because every rig in these tests relies on it.
        var net = new Network();
        var terminal = new FloatingNode();
        var source = new ACVoltageSourceCoupling(terminal, null, 0.001f, 10, FREQUENCY);
        net.network.addNode(terminal);
        net.network.addNode(source);

        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var R = net.W(5f, terminal, ground);

        for(int i = 0; i < 40; ++i)
            net.network.calculate(SUB_TICKS);

        Assertions.assertEquals(10 / Math.sqrt(2), R.rmsVoltage(), 0.3,
                "Load should see the source's RMS voltage");
    }
}
