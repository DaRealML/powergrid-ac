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
import org.patryk3211.powergrid.electricity.sim.calculation.Precalculated;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.IResidualAdder;
import org.patryk3211.powergrid.electricity.sim.solver.ISubTickRate;

import static org.patryk3211.powergrid.electricity.sim.ElectricalNetwork.G_MIN;

/**
 * A synchronous alternator: an EMF that alternates with the shaft angle rather than being
 * constant in it.
 *
 * <h2>Machine model</h2>
 * The shaft angle is integrated directly from rotor speed,
 * <pre>
 *     theta(t+dt) = theta(t) + omega * dt
 * </pre>
 * and the generated EMF follows the angle rather than the speed alone:
 * <pre>
 *     e(t) = lambda * omega * sin(p * theta)
 * </pre>
 * with {@code lambda} the field strength (the same quantity {@link GeneratorCoupling} calls
 * {@code field}), {@code omega} the mechanical angular velocity in rad/s, and {@code p} the
 * number of pole pairs. Setting {@code p = 1} makes the peak EMF identical to what the DC
 * generator produces at the same shaft speed, so an alternator and a dynamo on the same shaft
 * are directly comparable.
 * <p>
 * A note on that formula, because it is a modelling choice and not the only defensible one: a
 * physical machine's EMF amplitude scales with the rate of flux change, which is the
 * <em>electrical</em> angular velocity {@code p * omega}, giving {@code lambda * p * omega} as
 * the peak. The form used here holds the peak independent of pole count and lets {@code p}
 * select frequency alone. Multiply by {@code p} in {@link #preSolve()} for the strict form.
 *
 * <h2>Armature reactance</h2>
 * A real winding has inductance, and without it two alternators connected in parallel are two
 * ideal voltage sources fighting each other — any phase difference produces a current limited
 * only by winding resistance. The armature inductance is stamped with the same backward-Euler
 * companion model the rest of the mod uses for inductors. Starting from
 * {@code V+ - V- - R*i - L*di/dt = e} and taking {@code di/dt ~ (i - i_prev)/dt}:
 * <pre>
 *     V+ - V- - (R + L/dt) * i = e - (L/dt) * i_prev
 * </pre>
 * so the inductance costs one addition to the source row's diagonal and one term on the right
 * hand side. This is the honest version of the fudge {@link GeneratorCoupling} applies as a
 * synthetic {@code backEmf} resistance, whose own comment admits it is standing in for
 * "an inductor like behaviour".
 *
 * <h2>Why the phase advances unconditionally</h2>
 * Every reactive component in the mod gates its state update on {@code isConverged()}, and the
 * network deliberately forces a non-converged tick after any structural change so components
 * settle. Phase cannot take part in that: it is a clock, and a clock that stops whenever a
 * player places a wire loses synchronisation with every other machine on the grid. The phase
 * integration therefore lives in {@link #preSolve()}, which runs once per sub-tick before the
 * right-hand side is built and is not gated on convergence. Only the torque feedback, which is
 * a physical quantity rather than a clock, is gated.
 *
 * <h2>Limits of the mechanical feedback</h2>
 * Electrical torque is returned through {@link IRotor#applyTickForce(float)}, which only
 * accumulates into the rotor's force sum; the rotor consumes it in its block-entity tick, which
 * runs after the entire electrical solve for that world tick. Angular velocity is therefore
 * constant across all sub-ticks and the speed-phase-torque loop closes once per world tick, one
 * tick late. That is adequate here because the mechanical swing of a synchronous machine is a
 * sub-hertz phenomenon while the electrical waveform is a few hertz, so the two need very
 * different resolutions — but it does mean the swing dynamics are sampled at 20 Hz, not at the
 * sub-tick rate, and very light rotors may hunt rather than settle.
 */
public class AlternatorCoupling extends VoltageSourceCoupling implements IOuterHook, ISubTickRate {
    private static final double TWO_PI = Math.PI * 2;

    private final IRotor rotor;

    private float field;
    private float baseResistance;
    private int polePairs = 1;

    /** Armature (synchronous) inductance in henries. Zero disables the companion model. */
    private double armatureInductance = 0;

    /** Shaft angle in radians, wrapped to [0, 2*pi). Integrated once per solver sub-tick. */
    private double phase = 0;

    /** sin(p * phase) for the sub-tick currently being solved, reused for the torque term. */
    private double phaseSine = 0;

    /** Source current from the previous sub-tick, for the inductor companion model. */
    private double previousCurrent = 0;

    /** Effective series resistance last written to the matrix, to avoid redundant updates. */
    private float appliedResistance = Float.NaN;

    // Sampling policy. Held as fields rather than read from the mod config here so that this
    // package stays free of Minecraft imports and remains unit-testable; the owning block
    // entity pushes the configured values in.
    private int samplesPerCycle = 32;
    private int maxSubTicks = 16;

    private Precalculated<Float> fieldStrength;

    public AlternatorCoupling(IElectricNode positive, @Nullable IElectricNode negative, Number resistance, IRotor rotor) {
        super(positive, negative, resistance);
        this.rotor = rotor;
        baseResistance = resistance.floatValue();
        if(baseResistance <= 0)
            baseResistance = (float) (1 / G_MIN);
    }

    public void setField(float field) {
        this.field = field;
    }

    public float getField() {
        return field;
    }

