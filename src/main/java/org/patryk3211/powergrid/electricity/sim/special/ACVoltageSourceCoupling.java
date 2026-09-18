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

import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.ISubTickRate;

import static org.patryk3211.powergrid.electricity.sim.special.AcSampling.TWO_PI;

/**
 * An ideal alternating voltage source with a settable frequency — a bench signal generator
 * rather than a machine.
 * <pre>
 *     v(t) = amplitude * sin(2*pi*f*t + offset)
 * </pre>
 * <p>
 * This is the counterpart to {@link AlternatorCoupling}, which derives its frequency from a
 * shaft and feeds torque back into it. Here frequency is simply a number, which makes it the
 * right thing for testing a filter, driving a rectifier, or measuring a component's reactance
 * without a rotor in the way.
 *
 * <h2>In a world, phase is read from the game time</h2>
 * When the island carries a world tick ({@link org.patryk3211.powergrid.electricity.sim.ElectricalNetwork#getWorldTick()}),
 * the angle is {@code 2*pi*f*t} with {@code t} the game time plus this source's own position in
 * the tick. That is what gives {@link #setPhaseOffset(double)} a meaning between sources that
 * were not built together. An integrated angle starts wherever its source was built, and a
 * circuit rebuild -- any wire placed anywhere on the island -- starts it again from zero, so two
 * sources set 120 degrees apart would in practice sit at whatever angle build order left them.
 * Anchored to game time, two sources at one frequency differ by exactly their offsets, and still
 * do after a rebuild or a reload.
 * <p>
 * The cost is on retuning. Changing the frequency moves the absolute phase of the new frequency,
 * so the waveform steps. That is the right trade for a reference supply, and in game the only
 * way to retune is a command that sets the amplitude in the same breath anyway. If the level's
 * clock stops while networks keep solving -- a frozen tick rate -- the angle extrapolates from
 * the last tick, and steps back once time resumes.
 *
 * <h2>Without a world, phase is integrated</h2>
 * The angle advances by {@code 2*pi*f*dt} each sub-tick. It stays continuous when the frequency
 * is changed: a source retuned from 4 Hz to 12 Hz carries on from the angle it had reached. It
 * also keeps running through warm-up, which {@link #preSolve()} is not gated on — the same
 * reasoning as the alternator's shaft angle.
 *
 * <h2>Amplitude is peak, not RMS</h2>
 * {@code amplitude} is the peak of the sine. For a sinusoid the RMS value a meter would show is
 * {@code amplitude / sqrt(2)}, available from {@link #getRmsVoltage()}.
 */
public class ACVoltageSourceCoupling extends VoltageSourceCoupling implements IOuterHook, ISubTickRate {
    private double amplitude;
    private double frequency;
    private double phaseOffset;

    /**
     * Constant added to the sine. Lets one source express a DC supply (amplitude zero), a pure
     * alternating one (offset zero), or the rectifier-style mixture of both.
     */
    private double dcOffset;

    /** Angle in radians, wrapped to {@code [0, 2*pi)}, before {@link #phaseOffset} is added. */
    private double phase;

    private final AcSampling.TickTimer worldTimer = new AcSampling.TickTimer();

    // Sampling policy. Zero means "not set", and the configured value is read at the point of use
    // so that changing it in game takes effect without rebuilding the circuit. A caller that sets
    // one explicitly -- the tests do -- overrides the config.
    private int samplesPerCycle = 0;
    private int maxSubTicks = 0;

    public ACVoltageSourceCoupling(IElectricNode positive, @Nullable IElectricNode negative, float resistance) {
        super(positive, negative, resistance);
    }

    public ACVoltageSourceCoupling(IElectricNode positive, @Nullable IElectricNode negative, Number resistance) {
        super(positive, negative, resistance);
    }

    public ACVoltageSourceCoupling(IElectricNode positive, @Nullable IElectricNode negative, Number resistance,
                                   double amplitude, double frequency) {
        super(positive, negative, resistance);
        this.amplitude = amplitude;
        this.frequency = frequency;
    }

    /** Peak voltage of the sine. */
    public void setAmplitude(double amplitude) {
        this.amplitude = amplitude;
    }

    public double getAmplitude() {
        return amplitude;
    }

    /** RMS of the generated sine, which is what a meter on this source would read. */
    public double getRmsVoltage() {
        return amplitude / Math.sqrt(2);
    }

    /** Sets the peak from an RMS figure, for when a supply is specified the way mains is. */
    public void setRmsVoltage(double rms) {
        this.amplitude = rms * Math.sqrt(2);
    }

    public void setFrequency(double frequency) {
        this.frequency = Math.max(frequency, 0);
    }

    public double getFrequency() {
        return frequency;
    }

    /**
     * Fixed angle added to the integrated phase. Two sources sharing a frequency but differing
     * here are the basis of a polyphase supply: three of them at 0, 2pi/3 and 4pi/3 make a
     * balanced three-phase set.
     */
    public void setPhaseOffset(double phaseOffset) {
        this.phaseOffset = AcSampling.wrapAngle(phaseOffset);
    }

    public double getPhaseOffset() {
        return phaseOffset;
    }

    /** The integrated angle, exposed so an owning block entity can persist it across a reload. */
    public double getPhase() {
        return phase;
    }

    public void setPhase(double phase) {
        if(Double.isFinite(phase))
            this.phase = AcSampling.wrapAngle(phase);
    }

    public void setDcOffset(double dcOffset) {
        this.dcOffset = dcOffset;
    }

    public double getDcOffset() {
        return dcOffset;
    }

    public void setSamplingPolicy(int samplesPerCycle, int maxSubTicks) {
        this.samplesPerCycle = Math.max(samplesPerCycle, 2);
        this.maxSubTicks = Math.max(maxSubTicks, 1);
    }

    @Override
    public int requiredSubTicks() {
        return AcSampling.subTicksFor(frequency,
                samplesPerCycle > 0 ? samplesPerCycle : AcSampling.configuredSamplesPerCycle(),
                maxSubTicks > 0 ? maxSubTicks : AcSampling.configuredMaxSubTicks());
    }

    @Override
    public void preSolve() {
        var dt = network == null ? AcSampling.TICK_SECONDS : network.getDeltaTime();
        var worldTick = network == null ? -1 : network.getWorldTick();
        if(worldTick < 0)
            phase = AcSampling.wrapAngle(phase + TWO_PI * frequency * dt);
        else
            phase = AcSampling.anchoredPhase(frequency, worldTick, worldTimer.step(worldTick, dt));
        setVoltage(dcOffset + amplitude * Math.sin(phase + phaseOffset));
    }

    @Override
    public String toString() {
        return String.format("ACVoltageSource(%s %gV %gHz)", positive, amplitude, frequency);
    }
}
