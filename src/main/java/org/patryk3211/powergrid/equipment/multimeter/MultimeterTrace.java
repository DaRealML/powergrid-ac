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

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Rolling history of what the multimeter is reading, kept on the client so the graph screen has
 * something to draw.
 * <p>
 * This lives entirely client-side and is never saved or sent anywhere. It can afford to, because
 * node voltages are already synchronised to tracking clients every tick — which is the same
 * reason the needle on the item model works — so
 * {@link MultimeterItem#getMeasurement(Level, ItemStack)} returns a real value on the client with
 * no extra networking.
 *
 * <h2>Sample rate, and what it can and cannot show</h2>
 * One sample per client tick, so <b>20 Hz</b>. That is the rate at which the underlying value
 * reaches the client at all; the solver may be stepping an AC island 8 or 16 times per tick
 * internally, but only the end-of-tick state is synchronised.
 * <p>
 * For direct current, and for an alternator at the default single pole pair — about 4.5 Hz at a
 * shaft's 272 rpm ceiling — 20 Hz is comfortably above the Nyquist limit and the trace is a
 * faithful, if coarse, picture of the waveform. Raising pole pairs pushes the electrical
 * frequency up until it crosses 10 Hz, beyond which <b>this graph will alias</b> and show a
 * believable waveform at the wrong frequency. Seeing the true sub-tick waveform needs the
 * dedicated sampling hardware — the plotter or the CRT — which sits inside the circuit and
 * records every solver sub-tick.
 */
public class MultimeterTrace {
    /** Ten seconds at one sample per client tick. */
    public static final int CAPACITY = 200;

    /** Seconds of history the buffer holds when full. */
    public static final float WINDOW_SECONDS = CAPACITY / 20f;

    private static final float[] samples = new float[CAPACITY];

    /** Index the next sample will be written to. */
    private static int head;

    /** How many entries are valid, up to {@link #CAPACITY}. */
    private static int filled;

    /** Mode of the stack being traced: 0 voltage, 1 current, -1 none. */
    private static int mode = -1;

    /** Identity of the probed target, so re-probing elsewhere starts a fresh trace. */
    private static int target;

    public static void clear() {
        head = 0;
        filled = 0;
        mode = -1;
        target = 0;
    }

    public static int getMode() {
        return mode;
    }

    public static int size() {
        return filled;
    }

    public static boolean isEmpty() {
        return filled == 0;
    }

    /**
     * Samples in chronological order, oldest first.
     *
     * @param index 0 is the oldest retained sample, {@code size() - 1} the newest
     */
    public static float get(int index) {
        // Once the buffer has wrapped, head is the oldest entry; before that it is the count.
        var start = filled < CAPACITY ? 0 : head;
        return samples[(start + index) % CAPACITY];
    }

    /** Record one reading, resetting the history if the probe moved to a different target. */
    public static void sample(Level level, ItemStack stack, MultimeterItem multimeter) {
        var stackMode = multimeter.getMode(stack);
        if(stackMode < 0) {
            clear();
            return;
        }

        var stackTarget = MultimeterItem.getModeData(stack).hashCode();
        if(stackMode != mode || stackTarget != target) {
            clear();
            mode = stackMode;
            target = stackTarget;
        }

        var value = multimeter.getMeasurement(level, stack);
        if(!Float.isFinite(value))
            return;

        samples[head] = value;
        head = (head + 1) % CAPACITY;
        if(filled < CAPACITY)
            ++filled;
    }

    /** Most recent reading, or zero if nothing has been recorded. */
    public static float latest() {
        if(filled == 0)
            return 0;
        return samples[(head - 1 + CAPACITY) % CAPACITY];
    }

    public static float minimum() {
        var min = Float.POSITIVE_INFINITY;
        for(int i = 0; i < filled; ++i)
            min = Math.min(min, get(i));
        return filled == 0 ? 0 : min;
    }

    public static float maximum() {
        var max = Float.NEGATIVE_INFINITY;
        for(int i = 0; i < filled; ++i)
            max = Math.max(max, get(i));
        return filled == 0 ? 0 : max;
    }

    public static float mean() {
        if(filled == 0)
            return 0;
        var sum = 0.0;
        for(int i = 0; i < filled; ++i)
            sum += get(i);
        return (float) (sum / filled);
    }

    /**
     * Root mean square across the window.
     * <p>
     * For a steady direct measurement this equals the magnitude of the reading. For an
     * alternating one it is the value a real meter would display, and the number that actually
     * determines heating in a load — which is why it is worth showing next to the instantaneous
     * value rather than instead of it.
     */
    public static float rms() {
        if(filled == 0)
            return 0;
        var sum = 0.0;
        for(int i = 0; i < filled; ++i) {
            var v = get(i);
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum / filled);
    }
}
