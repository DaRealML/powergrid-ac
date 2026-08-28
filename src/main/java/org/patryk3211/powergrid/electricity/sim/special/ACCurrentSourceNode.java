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
package org.patryk3211.powergrid.electricity.sim.special;

import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceNode;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.ISubTickRate;

import static org.patryk3211.powergrid.electricity.sim.special.AcSampling.TWO_PI;

/**
 * An alternating current source — the dual of {@link ACVoltageSourceCoupling}.
 * <pre>
 *     i(t) = dcOffset + amplitude * sin(2*pi*f*t + phaseOffset)
 * </pre>
 * <p>
 * Where a voltage source fixes the potential across its terminals and lets the circuit decide
 * the current, this fixes the current and lets the circuit decide the voltage. That makes it the
 * natural way to model anything that pushes a set current regardless of load — and the right
 * tool for driving a network whose impedance you are trying to measure.
 *
 * <h2>Built on the node, not the wire</h2>
 * The mod has both {@link CurrentSourceNode} and
 * {@link org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire}, and this extends the
 * node deliberately. {@link ISubTickRate} is collected from nodes in
 * {@code ElectricalNetwork.addNode} and from wires in {@code addWire}; the node path is the one
 * every other alternating source already uses, and keeping all of them on it means a single
 * place decides how finely an AC island is stepped.
 * <p>
 * A current source node stamps nothing into the admittance matrix — its whole contribution is
 * one right-hand-side entry at its own row, which is a KCL row, so a positive value is current
 * injected into the node. It therefore needs a conducting path to a reference or the system is
 * singular.
 *
 * <h2>isSource() must stay constant</h2>
 * Inherited as {@code true} from {@link CurrentSourceNode} and deliberately not overridden.
 * {@code ElectricalNetwork} counts sources once, when the node is added, and an island whose
 * count reaches zero is short-circuited to the trivial solution — {@code singleTick()} returns
 * before any hook runs, so {@link #preSolve()} would never fire and the phase would never
 * advance. Making this depend on the amplitude would also desynchronise that count.
 */
public class ACCurrentSourceNode extends CurrentSourceNode implements IOuterHook, ISubTickRate {
    private double amplitude;
    private double frequency;
    private double phaseOffset;
    private double dcOffset;

    /** Integrated angle in radians, wrapped to {@code [0, 2*pi)}. */
    private double phase;

    private int samplesPerCycle = 32;
    private int maxSubTicks = 16;

    public ACCurrentSourceNode() {
    }

    public ACCurrentSourceNode(double amplitude, double frequency) {
        this.amplitude = amplitude;
        this.frequency = frequency;
    }

    /** Peak current of the sine, in amperes. */
    public void setAmplitude(double amplitude) {
        this.amplitude = amplitude;
    }

    public double getAmplitude() {
        return amplitude;
    }

    /** RMS of the generated sine, which is what a meter in the loop would read. */
    public double getRmsCurrent() {
        return amplitude / Math.sqrt(2);
    }

    public void setRmsCurrent(double rms) {
        this.amplitude = rms * Math.sqrt(2);
    }

    public void setFrequency(double frequency) {
        this.frequency = Math.max(frequency, 0);
    }

    public double getFrequency() {
        return frequency;
    }

    public void setPhaseOffset(double phaseOffset) {
        this.phaseOffset = AcSampling.wrapAngle(phaseOffset);
    }

    public double getPhaseOffset() {
        return phaseOffset;
    }

    /** Constant current added to the sine; set the amplitude to zero for a pure DC source. */
    public void setDcOffset(double dcOffset) {
        this.dcOffset = dcOffset;
    }

    public double getDcOffset() {
        return dcOffset;
    }

    public double getPhase() {
        return phase;
    }

    public void setPhase(double phase) {
        if(Double.isFinite(phase))
            this.phase = AcSampling.wrapAngle(phase);
    }

    public void setSamplingPolicy(int samplesPerCycle, int maxSubTicks) {
        this.samplesPerCycle = Math.max(samplesPerCycle, 2);
        this.maxSubTicks = Math.max(maxSubTicks, 1);
    }

    @Override
    public int requiredSubTicks() {
        return AcSampling.subTicksFor(frequency, samplesPerCycle, maxSubTicks);
    }

    @Override
    public void preSolve() {
        var network = getNetwork();
        var dt = network == null ? AcSampling.TICK_SECONDS : network.getDeltaTime();
        phase = AcSampling.wrapAngle(phase + TWO_PI * frequency * dt);
        setCurrent(dcOffset + amplitude * Math.sin(phase + phaseOffset));
    }

    @Override
    public String toString() {
        return String.format("ACCurrentSource(%gA %gHz)", amplitude, frequency);
    }
}
