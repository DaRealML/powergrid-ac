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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Keeps the wall time the electrical simulation takes per world tick inside a budget by lowering
 * the sub-tick rate of the islands that cost the most.
 * <p>
 * A world tick is 50 ms for the whole server. An island stepped {@code n} times costs about
 * {@code n} solves, so the rate is the one lever that turns a runaway island into a bounded one
 * whatever made it expensive: a large mesh, a rectifier whose Newton iteration will not converge,
 * or simply a machine spun up to a frequency that wants 128 sub-ticks. This class knows nothing of
 * networks. It is handed numbers (what each unit wants, the least it may be given, what the last
 * tick cost) and hands back a rate for each, so it can be run against a fake clock and a synthetic
 * cost model.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><b>Under budget it changes nothing.</b> No unit is ever given less than it wants until
 *       {@link Settings#attackTicks} consecutive world ticks have each cost more than the budget,
 *       or a rate <em>rise</em> is predicted, from the measured cost per step, to break it.</li>
 *   <li><b>Fast attack.</b> After the second consecutive over-budget tick the most expensive unit
 *       is stepped down its ladder, one rung at a time, until the predicted total is under
 *       {@link Settings#targetFraction} of the budget. A single tick is not enough: one garbage
 *       collection pause inside the solve would otherwise cost an island its resolution.</li>
 *   <li><b>Slow, hysteretic release.</b> The cap is lifted one rung at a time, on the cheapest
 *       capped unit, and only after {@link Settings#releaseWaitTicks} ticks that were all under
 *       {@link Settings#releaseFraction} of the budget <em>and</em> only if the predicted cost
 *       after the lift is still under that fraction. Release is therefore judged at a lower level
 *       than attack, and the gap between them is what stops it oscillating: a rate change costs a
 *       matrix rebuild in the island.</li>
 *   <li><b>Learned ceiling.</b> The measured cost per step is remembered for each rate a unit has
 *       run at. A release will not go to a rate whose remembered cost would break the budget, and
 *       a release followed soon by an attack doubles the wait for the next one.</li>
 *   <li><b>It never makes things worse.</b> Cost is not always monotonic in the rate: a rectifier
 *       island needs more Newton iterations per step at a coarser step (see docs/perf). If a
 *       cut does not reduce the unit's cost by at least {@link #MIN_GAIN}, it is undone and the
 *       unit is not cut below that rate again for a while.</li>
 *   <li><b>Never below the floor</b> the caller supplies per unit.</li>
 * </ul>
 *
 * <h2>What is measured</h2>
 * The caller times each governed island's steps and its {@code prepare} with {@link #now()} and
 * reports them per unit, along with the total for the whole tick. The difference between the two
 * is the cost of everything not attributed (islands stepped once per tick, loop overhead), which
 * the governor cannot reduce but must count.
 * <p>
 * Everything is in ticks of the governor's own counter; there is no wall-clock time in the
 * decisions, so a run against a scripted clock is exactly reproducible.
 */
public final class SolveGovernor {
    /** A cut that leaves the unit's cost above this fraction of what it was is judged to have failed. */
    public static final double MIN_GAIN = 0.9;

    private static final int NO_CAP = Integer.MAX_VALUE;
    private static final double EMA_ALPHA = 0.1;
    private static final double UNIT_ALPHA = 0.3;
    private static final int HISTORY_LIMIT = 12;

    /**
     * Tunables. {@code releaseFraction < targetFraction < 1} is enforced: the release threshold
     * being strictly below the attack threshold is the hysteresis, and a release that landed
     * exactly on the edge of the budget would be undone by the next tick's noise.
     *
     * @param budgetMs        wall time per world tick the simulation may take; zero or less disables
     * @param attackTicks     consecutive over-budget ticks before acting
     * @param settleTicks     ticks to wait after any change before judging the result
     * @param targetFraction  an attack stops cutting once the predicted total is under this fraction
     * @param releaseFraction a release needs the cost, before and predicted after, under this fraction
     * @param releaseWaitTicks calm ticks before a release is tried
     * @param memoryTicks     how long a remembered cost per step stays trusted
     * @param maxBackoff      largest multiple the release wait grows to after a premature release
     */
    public record Settings(double budgetMs, int attackTicks, int settleTicks, double targetFraction,
                           double releaseFraction, int releaseWaitTicks, int memoryTicks, int maxBackoff) {
        public Settings {
            if(!(releaseFraction > 0 && releaseFraction < targetFraction && targetFraction < 1))
                throw new IllegalArgumentException("need 0 < releaseFraction < targetFraction < 1, got "
                        + releaseFraction + " and " + targetFraction);
            if(attackTicks < 1 || settleTicks < 1 || releaseWaitTicks < 1 || memoryTicks < 1 || maxBackoff < 1)
                throw new IllegalArgumentException("tick counts must be at least 1");
        }

        /** Defaults: react after two ticks, release after ten seconds of calm at 75% of the budget. */
        public static Settings withBudget(double budgetMs) {
            return new Settings(budgetMs, 2, 2, 0.85, 0.75, 200, 1200, 8);
        }

        public Settings withBudgetMs(double ms) {
            return new Settings(ms, attackTicks, settleTicks, targetFraction, releaseFraction, releaseWaitTicks,
                    memoryTicks, maxBackoff);
        }

        public boolean enabled() {
            return budgetMs > 0;
        }
    }

    /**
     * One governed unit for one tick: an island, or a group of islands that must run at one rate.
     * The caller fills the inputs, calls {@link #plan}, runs the tick, fills the measurements and
     * calls {@link #endTick}. Reused between ticks to keep the hot path allocation free.
     */
    public static final class Entry {
        /** Identity of the unit across ticks. Compared with {@code equals}. */
        public Object key;
        /** Rate the unit would run at ungoverned. */
        public int wanted;
        /** Least rate the governor may give it. Clamped to {@code wanted}. */
        public int floor;
        /** Size, for the log and the command. */
        public int nodes;
        /** Output of {@link #plan}: the rate to run at this tick. */
        public int rate;
        /** Measurements, in nanoseconds: {@code prepare}, all steps, and the step count. */
        public long prepNanos;
        public long stepNanos;
        public int steps;

        public Entry set(Object key, int wanted, int floor, int nodes) {
            this.key = key;
            this.wanted = wanted;
            this.floor = floor;
            this.nodes = nodes;
            this.rate = wanted;
            this.prepNanos = 0;
            this.stepNanos = 0;
            this.steps = 0;
            return this;
        }
    }

    public enum EventKind { ENGAGED, RELEASED, STUCK }

    /**
     * What a server owner needs to find the cause: the size of the unit, what it was doing, what it
     * cost, and what the total was against the budget.
     */
    public record Event(EventKind kind, long tick, int units, int nodes, int wanted, int fromRate, int toRate,
                        double unitMs, double totalMs, double budgetMs) { }

    public interface Listener {
        void onEvent(Event event);
    }

    /** Read-only view for the command. */
    public record UnitStatus(int nodes, int wanted, int floor, int rate, boolean capped, double costMs) { }

    public record Status(boolean enabled, double budgetMs, double lastMs, double averageMs, int cappedUnits,
                         int attacks, int releases, int regrets, boolean stuck, int backoff,
                         List<UnitStatus> units) { }

    private static final class Hist {
        double unit;
        long tick;

        Hist(double unit, long tick) {
            this.unit = unit;
            this.tick = tick;
        }
    }

    private static final class State {
        int cap = NO_CAP;
        int wanted, floor, nodes, rate;
        /** Rate run on the previous tick; zero for a unit not yet measured. */
        int lastRate;
        /** Nanoseconds per step at {@link #lastRate}, smoothed. */
        double unit;
        boolean hasUnit;
        /** Nanoseconds per tick (prepare plus steps) at the current rate: smoothed, and the last raw value. */
        double cost;
        double rawCost;
        long seen;
        /** What the unit was doing when it was first capped, for the log line. */
        int capFrom;
        double capFromCost;
        /** Regret bookkeeping: the last cut, judged a few ticks after it. */
        long cutTick;
        int cutFromRate;
        double cutFromCost;
        /** After a failed cut, the unit is not taken below this rate until {@link #lowerBoundUntil}. */
        int lowerBound;
        long lowerBoundUntil;
        final TreeMap<Integer, Hist> history = new TreeMap<>();

        boolean capped() {
            return cap < wanted;
        }
    }

    private final LongSupplier clock;
    private RateLadder ladder;
    private Settings settings;
    private Listener listener = event -> { };

    private final Map<Object, State> states = new LinkedHashMap<>();
    private long tick;
    private long lastAction = Long.MIN_VALUE / 2;
    private int over;
    private int calm;
    private int backoff = 1;
    private long lastRelease = Long.MIN_VALUE / 2;
    private long lastAttack = Long.MIN_VALUE / 2;
    private long lastDecay = Long.MIN_VALUE / 2;
    private boolean stuckNow;
    private boolean episode;
    private boolean stuckReported;
    private double lastTotal;
    private double emaTotal;
    private double emaOther;
    private boolean started;
    private int attacks, releases, regrets;

    public SolveGovernor(LongSupplier clock, RateLadder ladder, Settings settings) {
        this.clock = clock;
        this.ladder = ladder;
        this.settings = settings;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? event -> { } : listener;
    }

    /** The rungs a cut steps down. Changed between ticks when the ceiling or the rate mode is edited. */
    public void setLadder(RateLadder ladder) {
        this.ladder = ladder;
    }

    /** Change the tunables between ticks, for a config that can be edited while the server runs. */
    public void configure(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /** Forget every cap and measurement, as when the governor is switched off and on again. */
    public void reset() {
        states.clear();
        over = 0;
        calm = 0;
        backoff = 1;
        episode = false;
        stuckReported = false;
        stuckNow = false;
        started = false;
    }

    /** The injected clock, in nanoseconds. The stepping code times through this so a test can script it. */
    public long now() {
        return clock.getAsLong();
    }

    public long tick() {
        return tick;
    }

    // ------------------------------------------------------------------ planning

    /**
     * Decide each unit's rate for the coming tick and write it to {@link Entry#rate}.
     * <p>
     * Every unit gets {@code min(wanted, cap)}, raised to its floor. Then, if any rate rose since
     * the last tick and the cost per step measured so far predicts the total would break the
     * budget, the rises are held back. That is the only place the governor acts before it has seen
     * an over-budget tick, and it exists so that a machine spinning up through a rung does not cost
     * one bad tick per rung.
     */
    public void plan(List<Entry> units) {
        for(var entry : units) {
            var st = states.computeIfAbsent(entry.key, key -> new State());
            st.seen = tick;
            st.wanted = entry.wanted;
            st.floor = Math.min(entry.floor, entry.wanted);
            st.nodes = entry.nodes;
            // A demand that has fallen to the cap or below: the cap restrains nothing any more.
            if(st.cap != NO_CAP && st.wanted <= st.cap)
                st.cap = NO_CAP;
            st.rate = Math.max(Math.min(st.wanted, st.cap), st.floor);
            entry.rate = st.rate;
        }
        for(Iterator<State> it = states.values().iterator(); it.hasNext(); ) {
            if(it.next().seen != tick)
                it.remove();
        }
        if(settings.enabled())
            holdBackRises(units);
        updateEpisode(units);
    }

    private void holdBackRises(List<Entry> units) {
        var budget = settings.budgetMs() * 1e6;
        var rising = new ArrayList<Entry>();
        for(var entry : units) {
            var st = states.get(entry.key);
            // Only a rise that is predicted to cost more counts. A rate above the last one that
            // is measured cheaper (after a cut that made things worse was undone) is a saving.
            if(st.hasUnit && st.lastRate > 0 && entry.rate > st.lastRate && unitCost(st, entry.rate) > st.cost)
                rising.add(entry);
        }
        if(rising.isEmpty())
            return;
        if(predictedTotal(units) <= budget)
            return;
        var target = budget * settings.targetFraction();
        while(predictedTotal(units) > target) {
            Entry pick = null;
            var pickCost = -1.0;
            for(var entry : rising) {
                var st = states.get(entry.key);
                var lowest = Math.max(st.lastRate, st.floor);
                if(entry.rate <= lowest)
                    continue;
                var cost = unitCost(st, entry.rate);
                if(cost > pickCost) {
                    pick = entry;
                    pickCost = cost;
                }
            }
            if(pick == null)
                break;
            var st = states.get(pick.key);
            pick.rate = Math.max(ladder.below(pick.rate), Math.max(st.lastRate, st.floor));
        }
        for(var entry : rising) {
            var st = states.get(entry.key);
            if(entry.rate < st.wanted) {
                if(!st.capped()) {
                    st.capFrom = st.wanted;
                    st.capFromCost = st.cost;
                }
                st.cap = entry.rate;
            }
        }
    }

    // ------------------------------------------------------------------ after the tick

    /**
     * Record what the tick cost and, when the budget has been broken long enough, act on it.
     * The changes take effect at the next {@link #plan}.
     *
     * @param totalNanos wall time of the whole simulation this tick, prepare and steps together
     */
    public void endTick(List<Entry> units, long totalNanos) {
        ++tick;
        if(!settings.enabled()) {
            over = 0;
            calm = 0;
            return;
        }

        double attributed = 0;
        for(var entry : units) {
            var st = states.get(entry.key);
            if(st == null)
                continue;
            // The steps alone: prepare is a one-off rebuild after a rate change and would make every
            // cut look worse than it is. It still counts in the total, as unattributed time.
            var cost = (double) entry.stepNanos;
            attributed += cost;
            var rateChanged = st.lastRate != entry.rate;
            st.rawCost = cost;
            st.cost = rateChanged || !st.hasUnit ? cost : st.cost + UNIT_ALPHA * (cost - st.cost);
            if(entry.steps > 0) {
                // The cost per step excludes prepare, which is a one-off rebuild after a rate change.
                var unit = (double) entry.stepNanos / entry.steps;
                st.unit = rateChanged || !st.hasUnit ? unit : st.unit + UNIT_ALPHA * (unit - st.unit);
                st.hasUnit = true;
                var hist = st.history.get(entry.rate);
                if(hist == null) {
                    st.history.put(entry.rate, new Hist(st.unit, tick));
                    while(st.history.size() > HISTORY_LIMIT)
                        st.history.remove(oldest(st));
                } else {
                    hist.unit = st.unit;
                    hist.tick = tick;
                }
            }
            st.lastRate = entry.rate;
        }

        lastTotal = totalNanos;
        if(totalNanos <= settings.budgetMs() * 1e6)
            stuckNow = false;
        var other = Math.max(0, totalNanos - attributed);
        if(!started) {
            emaTotal = totalNanos;
            emaOther = other;
            started = true;
        } else {
            emaTotal += EMA_ALPHA * (totalNanos - emaTotal);
            emaOther += UNIT_ALPHA * (other - emaOther);
        }

        judgeCuts(units);

        var budget = settings.budgetMs() * 1e6;
        over = totalNanos > budget ? over + 1 : 0;
        if(over >= settings.attackTicks() && tick - lastAction >= settings.settleTicks()) {
            attack(units, totalNanos);
            over = 0;
            calm = 0;
        } else {
            calm = totalNanos <= settings.releaseFraction() * budget ? calm + 1 : 0;
            if(calm >= settings.releaseWaitTicks() * backoff && cappedCount() > 0
                    && tick - lastAction >= settings.settleTicks()) {
                release(units);
                calm = 0;
            }
            // A release that has survived three waits without an attack was a good one.
            if(backoff > 1 && lastAttack < lastRelease
                    && tick - Math.max(lastRelease, lastDecay) > 3L * settings.releaseWaitTicks() * backoff) {
                backoff = Math.max(1, backoff / 2);
                lastDecay = tick;
            }
        }
        updateEpisode(units);
    }

    private static Integer oldest(State st) {
        Integer key = null;
        var age = Long.MAX_VALUE;
        for(var e : st.history.entrySet()) {
            if(e.getValue().tick < age) {
                age = e.getValue().tick;
                key = e.getKey();
            }
        }
        return key;
    }

    /** Undo a cut that bought (almost) nothing. */
    private void judgeCuts(List<Entry> units) {
        for(var entry : units) {
            var st = states.get(entry.key);
            if(st == null || st.cutTick == 0 || tick - st.cutTick < settings.settleTicks())
                continue;
            var baseline = st.cutFromCost;
            st.cutTick = 0;
            if(st.cost <= baseline * MIN_GAIN)
                continue;
            ++regrets;
            var best = bestRate(st, st.cutFromRate);
            st.lowerBound = best;
            st.lowerBoundUntil = tick + settings.memoryTicks();
            st.cap = best >= st.wanted ? NO_CAP : best;
            lastAction = tick;
        }
    }

    /**
     * The rung between the floor and what the unit wants that has measured cheapest, preferring the
     * higher one on a near tie because it is the better waveform. {@code from} when nothing else is
     * remembered.
     */
    private int bestRate(State st, int from) {
        var best = from;
        var bestCost = Double.MAX_VALUE;
        for(var e : st.history.entrySet()) {
            var rate = e.getKey();
            if(rate > st.wanted || rate < st.floor || tick - e.getValue().tick > settings.memoryTicks())
                continue;
            var cost = e.getValue().unit * rate;
            if(cost < bestCost * 0.97 || (cost <= bestCost * 1.03 && rate > best)) {
                best = rate;
                bestCost = Math.min(cost, bestCost);
            }
        }
        return best;
    }

    private void attack(List<Entry> units, long totalNanos) {
        ++attacks;
        if(tick - lastRelease <= 2L * settings.releaseWaitTicks() * backoff)
            backoff = Math.min(settings.maxBackoff(), backoff * 2);
        lastAttack = tick;

        var budget = settings.budgetMs() * 1e6;
        var target = budget * settings.targetFraction();
        var n = units.size();
        var rate = new int[n];
        var origin = new int[n];
        var cost = new double[n];
        var lowest = new int[n];
        for(int i = 0; i < n; ++i) {
            var entry = units.get(i);
            var st = states.get(entry.key);
            rate[i] = entry.rate;
            origin[i] = entry.rate;
            cost[i] = st.rawCost;
            lowest[i] = Math.max(1, st.floor);
            if(st.lowerBoundUntil > tick)
                lowest[i] = Math.max(lowest[i], Math.min(st.lowerBound, st.wanted));
        }
        var predicted = (double) totalNanos;

        while(predicted > target) {
            var pick = -1;
            var pickCost = -1.0;
            for(int i = 0; i < n; ++i) {
                if(rate[i] <= lowest[i])
                    continue;
                if(cost[i] > pickCost) {
                    pick = i;
                    pickCost = cost[i];
                }
            }
            if(pick < 0)
                break;
            var st = states.get(units.get(pick).key);
            // The next rung down, but not below the floor or the learned bound, and never more than
            // one octave in a single action: the prediction is linear in the rate and cost is not,
            // so the rest waits for the next action, after the result of this one has been seen.
            // The most expensive island being at its limit for this action ends the action; the
            // cheaper ones are not made to pay for what it could still give up.
            var next = Math.max(ladder.below(rate[pick]), lowest[pick]);
            if(next * 2 < origin[pick])
                break;
            var before = cost[pick];
            var after = unitCost(st, next);
            if(after <= 0)
                after = before * next / rate[pick];
            cost[pick] = after;
            predicted += after - before;
            rate[pick] = next;
        }

        var changed = 0;
        for(int i = 0; i < n; ++i) {
            if(rate[i] == origin[i])
                continue;
            ++changed;
            var entry = units.get(i);
            var st = states.get(entry.key);
            if(!st.capped()) {
                st.capFrom = origin[i];
                st.capFromCost = st.rawCost;
            }
            st.cap = rate[i] < st.wanted ? rate[i] : NO_CAP;
            st.cutTick = tick;
            st.cutFromRate = origin[i];
            st.cutFromCost = st.rawCost;
        }
        if(changed > 0)
            lastAction = tick;
        stuckNow = changed == 0;
        if(stuckNow && !stuckReported) {
            // Over budget and nothing left to shed: every governable unit is at its floor, or is
            // an island stepped once per tick. Said once per episode; the owner needs to look at
            // the biggest island, which no rate can help.
            stuckReported = true;
            var biggest = -1;
            for(int i = 0; i < n; ++i) {
                if(biggest < 0 || cost[i] > cost[biggest])
                    biggest = i;
            }
            var nodes = biggest < 0 ? 0 : states.get(units.get(biggest).key).nodes;
            var at = biggest < 0 ? 0 : origin[biggest];
            listener.onEvent(new Event(EventKind.STUCK, tick, n, nodes, at, at, at,
                    biggest < 0 ? 0 : cost[biggest] / 1e6, totalNanos / 1e6, settings.budgetMs()));
        }
    }

    private void release(List<Entry> units) {
        var budget = settings.budgetMs() * 1e6;
        var limit = budget * settings.releaseFraction();
        var order = new ArrayList<Entry>(units);
        order.removeIf(entry -> !states.get(entry.key).capped());
        order.sort((a, b) -> Double.compare(states.get(a.key).cost, states.get(b.key).cost));
        var total = emaOther;
        for(var entry : units)
            total += states.get(entry.key).cost;
        for(var entry : order) {
            var st = states.get(entry.key);
            var current = Math.min(st.wanted, st.cap);
            var next = Math.min(ladder.above(current), st.wanted);
            if(next <= current)
                continue;
            var after = unitCost(st, next);
            if(after <= 0)
                after = st.cost * next / current;
            if(total - st.cost + after > limit)
                continue;
            st.cap = next >= st.wanted ? NO_CAP : next;
            // A raised cap invalidates any pending judgement of an earlier cut.
            st.cutTick = 0;
            ++releases;
            lastRelease = tick;
            lastAction = tick;
            return;
        }
    }

    // ------------------------------------------------------------------ cost model

    /**
     * Predicted wall time of {@code st} at {@code rate}, in nanoseconds per tick, from the cost per
     * step measured at that rate if it is remembered and fresh, otherwise scaled linearly from the
     * nearest rate that is. Zero when nothing is known.
     */
    private double unitCost(State st, int rate) {
        if(st.hasUnit && st.lastRate == rate)
            return st.unit * rate;
        var hist = st.history.get(rate);
        if(hist != null && tick - hist.tick <= settings.memoryTicks())
            return hist.unit * rate;
        Hist near = null;
        var nearRate = 0;
        for(var e : st.history.entrySet()) {
            if(tick - e.getValue().tick > settings.memoryTicks())
                continue;
            if(near == null || Math.abs(e.getKey() - rate) < Math.abs(nearRate - rate)) {
                near = e.getValue();
                nearRate = e.getKey();
            }
        }
        return near == null ? 0 : near.unit * rate;
    }

    /** Predicted total at the rates now in {@code entry.rate}, from smoothed costs. */
    private double predictedTotal(List<Entry> units) {
        var total = emaOther;
        for(var entry : units) {
            var st = states.get(entry.key);
            total += st.lastRate == entry.rate || !st.hasUnit ? st.cost : unitCost(st, entry.rate);
        }
        return total;
    }

    private int cappedCount() {
        var count = 0;
        for(var st : states.values()) {
            if(st.capped())
                ++count;
        }
        return count;
    }

    /** Fires the first ENGAGED after a quiet spell and the RELEASED when the last cap is gone. */
    private void updateEpisode(List<Entry> units) {
        var capped = 0;
        State biggest = null;
        for(var st : states.values()) {
            if(!st.capped())
                continue;
            ++capped;
            if(biggest == null || st.capFromCost > biggest.capFromCost)
                biggest = st;
        }
        if(!episode && capped > 0) {
            episode = true;
            stuckReported = false;
            listener.onEvent(new Event(EventKind.ENGAGED, tick, states.size(), biggest.nodes, biggest.wanted,
                    biggest.capFrom, biggest.cap, biggest.capFromCost / 1e6, lastTotal / 1e6, settings.budgetMs()));
        } else if(episode && capped == 0) {
            episode = false;
            stuckReported = false;
            stuckNow = false;
            listener.onEvent(new Event(EventKind.RELEASED, tick, states.size(), 0, 0, 0, 0, 0,
                    lastTotal / 1e6, settings.budgetMs()));
        }
    }

    // ------------------------------------------------------------------ reporting

    /** Number of units that currently run below what they want. */
    public int cappedUnits() {
        return cappedCount();
    }

    public Status status() {
        var list = new ArrayList<UnitStatus>();
        for(var st : states.values())
            list.add(new UnitStatus(st.nodes, st.wanted, st.floor, st.rate, st.capped(), st.cost / 1e6));
        list.sort((a, b) -> Double.compare(b.costMs(), a.costMs()));
        return new Status(settings.enabled(), settings.budgetMs(), lastTotal / 1e6, emaTotal / 1e6, cappedCount(),
                attacks, releases, regrets, stuckNow, backoff, list);
    }

    /** Test hook: the cap currently held for a unit, or {@code Integer.MAX_VALUE} for none. */
    public int capOf(Object key) {
        var st = states.get(key);
        return st == null ? NO_CAP : st.cap;
    }
}
