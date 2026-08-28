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
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.CapacitorWire;
import org.patryk3211.powergrid.electricity.sim.special.InductorWire;

/**
 * Measures the frequency-domain behaviour of the reactive components by driving them with a real
 * alternating source and reading the result out of the solver.
 * <p>
 * The point of these is to establish, rather than assert, that the existing backward-Euler
 * companion models already behave correctly under AC — that an inductor really does present
 * {@code omega*L}, that a capacitor really does present {@code 1/(omega*C)}, that the phase
 * relationships are right, and that a series LC really does resonate where theory says it should.
 * They also pin the RMS and power-factor metering against textbook values.
 *
 * <h2>Why 20 Hz</h2>
 * A world tick is 50 ms, so 20 Hz is exactly one electrical cycle per world tick. That matters
 * because the RMS accumulators reset every tick: a whole number of cycles per tick makes the
 * measured RMS exact rather than dependent on where the tick boundary fell.
 */
public class ReactiveAcTest extends TestHelper {
    /** One cycle per world tick. */
    private static final double FREQUENCY = 20;
    private static final double OMEGA = 2 * Math.PI * FREQUENCY;

    /** 64 samples per cycle; fine enough that discretisation error is well under a percent. */
    private static final int SUB_TICKS = 64;

    private static final double AMPLITUDE = 10;

    /** Source impedance, small enough not to disturb the component under test. */
    private static final float SOURCE_RESISTANCE = 0.001f;

    /**
     * Series damping resistance, and the reason it must not be zero.
     * <p>
     * An inductor energised from zero at a zero crossing carries a DC offset of exactly one
     * peak — {@code i(t) = (V/omega*L)(1 - cos(omega*t))} — and in a loop with no resistance
     * nothing removes it, so the current never becomes the symmetric sine that steady-state
     * reactance is defined for. That is correct physics, not a modelling artefact, but it means
     * any measurement of {@code omega*L} has to let the transient decay first. With L = 0.1 H
     * this gives a time constant of {@code L/R} = 0.1 s, so a few dozen ticks is ample.
     */
    private static final float DAMPING = 1f;

    private static class Rig {
        final Network net = new Network();
        final ACVoltageSourceCoupling source;
        final FloatingNode terminal;
        final FloatingNode ground;

        Rig() {
            terminal = new FloatingNode();
            source = new ACVoltageSourceCoupling(terminal, null, SOURCE_RESISTANCE, AMPLITUDE, FREQUENCY);
            net.network.addNode(terminal);
            net.network.addNode(source);
            ground = net.N();
            net.network.addNode(new org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling(ground, null, 0f, 0f));
        }

        /** A node one damping resistor away from the source terminal. */
        FloatingNode damped() {
            var mid = net.N();
            net.W(DAMPING, terminal, mid);
            return mid;
        }

        void run(int ticks) {
            for(int i = 0; i < ticks; ++i)
                net.network.calculate(SUB_TICKS);
        }
    }

    /** Magnitude of impedance seen by a component, from its own RMS metering. */
    private static double impedance(AbstractElectricWire wire) {
        return wire.rmsVoltage() / wire.rmsCurrent();
    }

    @Test
    void inductorPresentsOmegaL() {
        var inductance = 0.1;
        var rig = new Rig();
        var L = new InductorWire(inductance, rig.damped(), rig.ground);
        rig.net.network.addWire(L);

        rig.run(60);

        var expected = OMEGA * inductance;
        Assertions.assertEquals(expected, impedance(L), expected * 0.05,
                "Inductive reactance should be omega*L");
    }

    @Test
    void inductiveReactanceScalesWithFrequency() {
        // Doubling frequency must double the reactance. This is the property that distinguishes
        // a real inductor from a resistor, and it is what a DC-only model could not reproduce.
        var inductance = 0.1;

        var low = new Rig();
        var lowL = new InductorWire(inductance, low.damped(), low.ground);
        low.net.network.addWire(lowL);
        low.run(60);

        var high = new Rig();
        high.source.setFrequency(FREQUENCY * 2);
        var highL = new InductorWire(inductance, high.damped(), high.ground);
        high.net.network.addWire(highL);
        high.run(60);

        Assertions.assertEquals(2.0, impedance(highL) / impedance(lowL), 0.1,
                "Reactance should be proportional to frequency");
    }

    @Test
    void capacitorPresentsOneOverOmegaC() {
        var capacitance = 100e-6;
        var rig = new Rig();
        var C = new CapacitorWire(capacitance, rig.damped(), rig.ground);
        rig.net.network.addWire(C);

        rig.run(60);

        var expected = 1 / (OMEGA * capacitance);
        Assertions.assertEquals(expected, impedance(C), expected * 0.05,
                "Capacitive reactance should be 1/(omega*C)");
    }

    @Test
    void capacitiveReactanceFallsWithFrequency() {
        var capacitance = 100e-6;

        var low = new Rig();
        var lowC = new CapacitorWire(capacitance, low.damped(), low.ground);
        low.net.network.addWire(lowC);
        low.run(60);

        var high = new Rig();
        high.source.setFrequency(FREQUENCY * 2);
        var highC = new CapacitorWire(capacitance, high.damped(), high.ground);
        high.net.network.addWire(highC);
        high.run(60);

        Assertions.assertEquals(0.5, impedance(highC) / impedance(lowC), 0.05,
                "Capacitive reactance should be inversely proportional to frequency");
    }