    /**
     * Number of pole pairs, which multiplies shaft speed into electrical frequency:
     * {@code f = rpm * p / 60}. A Create shaft tops out near 272 rpm, so a single pole pair
     * gives roughly 4.5 Hz.
     */
    public void setPolePairs(int polePairs) {
        this.polePairs = Math.max(polePairs, 1);
    }

    public int getPolePairs() {
        return polePairs;
    }

    public void setArmatureInductance(double inductance) {
        this.armatureInductance = Math.max(inductance, 0);
    }

    public double getArmatureInductance() {
        return armatureInductance;
    }

    /**
     * Shaft angle in radians. Exposed so the owning block entity can persist it: unlike the
     * render angle on the rotor, this value must survive a save/load or every machine on the
     * grid comes back with an undefined phase relationship to its neighbours.
     */
    public double getPhase() {
        return phase;
    }

    public void setPhase(double phase) {
        if(!Double.isFinite(phase))
            return;
        this.phase = wrap(phase);
    }

    /** Instantaneous EMF as a fraction of peak, i.e. sin(p * theta). Useful for gauges. */
    public double getPhaseSine() {
        return phaseSine;
    }

    public void setFieldStrengthProvider(Precalculated<Float> fieldStrength) {
        this.fieldStrength = fieldStrength;
    }

    @Override
    public void setResistance(float resistance) {
        baseResistance = resistance;
        if(baseResistance <= 0)
            baseResistance = (float) (1 / G_MIN);
        applyEffectiveResistance(deltaTime());
    }

    private double deltaTime() {
        return network == null ? 0.05 : network.getDeltaTime();
    }

    private static double wrap(double angle) {
        var wrapped = angle % TWO_PI;
        return wrapped < 0 ? wrapped + TWO_PI : wrapped;
    }

    /**
     * Push {@code R + L/dt} into the source row, but only when it actually changed. Every call
     * to the parent counts as a conductance update, and enough of those trigger a full matrix
     * rebuild — so writing the same value every sub-tick would be quietly expensive.
     */
    private void applyEffectiveResistance(double dt) {
        var effective = (float) (baseResistance + (dt > 0 ? armatureInductance / dt : 0));
        if(effective == appliedResistance)
            return;
        appliedResistance = effective;
        super.setResistance(effective);
    }

    /**
     * Set the sampling policy, normally from {@code CSolver.acSamplesPerCycle} and
     * {@code CSolver.acMaxSubTicks}.
     */
    public void setSamplingPolicy(int samplesPerCycle, int maxSubTicks) {
        this.samplesPerCycle = Math.max(samplesPerCycle, 2);
        this.maxSubTicks = Math.max(maxSubTicks, 1);
    }

    @Override
    public int requiredSubTicks() {
        // Electrical frequency in Hz. Below one cycle per world tick there is nothing to
        // resolve, so a stopped or slow machine asks for nothing and costs nothing.
        var frequency = Math.abs(rotor.getAngularVelocityRadians()) * polePairs / TWO_PI;
        if(frequency <= 0)
            return 1;

        // Samples needed across one world tick = samples per cycle * cycles per world tick.
        var needed = samplesPerCycle * frequency * 0.05;

        // Round up to a power of two. Two reasons: every rate then divides the world tick
        // evenly so the stepping schedule is uniform, and the rate only changes at octave
        // boundaries instead of on every small speed change. That matters because changing the
        // sub-tick count changes dt, which re-derives every capacitor and inductor conductance
        // and dirties the matrix.
        var rate = 1;
        while(rate < needed && rate < maxSubTicks)
            rate <<= 1;
        return Math.min(rate, maxSubTicks);
    }

    @Override
    public void preSolve() {
        if(fieldStrength != null)
            field = fieldStrength.get();

        var dt = deltaTime();
        var omega = rotor.getAngularVelocityRadians();

        // Advance the clock first, then evaluate the waveform at the new angle. Unconditional
        // by design — see the class comment.
        phase = wrap(phase + omega * dt);
        phaseSine = Math.sin(polePairs * phase);

        applyEffectiveResistance(dt);
        setVoltage(field * omega * phaseSine);
    }

    @Override
    public void addStaticResidual(IResidualAdder residual) {
        // Contributes the source EMF.
        super.addStaticResidual(residual);

        // Companion source of the armature inductance: (L/dt) * i_prev.
        if(armatureInductance > 0) {
            var dt = deltaTime();
            if(dt > 0)
                residual.add(index, armatureInductance / dt * previousCurrent);
        }
    }

    @Override
    public void postUpperSolve() {
        if(!isConverged())
            return;

        previousCurrent = getCurrent();

        // Electrical torque. Instantaneous power is e*i = lambda*omega*sin(p*theta)*i, and
        // torque is power over speed, so omega cancels and the load on the shaft is
        // lambda*sin(p*theta)*i — largest when the EMF is at peak, zero at the zero crossing.
        // Divided by the sub-tick count because applyTickForce accumulates across every
        // sub-tick and the rotor consumes the sum once per world tick, so this has to be a
        // mean rather than a total.
        var subTicks = network == null ? 1 : Math.max(network.getMultiTick(), 1);
        rotor.applyTickForce((float) (field * phaseSine * getCurrent() / subTicks));
    }

    @Override
    public String toString() {
        return String.format("Alternator(%s p=%d theta=%.3f V=%g)", positive, polePairs, phase, getVoltage());
    }
}
