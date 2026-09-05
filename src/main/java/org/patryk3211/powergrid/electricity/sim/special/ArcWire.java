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

import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.IResidualAdder;
import org.patryk3211.powergrid.electricity.sim.solver.IStaticResidual;

/**
 * An electric arc across a gap.
 *
 * <h2>An arc is not a resistor</h2>
 * The thing that makes an arc an arc is that its voltage barely depends on its current. A column of
 * plasma has a fixed electrode fall of a few tens of volts plus a gradient along its length, and
 * pushing more current through it makes it hotter and wider rather than raising the voltage across
 * it — so {@code dV/di} is near zero and, over part of the range, negative. That is why an arc
 * needs series impedance to be stable, why it does not obey Ohm's law, and why a switch that draws
 * one does not simply stop conducting.
 *
 * <p>Everything the mod had before this was ohmic. {@code SparkGapBlockEntity} builds a 1 Ω
 * {@link org.patryk3211.powergrid.electricity.sim.SwitchedWire} and the HV switch randomises a
 * resistance between 0.5 and 5 Ω, so both present a voltage proportional to their current: a gap
 * set to strike at 1000 V measured only 14.92 V once conducting at 14.9 A, where a real arc would
 * have sat near 40 V whatever the current.
 *
 * <h2>How it is stamped</h2>
 * As a Norton equivalent: a fixed conductance {@code G} with a static residual current of
 * {@code V_arc * G * sign(v)}, which is a fixed voltage drop in series with {@code 1/G}. This is
 * the same shape {@link NeonBulbWire} uses for a glow discharge, and it is deliberate that it is
 * <em>not</em> an {@link org.patryk3211.powergrid.electricity.sim.solver.ISolverHook}: a genuine
 * Newton-iterated negative resistance is both a convergence hazard and would set
 * {@code ElectricalNetwork.hasHooks()}, costing the island {@code JavaMNA.singleTickLinear()} — the
 * fast path that lets a linear island skip the Newton loop entirely. Re-evaluating a piecewise
 * linear source between sub-ticks buys the constant-voltage law at none of that cost.
 *
 * <h2>Why it dies on alternating current and not on direct</h2>
 * This is the whole point, and it is the behaviour the mod could not previously express. A real
 * arc goes out at every current zero: the plasma stops being driven, and it only continues if the
 * circuit's recovering voltage beats the gap's own recovering strength. An alternating supply hands
 * it a zero twice a cycle. A direct one never does, which is why DC switchgear is rated far below
 * AC at the same voltage and why breaking a DC circuit under load is the hard case.
 *
 * <p>The zero is detected as a <b>sign change between consecutive sub-ticks</b>, never as a
 * magnitude falling below a threshold. That is not a stylistic choice. Measured on the shipped
 * sampling, the closest the solver comes to a current zero within a half cycle is 4.4% of peak at
 * 4.5 Hz but 34.9% at 72.5 Hz — so any fixed threshold either never fires at the top of the
 * alternator's range or fires constantly at the bottom. A sign change is exact at every rate,
 * because it asks whether a zero happened rather than how close a sample came to one.
 *
 * <h2>Dielectric recovery</h2>
 * After a zero the gap is still full of hot gas and will restrike at barely more than the arc
 * voltage; as it cools, the voltage it can withstand climbs back toward the cold breakdown value.
 * {@link #deionisationSeconds} is how long that takes. It is what decides whether an arc drawn on
 * an alternating supply reignites through zero after zero — which is what really happens — or
 * whether the gap wins and clears the fault.
 */
public class ArcWire extends AbstractElectricWire implements IOuterHook, IStaticResidual {
    /**
     * Conductance of an open gap.
     * <p>
     * Not zero, because a row of the admittance matrix with nothing in it is singular. This is the
     * same order as {@code SwitchedWire.OFF_CONDUCTANCE} and represents leakage across the gap.
     */
    public static final double OPEN_CONDUCTANCE = 1e-9;

    /**
     * Gap below which there is no arc, in metres.
     * <p>
     * An arc is a column of plasma and a column needs length. More practically: the strength of a
     * gap is its length times the dielectric strength, so a gap of zero withstands zero volts and
     * would read as permanently broken down — closed contacts would hold an arc lit forever
     * instead of shorting it out, which is the opposite of what touching contacts do. Ten microns
     * is far below any gap the mod can produce and comfortably above floating-point noise.
     */
    public static final float MINIMUM_GAP = 1e-5f;

    /** Volts dropped at the electrodes, independent of gap length and of current. */
    private final float electrodeFall;

    /** Volts per metre along the plasma column — the part of the arc voltage the gap length buys. */
    private final float gradient;

    /** Conductance of the struck channel. Large: the arc's voltage comes from the residual, not this. */
    private final float channelConductance;

    /** Field the cold gap withstands, in volts per metre. Dry air is about 3e6. */
    private final float dielectricStrength;

    /** How long the gap takes to recover its full cold strength after the arc goes out. */
    private final float deionisationSeconds;

    private float gapMetres;

    private boolean struck;

    /** The Norton source current. Named {@code I} to match {@link NeonBulbWire}'s stamping. */
    private double I;

    /** Sign of the current at the previous sub-tick, for zero detection. Zero means "no sample yet". */
    private double previousSign;

    /** Seconds since the arc last went out. Drives {@link #reignitionVoltage()}. */
    private double darkSeconds = Double.MAX_VALUE;

    /** Energy the arc has dissipated since it was last drained, in joules. */
    private double energy;

    /** Times the arc has reignited since the counter was last drained. */
    private int restrikes;

