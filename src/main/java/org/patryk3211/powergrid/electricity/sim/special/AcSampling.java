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
        var ceiling = Math.max(maxSubTicks, 1);
        if(!(frequency > 0))
            return 1;

        var needed = samplesPerCycle * frequency * TICK_SECONDS;
        var rate = 1;
        while(rate < needed && rate < ceiling)
            rate <<= 1;
        return Math.min(rate, ceiling);
    }
}
