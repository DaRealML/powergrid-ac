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
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;

/**
 * The current-carrying branch of a wattmeter, which knows its own voltage branch.
 *
 * <h2>Why this has to be a class rather than a multiplication</h2>
 * A wattmeter measures the product of a current in one branch and a voltage in another, and the
 * quantity it displays is the <em>mean</em> of that product over time — real power. Every other
 * averaged quantity in this package is a property of a single branch and can therefore live on
 * {@link AbstractElectricWire}: the mean of {@code i}, the mean of {@code i^2}, the mean of this
 * branch's own {@code v*i}. The mean of a product of two <em>different</em> branches is not a
 * property of either of them, and nothing accumulated it.
 *
 * <p>So the gauges multiplied the two instantaneous readings instead, once per world tick. That is
 * not real power, it is <em>instantaneous</em> power: for a sinusoid it swings between zero and
 * twice the real power at twice the supply frequency, and on a reactive load it goes negative for
 * part of every cycle while energy flows back out of the load. Worse, block entities tick once per
 * world tick and the phase advances by {@code 2*pi*f*0.05} each time, so a frequency dividing
 * 20 Hz evenly samples the same point of the waveform forever — at 20, 40 or 60 Hz that point is a
 * zero crossing and a power gauge read a flat zero however much power was flowing.
 *
 * <p>Neither substitution available on a single branch fixes it. {@code rmsCurrent()} times
 * {@code rmsVoltage()} is <em>apparent</em> power, which is right only on a resistive load and
 * overstates a reactive one by the reciprocal of its power factor. Either branch's own
 * {@code power()} is the power dissipated in that branch, which for a sense shunt is nearly
 * nothing. The product has to be accumulated as it happens, which is what this does.
 *
 * <h2>Draining rather than reading</h2>
 * {@link #drainRealPower()} returns the mean since the last call and starts a new average, so a
 * caller ticking once per world tick collects every sub-tick exactly once whatever rate the island
 * is running at. Reading without draining would either double-count or miss samples depending on
 * how the two rates happened to line up.
 */
public class WattmeterWire extends ElectricWire implements IOuterHook {
    private final AbstractElectricWire sense;

    private double sum;
    private int samples;

    /**
     * @param sense the branch whose voltage is multiplied by this branch's current. Typically the
     *              meter's shunt, sitting across the supply rather than in series with the load.
     */
    public WattmeterWire(double resistance, AbstractElectricWire sense,
                         IElectricNode node1, IElectricNode node2) {
        super(resistance, node1, node2);
        this.sense = sense;
    }

    @Override
    public void postUpperSolve() {
        if(!isConverged())
            return;
        sum += current() * sense.potentialDifference();
        ++samples;
    }

    /**
     * Mean real power since the last call, in watts, and start a new average.
     * <p>
     * Falls back to the instantaneous product when no sub-tick has been recorded yet, which keeps
     * the very first reading after a circuit is built from being zero.
     */
    public double drainRealPower() {
        if(samples == 0)
            return current() * sense.potentialDifference();
        var mean = sum / samples;
        sum = 0;
        samples = 0;
        return mean;
    }

    /** The running mean without disturbing it, for a readout that is drawn more than once a tick. */
    public double realPower() {
        if(samples == 0)
            return current() * sense.potentialDifference();
        return sum / samples;
    }

    @Override
    public String toString() {
        return String.format("WattmeterWire(R=%g)", resistance);
    }
}
