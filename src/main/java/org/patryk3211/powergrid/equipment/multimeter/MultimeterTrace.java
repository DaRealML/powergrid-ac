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
 * <h2>Two sample sources</h2>
 * With the graph screen closed, or with the sub-tick stream disabled in the config, the client
 * reads each channel once per client tick — <b>20 Hz</b>, the rate at which the underlying value
 * reaches the client at all. That is faithful for direct current and for an alternator at the
 * default single pole pair (about 4.5 Hz at a shaft's speed ceiling), and <b>aliases</b> above
 * roughly 10 Hz: it will draw a believable waveform at the wrong frequency.
 * <p>
 * With the graph open the server streams what the solver actually computed inside each tick, so
 * an island stepped sixteen times yields sixteen samples and the waveform is drawn as it really
 * is. The two never interleave: {@link #acceptSubTickSamples} takes over and the 20 Hz path
 * stands down, because splicing them would put two different time bases in one buffer.
 * <p>
 * Channels can legitimately arrive at different rates in the same packet, since two probes may
 * sit on islands the solver steps at different rates. They are resampled onto the fastest of
 * them so that one horizontal position means one instant for every channel — a slow channel
 * therefore draws as a staircase, which is an honest picture of how much it actually knows.
 */
public class MultimeterTrace {
    /**
     * Ring capacity, in samples per channel.
     * <p>
     * Generous, because the sample rate is not fixed. At a solver rate of 2560 Hz the old
     * 200-slot ring held 78 milliseconds, so a channel the server could only sample once per
     * world tick had room for one or two distinct values across the entire plot and drew as a
     * single step -- which reads as a broken probe rather than as a coarse one. Four thousand
     * floats per channel is 64 kB for a full meter, which is nothing.
     */
    public static final int CAPACITY = 4096;

    /**
     * How much history the plot can show, whatever the sample rate.
     * <p>
     * A fixed sample count means the time axis silently rescales by two orders of magnitude when
     * the solver starts sub-stepping. A fixed window keeps the horizontal axis meaning the same
     * thing, and the ring only bounds it at the very highest rates.
     */
    public static final float MAX_WINDOW_SECONDS = 2f;

    /** Shortest window worth offering: one 50 ms world tick, give or take. */
    public static final float MIN_WINDOW_SECONDS = 0.02f;

    /**
     * Requested display window in seconds, or zero for automatic.
     * <p>
     * A fixed two-second window is unreadable as soon as the signal is fast. At 2560 Hz it holds
     * 4096 samples, and a 47 Hz waveform then occupies about four pixels per cycle across
     * seventy-five cycles — which no rendering technique can make legible, because the
     * information simply is not there at that scale. Every oscilloscope has a timebase control
     * for exactly this reason, and this is it.
     */
    private static float windowRequest;

    /** Window chosen automatically from the measured frequency, when the request is automatic. */
    private static float autoWindow = MAX_WINDOW_SECONDS;

    public static boolean isAutoWindow() {
        return windowRequest <= 0;
    }

    public static float windowRequest() {
        return windowRequest;
    }

    /** Zero selects automatic; anything else is taken literally, within the ring's reach. */
    public static void setWindowRequest(float seconds) {
        windowRequest = seconds <= 0 ? 0
                : Math.min(Math.max(seconds, MIN_WINDOW_SECONDS), MAX_WINDOW_SECONDS);
    }

    /**
     * Fewest samples the automatic timebase will leave on the plot.
     * <p>
     * Shrinking the window only helps when there are samples to spare. On the 20 Hz fallback a
     * 47 Hz signal is aliased beyond recovery anyway, and a window sized to eight of its apparent
     * cycles would hold three samples — three points stretched across three hundred pixels, which
     * is worse than the long window it replaced. Below this floor the timebase stops shrinking and
     * the trace shows the envelope instead, which is all that rate can honestly support.
     */
    private static final int MIN_PLOT_SAMPLES = 32;

    /** Set from the measured frequency each frame while the timebase is automatic. */
    public static void setAutoWindow(float seconds) {
        var floor = Math.max(MIN_WINDOW_SECONDS, MIN_PLOT_SAMPLES / (float) sampleRate());
        autoWindow = Math.min(Math.max(seconds, floor), MAX_WINDOW_SECONDS);
    }

    /** The window actually in force. */
    public static float effectiveWindowSeconds() {
        return isAutoWindow() ? autoWindow : windowRequest;
    }

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

    /** Samples making up the target window at the present rate, bounded by the ring. */
    public static int targetSamples() {
        return Math.max(2, Math.min(CAPACITY, Math.round(effectiveWindowSeconds() * sampleRate())));
    }

    /**
     * The longest slice the ring can offer, for measurement rather than for drawing.
     * <p>
     * Frequency and phase want as many cycles as possible; the plot wants few enough to see. Tying
     * both to one window means that shortening the timebase to read a waveform also degrades the
     * numbers printed under it, and at the shortest settings leaves fewer than two zero crossings,
     * at which point {@code estimateFrequency} gives up entirely. So the phasor maths keeps its
     * own window and only the drawing follows the timebase.
     */
    public static float[] analysisArray(int channel) {
        var want = Math.min(filled[channel], Math.min(CAPACITY,
                Math.round(MAX_WINDOW_SECONDS * sampleRate())));
        var start = filled[channel] - want;
        var out = new float[want];
        for(int i = 0; i < want; ++i)
            out[i] = get(channel, start + i);
        return out;
    }

    /** Samples of this channel actually on screen: the target window, or all there is so far. */
    public static int visibleCount(int channel) {
        return Math.min(filled[channel], targetSamples());
    }

    /** Seconds of history the plot is showing. */
    public static float windowSeconds() {
        var rate = sampleRate();
        if(rate <= 0)
            return effectiveWindowSeconds();
        return targetSamples() / (float) rate;
    }

    /** Samples per world tick most recently received, for the time axis. */
    private static int subTickRate = 1;

    public static boolean receivingSubTicks() {
        return ticksSinceSubTick <= GRACE_TICKS;
    }

    /**
     * Frozen: no new samples are appended from either source and the picture holds still.
     * <p>
     * A waveform scrolling past at 2560 Hz cannot be read, and the numbers under it change every
     * frame. Freezing is what makes a transient examinable at all, which is why every real scope
     * has the button.
     */
    private static boolean paused;

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean value) {
        if(paused == value)
            return;
        paused = value;
        if(!value) {
            // Resuming would otherwise splice the frozen history straight onto live samples with
            // however many seconds of world time missing in between, and the axis would be a
            // lie across the join. The channels are kept; only the stale history goes.
            clear0();
        }
    }

    /**
     * Samples the most recent packet carried for each channel, so a channel the server could
     * only sample once per tick is visibly identified as such rather than just looking wrong.
     */
    private static final int[] channelSamples = new int[MultimeterChannel.MAX_CHANNELS];

    /** Effective sample rate of one channel in Hz, which may be below the headline rate. */
    public static int channelRate(int channel) {
        return 20 * Math.max(channelSamples[channel], 1);
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
    public static void acceptSubTickSamples(float[][] perChannel, float[] live) {
        if(paused)
            return;
        if(perChannel.length == 0)
            return;

        if(channelCount == 0)
            return;

        // The server built this payload from ITS copy of the channel list. For about a round
        // trip after probing, the two disagree -- the client adds a channel optimistically and
        // the server confirms it a tick later -- and mapping payload index i onto channel i
        // across that gap writes one probe's samples into another probe's trace. That is
        // indistinguishable from a wiring mistake in-world, so the packet is dropped instead.
        // The cost is one tick of history; the fallback is the 20 Hz path, which is the safe
        // direction to fail in.
        if(perChannel.length != channelCount)
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

        // Two different time bases in one buffer make the axis wrong for the older half of it.
        // That happens on a change of SOURCE, and equally on a change of RATE while the stream
        // continues: an alternator spinning up walks its sub-tick count 1, 2, 4 ... 128, and at
        // each step the ring still holds the older half at the previous rate while
        // targetSamples() and windowSeconds() are recomputed entirely from the new one. The plot
        // then snaps horizontally and every phasor is handed one sampleRate for a window that is
        // mostly stale-rate samples, so frequency, phase, Z and SWR are all wrong until it
        // refills. Guarding only the source transition left that case in.
        if(!receivingSubTicks() || common != subTickRate)
            clear0();

        ticksSinceSubTick = 0;
        subTickRate = common;

        for(int c = 0; c < channelCount; ++c) {
            var samples = perChannel[c];
            channelSamples[c] = samples.length;
            for(int i = 0; i < common; ++i) {
                float value;
                if(samples.length == 0) {
                    // Nothing captured: the probe's target could not be resolved server-side
                    // this tick. Use the client's own once-per-tick reading rather than holding
                    // the last value. Holding froze the trace permanently, because the 20 Hz
                    // path below stands down for EVERY channel as soon as ANY channel starts
                    // streaming -- so a channel that fell back to "hold" had nothing left to
                    // update it, and sat at whatever it happened to contain (zero, after the
                    // buffer reset that a change of source performs).
                    value = c < live.length ? live[c] : latest(c);
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
            // Or the header prints a stale "2560 Hz" over an empty plot for the frame or two
            // before the next packet lands.
            channelSamples[c] = 0;
        }
        subTickRate = 1;
    }


    public static void clear() {
        // Both append paths already stand down while frozen; this one did not, so a stack
        // leaving the main hand mid-freeze wiped the picture the freeze existed to hold, and
        // nothing repopulated it because sample() was standing down too.
        if(paused)
            return;
        for(int c = 0; c < MultimeterChannel.MAX_CHANNELS; ++c) {
            head[c] = 0;
            filled[c] = 0;
            currentChannel[c] = false;
            channelSamples[c] = 0;
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
        if(channelCount == 0)
            return true;
        // Any channel with history is enough to draw. Testing filled[0] alone blanked the whole
        // screen whenever the first channel happened to be the one that could not be resolved.
        for(int c = 0; c < channelCount; ++c)
            if(filled[c] > 0)
                return false;
        return true;
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
        if(paused)
            return;
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
            channelSamples[c] = 1;
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

    /**
     * Index of the oldest sample still on screen.
     * <p>
     * Every statistic below is taken over the visible window rather than the whole ring, so the
     * numbers under the plot describe the picture above them. It also keeps the auto-scale
     * responsive: a startup transient scrolls out of the window and stops squashing the
     * steady-state waveform, instead of dominating the scale until the meter is put away.
     */
    private static int windowStart(int channel) {
        return filled[channel] - visibleCount(channel);
    }

    public static float minimum(int channel) {
        if(filled[channel] == 0)
            return 0;
        var min = Float.POSITIVE_INFINITY;
        for(int i = windowStart(channel); i < filled[channel]; ++i)
            min = Math.min(min, get(channel, i));
        return min;
    }

    public static float maximum(int channel) {
        if(filled[channel] == 0)
            return 0;
        var max = Float.NEGATIVE_INFINITY;
        for(int i = windowStart(channel); i < filled[channel]; ++i)
            max = Math.max(max, get(channel, i));
        return max;
    }

    /**
     * Largest magnitude in the window, regardless of sign — not {@link #maximum(int)}, which
     * with reversed probe leads on DC would report the reading closest to zero as the peak.
     */
    public static float peak(int channel) {
        // One pass. This is called for every channel by every channel's shared-scale lookup, so
        // running minimum() and maximum() as two separate full sweeps of a 4096-sample window
        // was quadratic in the channel count for no reason.
        if(filled[channel] == 0)
            return 0;
        var peak = 0f;
        for(int i = windowStart(channel); i < filled[channel]; ++i)
            peak = Math.max(peak, Math.abs(get(channel, i)));
        return peak;
    }

    /**
     * Smallest full-scale value a channel is ever drawn against, by unit.
     * <p>
     * Pure auto-scaling has a failure mode that reads as a hardware fault: a channel sitting at
     * essentially zero — an open probe, a branch carrying no current, a solver residual of a
     * few microamps — gets normalised to its own noise and fills the plot with a jagged mess
     * that looks like a real signal. A real scope has fixed volts-per-division for the same
     * reason. Below the floor the trace collapses towards the zero line, which is the truth.
     */
    private static final float FLOOR_VOLTS = 0.05f;
    private static final float FLOOR_AMPS = 0.01f;

    /** Full-scale value this channel should be plotted against, floored out of the noise. */
    public static float displayScale(int channel) {
        var floor = currentChannel[channel] ? FLOOR_AMPS : FLOOR_VOLTS;
        return Math.max(peak(channel) * 1.1f, floor);
    }

    /**
     * Full-scale value shared by every channel measuring the same quantity as this one.
     * <p>
     * Scaling each channel to its own peak makes every trace fill its lane, so two voltages an
     * order of magnitude apart draw as the same height and the display actively misleads about
     * amplitude. Sharing one scale across all the voltage channels, and another across all the
     * current ones, restores the comparison. It is still per-unit rather than global, because
     * volts and amps have no common axis and putting a 200 V trace and a 2 A one on one scale
     * flattens the current onto the zero line.
     */
    public static float sharedScale(int channel) {
        return sharedScaleForUnit(currentChannel[channel]);
    }

    /**
     * The shared scale for one unit, so a caller drawing every channel can compute the two scales
     * once instead of once per channel — the difference between O(channels) and O(channels²)
     * sweeps of the window.
     */
    public static float sharedScaleForUnit(boolean wantCurrent) {
        var peak = 0f;
        for(int c = 0; c < channelCount; ++c)
            if(currentChannel[c] == wantCurrent)
                peak = Math.max(peak, peak(c));
        var floor = wantCurrent ? FLOOR_AMPS : FLOOR_VOLTS;
        return Math.max(peak * 1.1f, floor);
    }

    /**
     * Root mean square across the window.
     * <p>
     * For a steady reading this is its magnitude. For an alternating one it is what a real meter
     * displays and what determines heating in a load, which is why it sits beside the
     * instantaneous value rather than replacing it.
     */
    public static float rms(int channel) {
        var count = visibleCount(channel);
        if(count == 0)
            return 0;
        var sum = 0.0;
        for(int i = windowStart(channel); i < filled[channel]; ++i) {
            var v = get(channel, i);
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum / count);
    }

    /** The visible window's samples as a plain array, oldest first, for analysis that is pure maths. */
    public static float[] toArray(int channel) {
        var count = visibleCount(channel);
        var start = windowStart(channel);
        var out = new float[count];
        for(int i = 0; i < count; ++i)
            out[i] = get(channel, start + i);
        return out;
    }

    /** One sample of the visible window, index 0 being its oldest. */
    public static float visible(int channel, int index) {
        return get(channel, windowStart(channel) + index);
    }

    /** Largest magnitude across every channel, so they can share one vertical scale. */
    public static float peakAcrossChannels() {
        var peak = 0f;
        for(int c = 0; c < channelCount; ++c)
            peak = Math.max(peak, peak(c));
        return peak;
    }
}
