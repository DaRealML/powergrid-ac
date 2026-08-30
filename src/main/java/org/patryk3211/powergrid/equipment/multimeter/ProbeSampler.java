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
package org.patryk3211.powergrid.equipment.multimeter;

import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.IMultiHooks;

/**
 * Records one probe's value at every solver sub-tick.
 * <p>
 * The multimeter otherwise reads a value once per client tick, at 20 Hz, because that is all
 * that reaches the client. This rides the solver's own sub-tick loop instead, so an island being
 * stepped sixteen times per tick yields sixteen samples — enough to draw the waveform rather than
 * an alias of it.
 *
 * <h2>Registered for one tick at a time</h2>
 * This is not a node or a wire, so it is not part of the network's structure and is never carried
 * across a merge or a split. Whatever owns it re-registers it every world tick, before
 * {@code prepare()}. That is what makes it safe: islands merge, split and rebuild constantly as
 * players edit the grid, and an observer that had to be migrated through all of those paths would
 * be a standing source of stale references.
 */
public class ProbeSampler implements IMultiHooks {
    /** Voltage probe: the difference between two node potentials. */
    @Nullable
    private final IElectricNode positive;
    @Nullable
    private final IElectricNode negative;

    /** Current probe: the signed current through one wire. */
    @Nullable
    private final AbstractElectricWire wire;

    private float[] samples = new float[0];
    private int count;

    private ProbeSampler(@Nullable IElectricNode positive, @Nullable IElectricNode negative,
                         @Nullable AbstractElectricWire wire) {
        this.positive = positive;
        this.negative = negative;
        this.wire = wire;
    }

    public static ProbeSampler voltage(IElectricNode positive, IElectricNode negative) {
        return new ProbeSampler(positive, negative, null);
    }

    public static ProbeSampler current(AbstractElectricWire wire) {
        return new ProbeSampler(null, null, wire);
    }

    @Override
    public void prepare(int multiTicks) {
        if(samples.length != multiTicks)
            samples = new float[multiTicks];
        count = 0;
    }

    @Override
    public void postMicroTick() {
        if(count >= samples.length)
            return;
        samples[count++] = (float) read();
    }

    private double read() {
        if(wire != null) {
            // Deliberately the signed current, not BaseWireEntity.measuredCurrent(), which takes
            // an absolute value — sampling that per sub-tick would fold the negative half of an
            // alternating waveform upwards and draw a full-wave-rectified trace.
            return wire.current();
        }
        if(positive == null || negative == null)
            return 0;
        return positive.getVoltage() - negative.getVoltage();
    }

    /** Number of samples captured this tick; zero on an island that is not sub-stepping. */
    public int size() {
        return count;
    }

    public float sample(int index) {
        return samples[index];
    }

    /**
     * Copy the captured samples, decimated to at most {@code limit} entries.
     * <p>
     * Decimated rather than truncated: taking the first {@code limit} samples would show only
     * the head of each world tick and leave a discontinuity at every tick boundary.
     * <p>
     * The index is scaled proportionally rather than advanced by a fixed stride. A stride of
     * {@code count / limit} only spans the tick when the limit divides the count; otherwise it
     * runs out part-way through and the tail of every tick goes unsampled, putting a step at
     * each tick boundary that reads as noise on the graph. Sub-tick counts are always powers of
     * two but {@code limit} is a config value and need not be, so the stride form was a trap
     * waiting for anyone who set it to, say, 10.
     */
    public float[] snapshot(int limit) {
        if(count == 0 || limit <= 0)
            return new float[0];
        var length = Math.min(limit, count);
        var out = new float[length];
        for(int i = 0; i < length; ++i)
            out[i] = samples[i * count / length];
        return out;
    }
}
