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
 * <h2>Relationship to the DC generator</h2>
 * This extends {@link GeneratorCoupling} to reuse the excitation plumbing the commutator block
 * entity already drives — the field-strength provider and the stored EMF state — and overrides
 * every method that carries DC behaviour. In particular the inherited {@code backEmf} fudge
 * resistance is never engaged, because {@link #setField(float)} does not compute it; a real
 * armature inductance is stamped instead, which is what that fudge was approximating. Nothing
 * about the DC machine changes: {@link GeneratorCoupling} is untouched and a commutator keeps
 * behaving exactly as it did.
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
 * hand side.
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
public class AlternatorCoupling extends GeneratorCoupling implements ISubTickRate {
    private static final double TWO_PI = Math.PI * 2;

    // The parent keeps its own private copies of these; this class deliberately shadows them
    // with its own state and overrides every method that reads the parent's, so the DC field
    // and back-EMF machinery is never engaged.
    private final IRotor acRotor;
    private float acField;
    private float acBaseResistance;
    private Precalculated<Float> acFieldStrength;

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

    public AlternatorCoupling(IElectricNode positive, @Nullable IElectricNode negative, Number resistance, IRotor rotor) {
        super(positive, negative, resistance, rotor);
        this.acRotor = rotor;
        acBaseResistance = resistance.floatValue();
        if(acBaseResistance <= 0)
            acBaseResistance = (float) (1 / G_MIN);
    }

    /** Sets the field strength without engaging the parent's back-EMF resistance. */
    @Override
    public void setField(float field) {
        this.acField = field;
    }

    public float getField() {
        return acField;
    }

    @Override
    public void setFieldStrengthProvider(Precalculated<Float> fieldStrength) {
        super.setFieldStrengthProvider(fieldStrength);
        this.acFieldStrength = fieldStrength;
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

    /**
     * Set the sampling policy, normally from {@code CSolver.acSamplesPerCycle} and
     * {@code CSolver.acMaxSubTicks}.
     */
    public void setSamplingPolicy(int samplesPerCycle, int maxSubTicks) {
        this.samplesPerCycle = Math.max(samplesPerCycle, 2);
        this.maxSubTicks = Math.max(maxSubTicks, 1);
    }

    @Override
    public void setResistance(float resistance) {
        acBaseResistance = resistance;
        if(acBaseResistance <= 0)
            acBaseResistance = (float) (1 / G_MIN);
        applyEffectiveResistance(deltaTime());
    }

    private double deltaTime() {
        return network == null ? AcSampling.TICK_SECONDS : network.getDeltaTime();
    }

    private static double wrap(double angle) {
        return AcSampling.wrapAngle(angle);
    }

    /**
     * Push {@code R + L/dt} into the source row, but only when it actually changed. Every write
     * counts as a conductance update, and enough of those trigger a full matrix rebuild — so
     * writing the same value every sub-tick would be quietly expensive.
     */
    private void applyEffectiveResistance(double dt) {
        var effective = (float) (acBaseResistance + (dt > 0 ? armatureInductance / dt : 0));
        if(effective == appliedResistance)
            return;
        appliedResistance = effective;
        // Routes through GeneratorCoupling.setResistance, which adds its backEmf term before
        // reaching the source row. That term is identically zero here because setField() is
        // overridden never to compute it, so this writes exactly `effective`.
        super.setResistance(effective);
    }

    /**
     * Excitation time constant in seconds. Long compared with one electrical cycle, because a
     * field winding's own L/R is.
     */
    private static final double FIELD_TAU = 0.25;

    /**
     * Mean of {@code |sin|} over a cycle is {@code 2/pi}, so a rectified average has to be
     * scaled by its reciprocal to recover the peak the field would have had.
     */
    private static final double FIELD_FORM_FACTOR = Math.PI / 2;

    /** Smoothed field, the slow DC quantity a real excitation circuit settles to. */
    private double excitation;

    /**
     * Turn an instantaneous field reading into the slowly varying excitation a real machine has.
     * <p>
     * This exists because a <em>self-excited</em> alternator — shunt or compound wound, taking
     * its field current from its own output — otherwise cannot build up at all. Sampling the
     * field instantaneously makes the excitation loop a product recursion,
     * {@code lambda -> g * lambda * sin(p*theta)}, where the DC machine's is simply
     * {@code lambda -> g * lambda}. Two things then go wrong: the geometric mean of
     * {@code |sin|} over a cycle is exactly one half, so the loop gain is permanently halved and
     * a build that self-excites on DC at a gain of 1.79 sits at 0.90 on AC — below unity; and the
     * sign reverses through every negative half cycle, which a winding with a 10 ms time constant
     * cannot follow. The field decays to its residual and the machine produces almost nothing.
     * <p>
     * Physically the field circuit rectifies, and its L/R is far longer than one electrical
     * cycle, so the field is a slow, one-signed quantity. All three steps below are load-bearing:
     * rectifying alone leaves the gain under unity, low-passing alone averages the sign reversal
     * to zero, and without the form factor the rectified mean is {@code 2/pi} of the peak.
     * <p>
     * Separately excited alternators — field fed from a battery or another DC source — were never
     * affected, and are unchanged by this.
     */
    private double excite(double instantaneous, double dt) {
        var alpha = dt / (FIELD_TAU + dt);
        excitation += alpha * (Math.abs(instantaneous) - excitation);
        return excitation * FIELD_FORM_FACTOR;
    }

    @Override
    public int requiredSubTicks() {
        // Electrical frequency in Hz. A stopped or slow machine asks for nothing and costs
        // nothing; the rounding and ceiling rules live in AcSampling so this and the bench AC
        // source cannot drift apart.
        var frequency = Math.abs(acRotor.getAngularVelocityRadians()) * polePairs / TWO_PI;
        return AcSampling.subTicksFor(frequency, samplesPerCycle, maxSubTicks);
    }

    @Override
    public void preSolve() {
        var dt = deltaTime();
        if(acFieldStrength != null)
            acField = (float) excite(acFieldStrength.get(), dt);

        var omega = acRotor.getAngularVelocityRadians();

        // Advance the clock first, then evaluate the waveform at the new angle. Unconditional
        // by design — see the class comment.
        phase = wrap(phase + omega * dt);
        phaseSine = Math.sin(polePairs * phase);

        applyEffectiveResistance(dt);
        setVoltage(acField * omega * phaseSine);
    }

    @Override
    public void addStaticResidual(IResidualAdder residual) {
        // Deliberately does NOT call GeneratorCoupling's version, which stamps the DC back-EMF
        // correction. The plain source contribution is the one line below.
        residual.add(index, getVoltage());

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
        acRotor.applyTickForce((float) (acField * phaseSine * getCurrent() / subTicks));
    }

    @Override
    public String toString() {
        return String.format("Alternator(%s p=%d theta=%.3f V=%g)", positive, polePairs, phase, getVoltage());
    }
}
