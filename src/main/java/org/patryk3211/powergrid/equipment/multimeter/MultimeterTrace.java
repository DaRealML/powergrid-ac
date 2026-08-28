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

import java.lang.ref.WeakReference;

/**
 * Rolling history of every channel the multimeter is watching, kept on the client so the graph
 * screen has something to draw.
 * <p>
 * Entirely client-side and never saved or sent anywhere. It can afford to be, because node
 * voltages are already synchronised to tracking clients every tick — the same reason the needle
 * on the item model works — so {@link MultimeterChannel#measure(Level)} returns a real value on
 * the client with no extra networking.
 *
 * <h2>Sample rate</h2>
 * One sample per client tick, so <b>20 Hz</b> — the rate at which the underlying value reaches
 * the client at all. The solver may step an AC island 8 or more times per tick internally, but
 * only the end-of-tick state is synchronised.
 * <p>
 * For direct current, and for an alternator at the default single pole pair (about 4.5 Hz at a
 * shaft's speed ceiling), that is comfortably above the Nyquist limit and the trace is faithful.
 * Above roughly 10 Hz <b>this graph aliases</b> and will show a believable waveform at the wrong
 * frequency; the in-circuit plotter and CRT record every solver sub-tick and are the instruments
 * for that.
 */
public class MultimeterTrace {
    /** Ten seconds at one sample per client tick. */
    public static final int CAPACITY = 200;

    /** Seconds of history a full buffer holds. */
    public static final float WINDOW_SECONDS = CAPACITY / 20f;

    /** Distinct, colour-blind-friendly trace colours, in channel order. */
    public static final int[] CHANNEL_COLOURS = {
            0xFF46D8A0,  // green
            0xFF5AA9E6,  // blue
            0xFFE8B84B,  // amber
            0xFFE0685A,  // red
    };

    private static final float[][] samples =
            new float[MultimeterChannel.MAX_CHANNELS][CAPACITY];

    /** Per-channel write cursor and fill count. */
    private static final int[] head = new int[MultimeterChannel.MAX_CHANNELS];
    private static final int[] filled = new int[MultimeterChannel.MAX_CHANNELS];

    /** Whether each channel reads current rather than voltage, for unit formatting. */
    private static final boolean[] currentChannel = new boolean[MultimeterChannel.MAX_CHANNELS];

    /** How many channels the meter is presently watching. */
    private static int channelCount;

    /** Identity of the probe set, so re-probing starts fresh rather than splicing. */
    private static int signature;

    /** The world these samples came from; weak so a trace can never keep a level alive. */
    private static WeakReference<Level> origin = new WeakReference<>(null);

    /**
     * Ticks since sub-tick samples last arrived from the server.
     * <p>
     * While they are arriving the once-per-client-tick path stands down, so the two do not
     * interleave and produce a trace at two different time bases. A short grace period covers a
     * dropped or late packet without flickering between the two sources.
     */
    private static final int GRACE_TICKS = 5;

    private static int ticksSinceSubTick = GRACE_TICKS + 1;

    /** Seconds of history held when the server is streaming {@code n} samples per world tick. */
    public static float windowSeconds() {
        if(!receivingSubTicks())
            return WINDOW_SECONDS;
        var perTick = Math.max(subTickRate, 1);
        return CAPACITY / (20f * perTick);
    }

    /** Samples per world tick most recently received, for the time axis. */
    private static int subTickRate = 1;

    public static boolean receivingSubTicks() {
        return ticksSinceSubTick <= GRACE_TICKS;
    }

    /** Effective sample rate in Hz — twenty world ticks a second times the samples in each. */
    public static int sampleRate() {
        return 20 * Math.max(subTickRate, 1);
    }

    /**
     * Append one world tick of solver-resolution samples, one array per channel.
     * <p>
     * This is the high-resolution path: rather than one reading per client tick, the server sends
     * what the solver actually computed inside the tick, so a waveform that would alias at 20 Hz
     * is drawn as it really is.
     */
    public static void acceptSubTickSamples(float[][] perChannel) {
        if(perChannel.length == 0)
            return;

        // Every channel must advance by the SAME number of samples, or the horizontal axis stops
        // meaning the same instant for each of them. Probes can legitimately return different
        // counts — two islands may be stepped at different rates, and a probe whose target could
        // not be resolved returns none at all — and letting each buffer advance at its own pace
        // makes the traces drift apart over time. Phase alignment between channels is the entire
        // reason to have more than one, so they are resampled onto a common count first.
        var common = 0;
        for(var samples : perChannel)
            common = Math.max(common, samples.length);
        if(common == 0)
            return;

        if(channelCount == 0)
            return;

        // Splicing 20 Hz history in front of solver-resolution samples would put two different
        // time bases in one buffer, and the axis would be wrong for the older half of it.
        if(!receivingSubTicks())
            clear0();

        ticksSinceSubTick = 0;
        subTickRate = common;

        for(int c = 0; c < channelCount; ++c) {
            var samples = c < perChannel.length ? perChannel[c] : new float[0];
            for(int i = 0; i < common; ++i) {
                float value;
                if(samples.length == 0) {
                    // Nothing captured: hold the last known value so this channel still advances
                    // in step rather than freezing while the others scroll past it.
                    value = latest(c);
                } else {
                    // Nearest-neighbour stretch of a coarser channel onto the common time base.
                    value = samples[i * samples.length / common];
                }
                if(!Float.isFinite(value))
                    value = 0;
                MultimeterTrace.samples[c][head[c]] = value;
                head[c] = (head[c] + 1) % CAPACITY;
                if(filled[c] < CAPACITY)
                    ++filled[c];
            }
        }
    }

