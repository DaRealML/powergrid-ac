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
package org.patryk3211.powergrid.electricity.sim.schedule;

import java.util.Arrays;

/**
 * The set of sub-tick rates an island may run at, and the rules for moving between them.
 * <p>
 * Every change of rate re-derives the conductance of every capacitor and inductor in the island
 * and dirties its matrix, so the rate must not follow a slowly moving demand step by step. A
 * ladder of allowed values does the quantising, and {@link #settle} adds the hysteresis: a rate
 * rises the moment the demand outgrows it but falls only once the demand is clearly below the
 * rung underneath, so a machine hovering at a rung boundary does not flap between two rates.
 *
 * <h2>Why the rungs are not only powers of two</h2>
 * The old rule rounded every demand up to a power of two. A 50 Hz machine at 32 samples per cycle
 * asks for 80 and got 128, so 37% of its solves were rounding. The cost of a linear island is
 * exactly proportional to its rate (measured in docs/perf/scheduling.md), so that rounding was
 * paid in full. A ladder with four rungs per octave rounds up by at most 25%.
 * <p>
 * The power-of-two constraint existed so that the interleaved stepping schedule of
 * {@code WorldNetworks.preTick} spaces an island's steps evenly. Islands never exchange state
 * with each other except through transmission lines (which force both ends to one rate), and each
 * island counts its own time by summing its own {@code dt}, so the spacing of one island's steps
 * against another's is only ever seen by a probe that reads across islands. See the class comment
 * of {@link SubTickScheduler} for what that costs.
 */
public final class RateLadder {
    /** A demand within this fraction of the rung below is not enough to step down to it. */
    public static final double DOWN_MARGIN = 0.9;

    private final int[] levels;

    private RateLadder(int[] levels) {
        this.levels = levels;
    }

    /**
     * Build a ladder.
     *
     * @param perOctave rungs per doubling: 1 (powers of two only, the original rule), 2 or 4
     * @param ceiling   highest rate allowed; with one rung per octave the largest power of two not
     *                  above it, otherwise the ceiling itself is the top rung
     */
    public static RateLadder of(int perOctave, int ceiling) {
        var top = Math.max(ceiling, 1);
        // Mantissas in quarters of an octave base: 4 is 1.0, 5 is 1.25, 6 is 1.5, 7 is 1.75.
        // A rung is mantissa * 2^e / 4, kept only when that is a whole number.
        int[] mantissas = switch(perOctave) {
            case 1 -> new int[]{ 4 };
            case 2 -> new int[]{ 4, 6 };
            case 4 -> new int[]{ 4, 5, 6, 7 };
            default -> throw new IllegalArgumentException("rungs per octave must be 1, 2 or 4, not " + perOctave);
        };
        if(perOctave == 1)
            top = Integer.highestOneBit(top);
        var set = new java.util.TreeSet<Integer>();
        for(int e = 0; e < 30; ++e) {
            for(var m : mantissas) {
                var scaled = (long) m << e;
                if(scaled % 4 != 0)
                    continue;
                var value = scaled / 4;
                if(value >= 1 && value <= top)
                    set.add((int) value);
            }
        }
        set.add(top);
        return new RateLadder(set.stream().mapToInt(Integer::intValue).toArray());
    }

    public int top() {
        return levels[levels.length - 1];
    }

    public int[] levels() {
        return levels.clone();
    }

    /** Smallest rung at or above {@code needed}; the top rung when nothing is high enough. */
    public int roundUp(int needed) {
        for(var level : levels) {
            if(level >= needed)
                return level;
        }
        return top();
    }

    /** Largest rung strictly below {@code rate}, or 1 when there is none. */
    public int below(int rate) {
        var found = 1;
        for(var level : levels) {
            if(level >= rate)
                break;
            found = level;
        }
        return found;
    }

    /** Smallest rung strictly above {@code rate}, or {@code rate} itself at the top. */
    public int above(int rate) {
        for(var level : levels) {
            if(level > rate)
                return level;
        }
        return Math.max(rate, top());
    }

    /**
     * The rate to run at given the demand this tick and the rate run last tick.
     * <p>
     * Rising is immediate, because a rate below the demand samples the waveform too coarsely.
     * Falling waits until the demand is at most {@link #DOWN_MARGIN} of the rung underneath the
     * present rate (one whole step below it on the small rungs); the demand then rounds up on its
     * own merits, which may still be above that lower rung.
     *
     * @param previous rate last tick, or zero for an island seen for the first time
     * @param demand   unrounded number of sub-ticks the island's elements ask for, at least 1
     */
    public int settle(int previous, int demand) {
        var wanted = roundUp(demand);
        // A previous rate above the ceiling (the ceiling was lowered) has nothing to hold.
        if(previous <= 0 || wanted >= previous || previous > top())
            return wanted;
        var lower = below(previous);
        // On the small rungs 10% is less than one sub-tick, so the gap there is one whole step. A
        // demand of 1 means nothing wants sub-stepping at all and always drops straight down.
        var threshold = lower <= 10 ? lower - 1 : (int) Math.floor(lower * DOWN_MARGIN);
        if(demand <= threshold || demand <= 1)
            return wanted;
        return previous;
    }

    @Override
    public String toString() {
        return Arrays.toString(levels);
    }
}
