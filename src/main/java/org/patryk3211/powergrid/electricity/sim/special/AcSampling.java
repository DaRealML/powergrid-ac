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

/**
 * Shared arithmetic for alternating sources: angle wrapping and choosing a sub-tick rate.
 * <p>
 * Both the alternator and the bench AC source need exactly this, and getting the sub-tick rule
 * subtly different between two sources on the same grid would be an unpleasant bug to chase.
 */
public final class AcSampling {
    public static final double TWO_PI = Math.PI * 2;

    /** One world tick, in seconds. The solver's timestep is this divided by the sub-tick count. */
    public static final double TICK_SECONDS = 0.05;

    private AcSampling() { }

    /**
     * Wrap an angle into {@code [0, 2*pi)}.
     * <p>
     * A true modulo rather than a remainder, so a negative angle — a shaft turning backwards —
     * comes back positive instead of staying negative.
     */
    public static double wrapAngle(double angle) {
        var wrapped = angle % TWO_PI;
        return wrapped < 0 ? wrapped + TWO_PI : wrapped;
    }

    /**
     * Sub-ticks per world tick needed to resolve a waveform at the given frequency.
     * <p>
     * Rounded up to a power of two so every rate divides the world tick evenly and the rate only
     * changes at octave boundaries — changing it re-derives every capacitor and inductor
     * conductance and dirties the matrix, so it should not chatter with small speed changes.
     *
     * @param frequency       electrical frequency in Hz; zero or negative asks for nothing
     * @param samplesPerCycle target samples across one cycle
     * @param maxSubTicks     hard ceiling, the real cost limit for AC
     */
    public static int subTicksFor(double frequency, int samplesPerCycle, int maxSubTicks) {
        // Floor the ceiling to a power of two as well as the rate. The stepping schedule in
        // WorldNetworks advances an island whenever (i+1)*n/max crosses an integer, which gives
        // exactly n steps for any n — but evenly spaced ones only when n divides max. A rate
        // that does not, say a configured ceiling of 100, would space its sub-ticks unevenly
        // while every companion model still assumes a fixed dt. A configured 100 therefore
        // behaves as 64 rather than silently producing a non-uniform timestep.
        var ceiling = Integer.highestOneBit(Math.max(maxSubTicks, 1));
        if(!(frequency > 0))
            return 1;

        var needed = samplesPerCycle * frequency * TICK_SECONDS;
        var rate = 1;
        while(rate < needed && rate < ceiling)
            rate <<= 1;
        return Math.min(rate, ceiling);
    }

    /**
     * Phase of a sine at {@code frequency} referenced to the world's game time, in {@code [0, 2*pi)}.
     * <p>
     * The fractional cycle count is taken in two parts. Game time grows without bound -- a year of
     * play is over six hundred million ticks -- and at mains frequency {@code f * t} is then a
     * number in the hundreds of millions; folding the tick part to its fraction first keeps the
     * sub-tick part from being added to a value that has already spent most of a double's digits.
     *
     * @param tick    game time of the tick being solved
     * @param elapsed seconds from the start of that tick, from a {@link TickTimer}
     */
    public static double anchoredPhase(double frequency, long tick, double elapsed) {
        var whole = frequency * TICK_SECONDS * tick;
        var cycles = (whole - Math.floor(whole)) + frequency * elapsed;
        return TWO_PI * (cycles - Math.floor(cycles));
    }

    /**
     * Time into the current world tick, for a component whose angle is anchored to a clock it
     * does not own.
     *
     * <h2>Why an anchor rather than an accumulator</h2>
     * Two sources that each integrate their own angle agree on frequency but not on phase: each
     * starts from wherever it was built, and a circuit rebuild starts it again from zero. That is
     * fine for one machine on its own and useless for three windings that must sit 120 degrees
     * apart. Anchoring every component to one shared value per world tick -- a shaft angle, or the
     * world's game time -- makes the phase relationship a property of the settings rather than of
     * build order.
     *
     * <h2>Why the anchor is per world tick and the fraction is per component</h2>
     * Islands step at different sub-tick rates, so two components anchored to the same clock do
     * not sample at the same instants. Each therefore measures its own position inside the tick
     * by summing its own timesteps, and resets that sum when the anchor's tick counter moves. Both
     * then read the true angle at their own sample times, which is exactly what is wanted: the
     * windings agree at every instant, not merely at the tick boundaries.
     * <p>
     * If the anchor fails to advance for a tick -- a frozen tick, a rotor in a chunk that did not
     * tick -- the elapsed time simply keeps growing and the angle extrapolates at the last known
     * speed, which is what an integrator would have done anyway.
     */
    public static final class TickTimer {
        private long tick = Long.MIN_VALUE;
        private double elapsed;

        /**
         * Advance by one sub-tick.
         *
         * @param currentTick the anchor's counter; any change restarts the count
         * @param dt          this sub-tick's timestep in seconds
         * @return seconds from the start of the anchor's tick to the end of this sub-tick
         */
        public double step(long currentTick, double dt) {
            if(currentTick != tick) {
                tick = currentTick;
                elapsed = 0;
            }
            elapsed += dt;
            return elapsed;
        }
    }
}