    /** Empty the sample buffers but keep the channel set, for a change of sample source. */
    private static void clear0() {
        for(int c = 0; c < MultimeterChannel.MAX_CHANNELS; ++c) {
            head[c] = 0;
            filled[c] = 0;
        }
    }

    public static void clear() {
        for(int c = 0; c < MultimeterChannel.MAX_CHANNELS; ++c) {
            head[c] = 0;
            filled[c] = 0;
            currentChannel[c] = false;
        }
        channelCount = 0;
        signature = 0;
        // Reset the source tracking as well, or re-equipping within the grace window leaves
        // receivingSubTicks() true while no packets are arriving and the 20 Hz path stands down
        // against nothing — a visibly frozen trace.
        ticksSinceSubTick = GRACE_TICKS + 1;
        subTickRate = 1;
    }

    public static int channelCount() {
        return channelCount;
    }

    public static boolean isEmpty() {
        return channelCount == 0 || filled[0] == 0;
    }

    public static int size(int channel) {
        return filled[channel];
    }

    public static boolean isCurrent(int channel) {
        return currentChannel[channel];
    }

    public static int colour(int channel) {
        return CHANNEL_COLOURS[channel % CHANNEL_COLOURS.length];
    }

    /**
     * Samples of one channel in chronological order, oldest first.
     *
     * @param index 0 is the oldest retained sample, {@code size(channel) - 1} the newest
     */
    public static float get(int channel, int index) {
        // Before the buffer wraps, head is the count and index 0 is the oldest; after, head is
        // itself the oldest entry.
        var start = filled[channel] < CAPACITY ? 0 : head[channel];
        return samples[channel][(start + index) % CAPACITY];
    }

    /** Record one reading per channel, resetting if the probe set changed. */
    public static void sample(Level level, ItemStack stack, MultimeterItem multimeter) {
        // Rejoining, or moving to another world, leaves the held stack untouched — so without
        // this the old readings would be drawn as continuous with the new ones.
        if(origin.get() != level) {
            clear();
            origin = new WeakReference<>(level);
        }

        var channels = MultimeterItem.getChannels(stack);
        if(channels.isEmpty()) {
            clear();
            return;
        }

        // Only the channel list, not the whole mode data: including the pending "Pos" key meant
        // the first click of a voltage pair, and the later expiry of a stale one, each wiped
        // every channel's history.
        var stackSignature = MultimeterItem.getModeData(stack).get("Channels") == null
                ? 0 : MultimeterItem.getModeData(stack).get("Channels").hashCode();
        if(stackSignature != signature || channels.size() != channelCount) {
            clear();
            signature = stackSignature;
            channelCount = Math.min(channels.size(), MultimeterChannel.MAX_CHANNELS);
            for(int c = 0; c < channelCount; ++c)
                currentChannel[c] = channels.get(c).isCurrent();
        }

        // Stand down while the server is streaming solver-resolution samples, so the two sources
        // never interleave into a trace with two different time bases.
        var wasSubTick = receivingSubTicks();
        ++ticksSinceSubTick;
        if(receivingSubTicks())
            return;
        if(wasSubTick) {
            // Just fell back to the once-per-tick source; drop the finer history rather than
            // continuing the same buffer at a different time base.
            clear0();
        }
        subTickRate = 1;

        for(int c = 0; c < channelCount; ++c) {
            var value = channels.get(c).measure(level);
            // Write and advance even when the reading is unusable. Skipping the write used to
            // skip the head increment too, so one channel fell behind the others and the shared
            // time axis broke — the same defect that was fixed in the sub-tick path.
            if(!Float.isFinite(value))
                value = 0;
            samples[c][head[c]] = value;
            head[c] = (head[c] + 1) % CAPACITY;
            if(filled[c] < CAPACITY)
                ++filled[c];
        }
    }

    public static float latest(int channel) {
        if(filled[channel] == 0)
            return 0;
        return samples[channel][(head[channel] - 1 + CAPACITY) % CAPACITY];
    }

    public static float minimum(int channel) {
        if(filled[channel] == 0)
            return 0;
        var min = Float.POSITIVE_INFINITY;
        for(int i = 0; i < filled[channel]; ++i)
            min = Math.min(min, get(channel, i));
        return min;
    }

    public static float maximum(int channel) {
        if(filled[channel] == 0)
            return 0;
        var max = Float.NEGATIVE_INFINITY;
        for(int i = 0; i < filled[channel]; ++i)
            max = Math.max(max, get(channel, i));
        return max;
    }

    /**
     * Largest magnitude in the window, regardless of sign — not {@link #maximum(int)}, which
     * with reversed probe leads on DC would report the reading closest to zero as the peak.
     */
    public static float peak(int channel) {
        return Math.max(Math.abs(minimum(channel)), Math.abs(maximum(channel)));
    }

    /**
     * Root mean square across the window.
     * <p>
     * For a steady reading this is its magnitude. For an alternating one it is what a real meter
     * displays and what determines heating in a load, which is why it sits beside the
     * instantaneous value rather than replacing it.
     */
    public static float rms(int channel) {
        if(filled[channel] == 0)
            return 0;
        var sum = 0.0;
        for(int i = 0; i < filled[channel]; ++i) {
            var v = get(channel, i);
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum / filled[channel]);
    }

    /** The channel's samples as a plain array, oldest first, for analysis that is pure maths. */
    public static float[] toArray(int channel) {
        var out = new float[filled[channel]];
        for(int i = 0; i < out.length; ++i)
            out[i] = get(channel, i);
        return out;
    }

    /** Largest magnitude across every channel, so they can share one vertical scale. */
    public static float peakAcrossChannels() {
        var peak = 0f;
        for(int c = 0; c < channelCount; ++c)
            peak = Math.max(peak, peak(c));
        return peak;
    }
}
