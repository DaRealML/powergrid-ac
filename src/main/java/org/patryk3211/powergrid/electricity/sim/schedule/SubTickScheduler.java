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

import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.ParallelIslandStepping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Chooses the sub-tick rate of every island for a world tick and steps them, on behalf of
 * {@code WorldNetworks.preTick}. Kept out of that class so the whole schedule can be exercised
 * without a Minecraft world, and so the benchmark steps the same code the server does.
 *
 * <h2>What it does, in order</h2>
 * <ol>
 *   <li><b>Wanted rate per island.</b> The original rule ({@link ElectricalNetwork#computeSubTicks},
 *       powers of two) or, with {@link Settings#fineRates}, the unrounded demand of the island's
 *       elements settled onto a {@link RateLadder} with hysteresis.</li>
 *   <li><b>Lockstep groups.</b> Islands joined by a transmission line exchange state every step and
 *       must run at one rate. Those islands are grouped by union-find over the lines, and a group
 *       runs at the highest rate any member wants. An island that needs lockstep but cannot name its
 *       partner falls back to the original rule: it joins the island with the highest demand in the
 *       world. {@link #conservativeLockstep} forces that fallback for every such island, which is the
 *       original behaviour and what the differential tests compare against.</li>
 *   <li><b>Governor.</b> Each group is one unit handed to the {@link SolveGovernor}, which may lower
 *       its rate if the simulation is over its time budget. With no budget nothing is asked of it.</li>
 *   <li><b>Stepping.</b> The interleaved schedule of the original loop, unchanged: an island steps
 *       whenever {@code (i+1)*n/max} crosses an integer.</li>
 * </ol>
 *
 * <h2>Non-power-of-two rates and the interleaved schedule</h2>
 * The schedule crosses a boundary exactly {@code n} times in {@code max} iterations for any
 * {@code n}, but only spaces the crossings evenly when {@code n} divides {@code max}. That spacing
 * matters to nothing an island computes: each companion model takes {@code dt} from
 * {@code getDeltaTime()}, and each time-anchored source sums its own {@code dt} rather than reading
 * the outer iteration. It is visible only to a reader that samples one island while another moves,
 * which is a multimeter probe with one node in each, and there it is a jitter of at most one outer
 * iteration (1/max of a world tick) in when the slower island's value was last updated.
 */
public final class SubTickScheduler {
    /** Everything the scheduler reads from configuration, gathered by the caller once per tick. */
    public static final class Settings {
        /** {@code CSolver.multiTicks}: the floor for every island. */
        public int multiTicks = 1;
        /** {@code CSolver.acMaxSubTicks}. */
        public int ceiling = 64;
        /** Whether rates come from the fine ladder instead of powers of two. */
        public boolean fineRates;
        /** {@code CSolver.acSamplesPerCycle}, the resolution the elements were asked for. */
        public int samplesPerCycle = 32;
        /** The least resolution the governor may reduce an island to, in samples per cycle. */
        public int minSamplesPerCycle = 8;
        /** Wall time per world tick the simulation may take; zero or less disables the governor. */
        public double budgetMs;
    }

    private static final class Info {
        final long id;
        int prevWanted;
        int seen;

        Info(long id) {
            this.id = id;
        }
    }

    private final LongSupplier clock;
    private final SolveGovernor governor;
    private final IdentityHashMap<ElectricalNetwork, Info> infos = new IdentityHashMap<>();
    private final IdentityHashMap<ElectricalNetwork, Integer> index = new IdentityHashMap<>();
    private final List<SolveGovernor.Entry> pool = new ArrayList<>();
    private final List<SolveGovernor.Entry> entries = new ArrayList<>();
    private final List<ElectricalNetwork> partners = new ArrayList<>();

    private int generation;
    private RateLadder ladder;
    private int ladderPerOctave = -1;
    private int ladderCeiling = -1;
    private double budgetApplied = Double.NaN;
    private boolean governing;
    private boolean governorHasState;
    private long totalStart;
    private int maxSubTicks = 1;

    private int[] parent = new int[0];
    private int[] wanted = new int[0];
    private int[] floors = new int[0];
    private int[] unitWanted = new int[0];
    private int[] unitFloor = new int[0];
    private int[] unitNodes = new int[0];
    private int[] unitKey = new int[0];
    private long[] ids = new long[0];
    private SolveGovernor.Entry[] entryOf = new SolveGovernor.Entry[0];
    private long[] prepareNanos = new long[0];
    private long[] stepNanos = new long[0];

    /**
     * Pull every island that needs lockstep up to the fastest rate in the world, as the original
     * code did, instead of grouping by transmission line. For tests that compare against it.
     */
    public boolean conservativeLockstep;

    public SubTickScheduler(LongSupplier clock) {
        this.clock = clock;
        this.governor = new SolveGovernor(clock, RateLadder.of(1, 1), SolveGovernor.Settings.withBudget(0));
    }

    public SubTickScheduler() {
        this(System::nanoTime);
    }

    public SolveGovernor governor() {
        return governor;
    }

    /** Highest rate given to any island by the last {@link #plan}: the length of the outer loop. */
    public int maxSubTicks() {
        return maxSubTicks;
    }

    // ------------------------------------------------------------------ planning

    /** Choose the rate of every island and write it with {@code setSubTicks}. */
    public void plan(List<ElectricalNetwork> islands, Settings s) {
        var n = islands.size();
        grow(n);
        var perOctave = s.fineRates ? 4 : 1;
        if(ladder == null || perOctave != ladderPerOctave || s.ceiling != ladderCeiling) {
            ladder = RateLadder.of(perOctave, s.ceiling);
            ladderPerOctave = perOctave;
            ladderCeiling = s.ceiling;
            governor.setLadder(ladder);
        }
        var multi = Math.max(s.multiTicks, 1);
        ++generation;
        index.clear();

        var maxWanted = multi;
        var argmax = -1;
        for(int k = 0; k < n; ++k) {
            var net = islands.get(k);
            index.put(net, k);
            var info = infos.computeIfAbsent(net, x -> new Info(nextId()));
            info.seen = generation;
            ids[k] = info.id;
            int demand, w;
            if(s.fineRates) {
                demand = net.computePreferredSubTicks(multi);
                w = demand <= multi ? multi : Math.max(multi, ladder.settle(info.prevWanted, demand));
            } else {
                w = net.computeSubTicks(multi);
                demand = w;
            }
            info.prevWanted = w;
            wanted[k] = w;
            floors[k] = floorFor(demand, w, multi, s);
            parent[k] = k;
            if(w > maxWanted) {
                maxWanted = w;
                argmax = k;
            }
        }
        infos.values().removeIf(info -> info.seen != generation);

        joinLockstep(islands, multi, maxWanted, argmax);
        assignRates(islands, s, n);
    }

    private long idCounter;

    private long nextId() {
        return ++idCounter;
    }

    /** The least rate the governor may give an island: {@code minSamplesPerCycle} of what was asked at {@code samplesPerCycle}. */
    private int floorFor(int demand, int wantedRate, int multi, Settings s) {
        if(wantedRate <= multi || s.samplesPerCycle <= s.minSamplesPerCycle)
            return wantedRate;
        var needed = (int) Math.ceil(demand * (double) s.minSamplesPerCycle / s.samplesPerCycle - 1e-9);
        return Math.max(multi, Math.min(wantedRate, ladder.roundUp(Math.max(needed, 1))));
    }

    private void joinLockstep(List<ElectricalNetwork> islands, int multi, int maxWanted, int argmax) {
        var n = islands.size();
        var pulledStart = new int[0];
        var pulled = 0;
        for(int k = 0; k < n; ++k) {
            var net = islands.get(k);
            if(!net.requiresLockstep())
                continue;
            partners.clear();
            var known = !conservativeLockstep && net.collectLockstepPartners(partners);
            if(known) {
                for(var partner : partners) {
                    var j = index.get(partner);
                    if(j != null)
                        union(k, j);
                }
            } else {
                // The original rule for this island: it goes to the fastest rate in the world.
                if(pulled == pulledStart.length)
                    pulledStart = Arrays.copyOf(pulledStart, Math.max(4, pulled * 2));
                pulledStart[pulled++] = k;
            }
        }
        if(maxWanted > multi) {
            for(int i = 0; i < pulled; ++i)
                union(pulledStart[i], argmax);
        }
    }

    private void assignRates(List<ElectricalNetwork> islands, Settings s, int n) {
        for(int k = 0; k < n; ++k) {
            unitWanted[k] = 0;
            unitFloor[k] = 0;
            unitNodes[k] = 0;
            unitKey[k] = -1;
        }
        for(int k = 0; k < n; ++k) {
            var r = find(k);
            unitWanted[r] = Math.max(unitWanted[r], wanted[k]);
            unitFloor[r] = Math.max(unitFloor[r], floors[k]);
            unitNodes[r] += islands.get(k).size();
            // The member that has existed longest names the unit, so the name survives other
            // members coming and going.
            if(unitKey[r] < 0 || ids[k] < ids[unitKey[r]])
                unitKey[r] = k;
        }

        governing = s.budgetMs > 0;
        entries.clear();
        Arrays.fill(entryOf, 0, n, null);
        if(governing) {
            if(s.budgetMs != budgetApplied) {
                governor.configure(SolveGovernor.Settings.withBudget(s.budgetMs));
                budgetApplied = s.budgetMs;
            }
            for(int k = 0; k < n; ++k) {
                if(parent[k] != k || unitWanted[k] <= 1)
                    continue;
                var entry = entryAt(entries.size());
                entry.set(islands.get(unitKey[k]), unitWanted[k], Math.min(unitFloor[k], unitWanted[k]), unitNodes[k]);
                entries.add(entry);
                entryOf[k] = entry;
            }
            governor.plan(entries);
            governorHasState = true;
        } else if(governorHasState) {
            // Switched off while running: forget the caps so a later switch-on starts clean.
            governor.reset();
            governorHasState = false;
        }

        maxSubTicks = 1;
        for(int k = 0; k < n; ++k) {
            var r = find(k);
            var rate = entryOf[r] != null ? entryOf[r].rate : unitWanted[r];
            islands.get(k).setSubTicks(rate);
            maxSubTicks = Math.max(maxSubTicks, islands.get(k).getSubTicks());
        }
    }

    private SolveGovernor.Entry entryAt(int i) {
        while(pool.size() <= i)
            pool.add(new SolveGovernor.Entry());
        return pool.get(i);
    }

    private void grow(int n) {
        if(parent.length >= n)
            return;
        var size = Math.max(n, parent.length * 2);
        parent = new int[size];
        wanted = new int[size];
        floors = new int[size];
        unitWanted = new int[size];
        unitFloor = new int[size];
        unitNodes = new int[size];
        unitKey = new int[size];
        ids = new long[size];
        entryOf = new SolveGovernor.Entry[size];
        prepareNanos = new long[size];
        stepNanos = new long[size];
    }

    private int find(int k) {
        while(parent[k] != k) {
            parent[k] = parent[parent[k]];
            k = parent[k];
        }
        return k;
    }

    private void union(int a, int b) {
        var ra = find(a);
        var rb = find(b);
        if(ra != rb)
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    // ------------------------------------------------------------------ running

    /** {@code setWorldTick} and {@code prepare} for every island, timing them when the governor is on. */
    public void prepare(List<ElectricalNetwork> islands, long worldTick) {
        var n = islands.size();
        if(governing) {
            totalStart = clock.getAsLong();
            Arrays.fill(prepareNanos, 0, n, 0);
            Arrays.fill(stepNanos, 0, n, 0);
        }
        for(int k = 0; k < n; ++k) {
            var network = islands.get(k);
            network.setWorldTick(worldTick);
            if(governing && network.getSubTicks() > 1) {
                var t0 = clock.getAsLong();
                network.prepare(network.getSubTicks());
                prepareNanos[k] = clock.getAsLong() - t0;
            } else {
                network.prepare(network.getSubTicks());
            }
        }
    }

    /**
     * The stepping loop: {@link #maxSubTicks()} outer iterations, each stepping an island only when
     * its own count crosses a boundary. Ends the tick for the governor.
     * <p>
     * With {@link ParallelIslandStepping#ENABLED} off (the shipped default) this is exactly the
     * plain sequential loop it has always been, with no new allocation — {@link ParallelIslandStepping}
     * is only even asked to bucket islands once that flag is on. When it is on, each round is handed
     * to {@link ParallelIslandStepping#stepRound(List, int, int, LongSupplier, long[])}, which spreads
     * the independent (non-lockstep) islands of that round over a thread pool but still reports each
     * one's own wall time back into {@link #stepNanos}, so the governor's cost model works the same
     * way whether or not a given round actually used the pool.
     */
    public void step(List<ElectricalNetwork> islands) {
        var n = islands.size();
        var max = maxSubTicks;
        if(ParallelIslandStepping.ENABLED) {
            var timing = governing ? stepNanos : null;
            for(int i = 0; i < max; ++i)
                ParallelIslandStepping.stepRound(islands, i, max, clock, timing);
        } else {
            for(int i = 0; i < max; ++i) {
                for(int k = 0; k < n; ++k) {
                    var network = islands.get(k);
                    // Step this island only on the sub-iterations it participates in. The integer
                    // division crosses a boundary exactly `subTicks` times over `max` iterations, so an
                    // island running at the full rate steps every time and one running at 1 steps once,
                    // at the end of the world tick.
                    var subTicks = network.getSubTicks();
                    if((i + 1) * subTicks / max > i * subTicks / max) {
                        if(governing && subTicks > 1) {
                            var t0 = clock.getAsLong();
                            network.singleTick();
                            stepNanos[k] += clock.getAsLong() - t0;
                        } else {
                            network.singleTick();
                        }
                    }
                }
            }
        }
        if(!governing)
            return;
        var total = clock.getAsLong() - totalStart;
        for(var entry : entries) {
            entry.prepNanos = 0;
            entry.stepNanos = 0;
            entry.steps = entry.rate;
        }
        for(int k = 0; k < n; ++k) {
            var entry = entryOf[find(k)];
            if(entry == null)
                continue;
            entry.prepNanos += prepareNanos[k];
            entry.stepNanos += stepNanos[k];
        }
        governor.endTick(entries, total);
    }
}