    @Test
    void reactiveComponentsCarryNoRealPower() {
        // Power factor is P/S, and P is the mean of v*i across the cycle. For an ideal reactive
        // component that mean is zero: energy goes in during one quarter cycle and comes back
        // out during the next. A near-zero power factor is therefore the direct evidence that
        // voltage and current are a quarter cycle apart.
        var rig = new Rig();
        var L = new InductorWire(0.1, rig.damped(), rig.ground);
        rig.net.network.addWire(L);
        rig.run(60);

        Assertions.assertEquals(0, L.powerFactor(), 0.15,
                "An inductor should carry almost no real power");

        var rig2 = new Rig();
        var C = new CapacitorWire(100e-6, rig2.damped(), rig2.ground);
        rig2.net.network.addWire(C);
        rig2.run(60);

        Assertions.assertEquals(0, C.powerFactor(), 0.15,
                "A capacitor should carry almost no real power");
    }

    @Test
    void resistorIsInPhaseAndCarriesRealPower() {
        // The control case. Same source, same metering, but a resistor: power factor 1, and
        // real power equal to Vrms*Irms.
        var rig = new Rig();
        var R = rig.net.W(10f, rig.terminal, rig.ground);
        rig.run(40);

        Assertions.assertEquals(1.0, R.powerFactor(), 0.05,
                "A resistor should be in phase");
        Assertions.assertEquals(10.0, impedance(R), 0.5,
                "A resistor's impedance is its resistance, independent of frequency");

        // Vrms of a 10 V peak sine is 7.07 V, so a 10 ohm load dissipates 7.07^2/10 = 5 W.
        Assertions.assertEquals(5.0, R.power(), 0.5, "Real power should be Vrms^2/R");
    }

    @Test
    void seriesLcResonatesWhereTheoryPredicts() {
        // Chosen so 1/(2*pi*sqrt(LC)) lands exactly on 20 Hz.
        var inductance = 0.1;
        var capacitance = 1 / (inductance * OMEGA * OMEGA);

        // At resonance the inductive and capacitive reactances cancel and the loop is limited
        // only by resistance, so the current is far larger than it is off resonance.
        var atResonance = seriesLcCurrent(inductance, capacitance, FREQUENCY);
        var offResonance = seriesLcCurrent(inductance, capacitance, FREQUENCY * 2);

        Assertions.assertTrue(atResonance > offResonance * 5,
                "Series LC should pass far more current at resonance than off it, got "
                        + atResonance + " vs " + offResonance);
    }

    /**
     * RMS current through a series R-L-C loop driven at the given frequency.
     * <p>
     * The resistance must be in series with the loop, not across the capacitor — a shunt
     * resistor damps the capacitor but leaves the L-C mesh its own path, which is a different
     * circuit with a different response.
     */
    private double seriesLcCurrent(double inductance, double capacitance, double frequency) {
        var rig = new Rig();
        rig.source.setFrequency(frequency);

        var afterResistor = rig.damped();
        var mid = rig.net.N();

        var L = new InductorWire(inductance, afterResistor, mid);
        rig.net.network.addWire(L);
        var C = new CapacitorWire(capacitance, mid, rig.ground);
        rig.net.network.addWire(C);

        rig.run(80);
        return L.rmsCurrent();
    }

    /**
     * Stored charge must bleed away at a rate set by elapsed world time, not by how finely the
     * island happens to be stepped.
     * <p>
     * The components shed a fraction of their stored state each step so a floating charge does
     * not persist forever. That fraction used to be applied per sub-tick, so an island stepped
     * sixteen times per tick lost charge sixteen times faster per real second than an identical
     * one stepped once — meaning a capacitor bank started draining measurably quicker the moment
     * an alternator elsewhere on the grid spun up and raised the island's rate.
     */
    @Test
    void leakageDoesNotDependOnSubTickRate() {
        Assertions.assertEquals(retainedCharge(1), retainedCharge(16), 1e-4,
                "A capacitor should hold its charge equally well at any sub-tick rate");
        Assertions.assertEquals(retainedCharge(1), retainedCharge(8), 1e-4,
                "A capacitor should hold its charge equally well at any sub-tick rate");
    }

    /** Fraction of its initial voltage a floating capacitor keeps after five seconds. */
    private double retainedCharge(int subTicks) {
        var net = new Network();
        var ground = net.V(0);
        var node = net.N();
        var C = new CapacitorWire(100e-6, node, ground);
        net.network.addWire(C);
        // Large enough that real discharge is negligible over the window, leaving only the
        // artificial leak: R*C here is 1e5 seconds.
        net.W(1e9f, node, ground);

        C.setVoltage(10);
        // Five seconds of world time, whatever the sub-tick rate.
        for(int i = 0; i < 100; ++i)
            net.network.calculate(subTicks);

        return node.getVoltage() / 10.0;
    }

    @Test
    void sourceAmplitudeAndRmsAgree() {
        var rig = new Rig();
        var R = rig.net.W(10f, rig.terminal, rig.ground);
        rig.run(40);

        Assertions.assertEquals(AMPLITUDE / Math.sqrt(2), rig.source.getRmsVoltage(), 1e-9,
                "Declared RMS should be peak/sqrt(2)");
        Assertions.assertEquals(rig.source.getRmsVoltage(), R.rmsVoltage(), 0.3,
                "Measured RMS across the load should match the source, less its own resistance");
    }
}