    public ArcWire(float electrodeFall, float gradient, float channelConductance,
                   float dielectricStrength, float deionisationSeconds, float gapMetres,
                   IElectricNode node1, IElectricNode node2) {
        super(node1, node2);
        this.electrodeFall = electrodeFall;
        this.gradient = gradient;
        this.channelConductance = channelConductance;
        this.dielectricStrength = dielectricStrength;
        this.deionisationSeconds = deionisationSeconds;
        this.gapMetres = Math.max(gapMetres, 0);
    }

    /** Voltage the arc sustains across itself, which is what makes it not a resistor. */
    public double arcVoltage() {
        return electrodeFall + gradient * gapMetres;
    }

    /** Voltage needed to break down a cold gap of this length. */
    public double breakdownVoltage() {
        return dielectricStrength * gapMetres;
    }

    /**
     * Voltage needed to restrike, which climbs from the arc voltage to the full cold breakdown
     * value as the gap deionises.
     * <p>
     * Immediately after a current zero this is barely above {@link #arcVoltage()}, so an
     * alternating supply reignites the arc almost every half cycle and the arc appears continuous.
     * Give the gap long enough — by opening it further, or by the current staying away — and it
     * recovers and the arc is cleared.
     */
    public double reignitionVoltage() {
        if(darkSeconds >= deionisationSeconds)
            return breakdownVoltage();
        var recovered = darkSeconds / deionisationSeconds;
        var cold = breakdownVoltage();
        var hot = arcVoltage();
        return hot + recovered * (cold - hot);
    }

    public boolean isStruck() {
        return struck;
    }

    /** Contact separation in metres. Widening a struck gap raises its voltage and can clear it. */
    public void setGap(float metres) {
        this.gapMetres = Math.max(metres, 0);
    }

    public float getGap() {
        return gapMetres;
    }

    /**
     * Joules dissipated since the last call, and reset.
     * <p>
     * Drained rather than read so that a caller ticking once per world tick collects every
     * sub-tick's contribution exactly once — the arc is integrated at the solver's rate and
     * consumed at the world's.
     */
    public double drainEnergy() {
        var drained = energy;
        energy = 0;
        return drained;
    }

    /** Reignitions since the last call, and reset. Zero on a steady supply once struck. */
    public int drainRestrikes() {
        var drained = restrikes;
        restrikes = 0;
        return drained;
    }

    /**
     * Put the arc out and treat the gap as fully recovered.
     * <p>
     * For the case physics cannot express here: contacts that have closed. A real arc between
     * touching contacts is shorted out by the contacts themselves, but the gap length this class
     * reasons from goes to zero at exactly that moment — and a zero-length gap withstands zero
     * volts, so it would read as permanently broken down and hold the arc lit forever. A closing
     * switch says so explicitly instead.
     */
    public void quench() {
        setStruck(false);
        darkSeconds = Double.MAX_VALUE;
        previousSign = 0;
    }

    private double deltaTime() {
        return network == null ? 0.05 : network.getDeltaTime();
    }

    private void setStruck(boolean struck) {
        if(this.struck == struck)
            return;
        // Read the old conductance before flipping the flag: conductance() is derived from it, so
        // taking the difference afterwards would compare the new value against itself and stamp a
        // delta of exactly zero, leaving the matrix describing a gap in the state it just left.
        var previous = conductance();
        this.struck = struck;
        if(network != null) {
            network.updateConductance(this, conductance() - previous);
            network.warmUp(1);
        }
    }

    @Override
    public double conductance() {
        return struck ? channelConductance : OPEN_CONDUCTANCE;
    }

    @Override
    public void preSolve() {
        if(!isConverged())
            return;

        var dt = deltaTime();
        var current = current();
        var sign = Math.signum(current);

        if(struck) {
            // The zero crossing, caught by the sign of the current changing between two sub-ticks
            // rather than by its magnitude getting small. A steady supply never takes this branch,
            // which is exactly why a direct-current arc does not put itself out.
            if(previousSign != 0 && sign != 0 && sign != previousSign) {
                setStruck(false);
                darkSeconds = 0;
            }
        }

        if(gapMetres < MINIMUM_GAP) {
            // Contacts touching. There is no column to sustain, and the conductors themselves are
            // the better path, so whatever was burning is shorted out.
            if(struck)
                quench();
            previousSign = 0;
            return;
        }

        if(!struck) {
            darkSeconds += dt;
            // Restrike while the gas is still hot, or break down a cold gap. One test serves both
            // because reignitionVoltage() interpolates between them as the gap recovers.
            if(Math.abs(potentialDifference()) > reignitionVoltage()) {
                setStruck(true);
                ++restrikes;
                darkSeconds = 0;
                sign = 0;
            }
        }

        previousSign = sign;
    }

    @Override
    public void postUpperSolve() {
        if(!isConverged() || !struck)
            return;
        // Real power into the column, integrated at the solver's rate. The arc voltage is what the
        // plasma sustains, so this is the energy the gap actually has to get rid of -- it is not
        // i^2*R through the channel conductance, which is a modelling artefact.
        energy += Math.abs(arcVoltage() * current()) * deltaTime();
    }

    @Override
    public void addStaticResidual(IResidualAdder residual) {
        if(struck) {
            // Norton form of a fixed voltage drop: the sign follows the branch voltage so the arc
            // opposes the current whichever way it is flowing.
            I = arcVoltage() * channelConductance * Math.signum(potentialDifference());
            residual.add(node1.getIndex(), I);
            residual.add(node2.getIndex(), -I);
        } else {
            I = 0;
        }
    }

    /** The residual is a source in parallel with the channel, so it comes back out of the branch. */
    @Override
    public double current() {
        return super.current() - I;
    }

    @Override
    public String toString() {
        return String.format("Arc(gap=%g m, V=%g, %s)", gapMetres, arcVoltage(),
                struck ? "STRUCK" : "open");
    }
}
