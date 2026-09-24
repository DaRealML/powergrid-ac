package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.schedule.RateLadder;
import org.patryk3211.powergrid.electricity.sim.schedule.SolveGovernor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * The governor against a scripted clock and synthetic islands whose cost per step is a function of
 * the rate. Nothing here touches a solver: what is under test is when it acts, by how much, and
 * that it does not oscillate.
 */
public class SolveGovernorTest {
    private static final double MS = 1e6;

    /** A world of named units. One {@link #tick()} is plan, run against the fake clock, end. */
    static final class Sim {
        long nanos;
        final SolveGovernor governor;
        final List<SolveGovernor.Event> events = new ArrayList<>();
        final Map<String, SolveGovernor.Entry> entries = new LinkedHashMap<>();
        final Map<String, IntToDoubleFunction> stepNanos = new LinkedHashMap<>();
        final Map<String, int[]> shape = new LinkedHashMap<>();
        final Map<String, Integer> lastRate = new LinkedHashMap<>();
        double otherNanos = 0.5 * MS;
        double prepareNanos = 0.3 * MS;
        /** Rate changes per unit since the start, first appearance not counted. */
        final Map<String, Integer> changes = new LinkedHashMap<>();
        double worstMs;

        Sim(SolveGovernor.Settings settings, int ceiling) {
            governor = new SolveGovernor(() -> nanos, RateLadder.of(4, ceiling), settings);
            governor.setListener(events::add);
        }

        Sim unit(String name, int wanted, int floor, IntToDoubleFunction nanosPerStep) {
            entries.put(name, new SolveGovernor.Entry());
            stepNanos.put(name, nanosPerStep);
            shape.put(name, new int[]{ wanted, floor, 100 });
            return this;
        }

        void remove(String name) {
            entries.remove(name);
            stepNanos.remove(name);
            shape.remove(name);
            lastRate.remove(name);
        }

        void wanted(String name, int wanted) {
            shape.get(name)[0] = wanted;
        }

        int rate(String name) {
            return entries.get(name).rate;
        }

        /** Runs one world tick and returns what it cost in milliseconds. */
        double tick() {
            var list = new ArrayList<SolveGovernor.Entry>();
            for(var e : entries.entrySet()) {
                var s = shape.get(e.getKey());
                list.add(e.getValue().set(e.getKey(), s[0], s[1], s[2]));
            }
            governor.plan(list);
            var t0 = governor.now();
            for(var entry : list) {
                var name = (String) entry.key;
                var previous = lastRate.get(name);
                var p0 = governor.now();
                if(previous != null && previous != entry.rate) {
                    nanos += (long) prepareNanos;
                    changes.merge(name, 1, Integer::sum);
                }
                entry.prepNanos = governor.now() - p0;
                var s0 = governor.now();
                for(int i = 0; i < entry.rate; ++i)
                    nanos += (long) stepNanos.get(name).applyAsDouble(entry.rate);
                entry.stepNanos = governor.now() - s0;
                entry.steps = entry.rate;
                lastRate.put(name, entry.rate);
            }
            nanos += (long) otherNanos;
            var total = governor.now() - t0;
            governor.endTick(list, total);
            worstMs = Math.max(worstMs, total / MS);
            return total / MS;
        }

        void run(int ticks) {
            for(int i = 0; i < ticks; ++i)
                tick();
        }

        int totalChanges() {
            return changes.values().stream().mapToInt(Integer::intValue).sum();
        }

        long count(SolveGovernor.EventKind kind) {
            return events.stream().filter(e -> e.kind() == kind).count();
        }
    }

    private static SolveGovernor.Settings budget(double ms) {
        return SolveGovernor.Settings.withBudget(ms);
    }

    /** Cost per step proportional to the rate's cost, in nanoseconds: {@code ms} per step. */
    private static IntToDoubleFunction linear(double msPerStep) {
        return rate -> msPerStep * MS;
    }

    // ------------------------------------------------------------------ it must do nothing when it is not needed

    @Test
    void underBudgetItChangesNothing() {
        var sim = new Sim(budget(10), 128);
        sim.unit("a", 64, 16, linear(0.05));   // 3.2 ms
        sim.unit("b", 32, 8, linear(0.04));    // 1.3 ms
        sim.unit("c", 1, 1, linear(0.2));
        for(int t = 0; t < 2000; ++t) {
            // Wander the demand too: rises that predict under budget are not held back either.
            if(t % 400 == 0)
                sim.wanted("a", t % 800 == 0 ? 64 : 96);
            sim.tick();
            Assertions.assertEquals(sim.shape.get("a")[0], sim.rate("a"), "tick " + t);
            Assertions.assertEquals(32, sim.rate("b"));
        }
        Assertions.assertEquals(0, sim.governor.cappedUnits());
        Assertions.assertTrue(sim.events.isEmpty(), sim.events.toString());
        Assertions.assertTrue(sim.worstMs < 10);
    }

    @Test
    void aDisabledGovernorNeverActsHoweverOverloaded() {
        var sim = new Sim(budget(0), 128);
        sim.unit("a", 128, 8, linear(0.4));
        sim.run(100);
        Assertions.assertEquals(128, sim.rate("a"));
        Assertions.assertTrue(sim.events.isEmpty());
    }

    @Test
    void aSingleSlowTickIsNotAnOverload() {
        // One garbage-collection pause inside the solve must not cost an island its resolution.
        var sim = new Sim(budget(10), 128);
        sim.unit("a", 64, 8, linear(0.1));
        sim.run(50);
        sim.otherNanos = 40 * MS;
        sim.tick();
        sim.otherNanos = 0.5 * MS;
        sim.run(500);
        Assertions.assertEquals(64, sim.rate("a"));
        Assertions.assertTrue(sim.events.isEmpty(), sim.events.toString());
    }

    // ------------------------------------------------------------------ attack

    @Test
    void anOverloadIsCutWithinTwoTicksAndHeldUnderBudget() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 128, 20, linear(0.4));    // 51 ms at 128
        sim.tick();
        sim.tick();
        // Two ticks over budget have been measured; the third plan already runs the cut.
        sim.tick();
        Assertions.assertTrue(sim.rate("alt") < 128, "not cut after two over-budget ticks");
        var rate = sim.rate("alt");
        // Keep going: the cut reaches a rate under budget in a few more actions, then holds.
        sim.run(40);
        Assertions.assertTrue(sim.rate("alt") >= 20, "went below the floor");
        Assertions.assertTrue(sim.tick() <= 10, "still over budget after settling");
        var settled = sim.rate("alt");
        Assertions.assertTrue(settled < rate, "the first cut alone should not have been enough for a 51 ms island");
        sim.run(150);
        Assertions.assertEquals(settled, sim.rate("alt"), "the cut must hold while the load is unchanged");
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.ENGAGED));
        var engaged = sim.events.get(0);
        Assertions.assertEquals(SolveGovernor.EventKind.ENGAGED, engaged.kind());
        Assertions.assertEquals(100, engaged.nodes());
        Assertions.assertEquals(128, engaged.fromRate());
        Assertions.assertEquals(0.4 * 128, engaged.unitMs(), 0.5, "the log line names the island's cost");
    }

    @Test
    void theMostExpensiveIslandIsCutFirstAndTheCheapOneIsLeftAlone() {
        var sim = new Sim(budget(20), 128);
        sim.unit("big", 128, 16, linear(0.2));    // 25.6 ms
        sim.unit("small", 64, 16, linear(0.1));   // 6.4 ms
        sim.run(30);
        Assertions.assertEquals(64, sim.rate("small"), "the cheap island paid for the expensive one");
        Assertions.assertTrue(sim.rate("big") < 128);
        Assertions.assertTrue(sim.tick() <= 20);
    }

    @Test
    void theFloorIsNeverBreachedAndTheStalemateIsReportedOnce() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 128, 40, linear(0.4));    // 16 ms even at the floor
        sim.run(400);
        Assertions.assertEquals(40, sim.rate("alt"));
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.STUCK), "the stalemate must be logged once, not every tick");
        Assertions.assertTrue(sim.governor.status().stuck());
    }

    @Test
    void anIslandAtOneSubTickCannotBeShedAndIsNotPunished() {
        var sim = new Sim(budget(10), 128);
        sim.unit("dc", 1, 1, linear(15));         // 15 ms at rate 1: a huge DC island
        sim.run(100);
        Assertions.assertEquals(1, sim.rate("dc"));
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.STUCK));
    }

    // ------------------------------------------------------------------ the cost model

    @Test
    void aRateRiseThatWouldBreakTheBudgetIsHeldBackBeforeItIsRun() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 32, 8, linear(0.2));      // 6.4 ms: comfortable
        sim.run(100);
        Assertions.assertTrue(sim.worstMs < 10);
        // The rotor spins up: the machine now wants 128, which would cost 25.6 ms.
        sim.wanted("alt", 128);
        var first = sim.tick();
        Assertions.assertTrue(first <= 10 + 1e-9, "the first tick after the change was already over budget: " + first);
        Assertions.assertTrue(sim.rate("alt") < 128 && sim.rate("alt") >= 32);
        Assertions.assertEquals(0, sim.governor.status().attacks(), "no over-budget tick was needed to act");
    }

    // ------------------------------------------------------------------ release and hysteresis

    @Test
    void itReleasesSlowlyOneRungAtATimeOnceTheLoadIsGone() {
        var sim = new Sim(budget(10), 128);
        var load = new double[]{ 0.4 };
        sim.unit("alt", 128, 20, r -> load[0] * MS);
        sim.run(60);
        var capped = sim.rate("alt");
        Assertions.assertTrue(capped < 128);
        // The cause goes away: a tenth of the cost per step.
        load[0] = 0.04;
        var releaseWait = sim.governor.settings().releaseWaitTicks();
        sim.run(releaseWait - 10);
        Assertions.assertEquals(capped, sim.rate("alt"), "released before the calm period was over");
        var previous = capped;
        var steps = 0;
        for(int t = 0; t < 40 * releaseWait && sim.rate("alt") < 128; ++t) {
            sim.tick();
            var now = sim.rate("alt");
            if(now != previous) {
                Assertions.assertEquals(RateLadder.of(4, 128).above(previous), now, "released more than one rung at once");
                previous = now;
                ++steps;
            }
        }
        Assertions.assertEquals(128, sim.rate("alt"));
        Assertions.assertTrue(steps >= 2, "a release should take several steps");
        sim.run(10);
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.RELEASED));
        Assertions.assertEquals(0, sim.governor.cappedUnits());
    }

    /**
     * A load with a cliff: cheap up to 64 sub-ticks, 2.5 times dearer above, as when a diode
     * bridge stops converging at the finer step. A linear model cannot see it coming.
     */
    private static IntToDoubleFunction cliff(double cheap, double dear) {
        return rate -> (rate <= 64 ? cheap : dear) * MS;
    }

    @Test
    void itDoesNotFlapWhenReleasingWouldBreakTheBudget() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 128, 20, cliff(0.1, 0.25));
        sim.run(6000);
        // One descent, at most three rate changes, and then nothing for five minutes.
        Assertions.assertTrue(sim.totalChanges() <= 3, "rate changed " + sim.totalChanges() + " times");
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.ENGAGED));
        Assertions.assertEquals(0, sim.count(SolveGovernor.EventKind.RELEASED));
        Assertions.assertTrue(sim.worstMs < 40, "the overload was not cut quickly enough: " + sim.worstMs);
    }

    @Test
    void aReleaseThresholdAtTheAttackThresholdFlaps() {
        // The same cliff as above, with the release fraction moved to the edge of the attack target.
        // This is the test that makes the one above mean something: it shows the machinery does flap
        // if the gap between the two thresholds is taken away.
        var loose = new SolveGovernor.Settings(10, 2, 2, 0.99, 0.98, 200, 1200, 8);
        var sim = new Sim(loose, 128);
        sim.unit("alt", 128, 20, cliff(0.1, 0.25));
        sim.run(6000);
        Assertions.assertTrue(sim.totalChanges() > 3, "expected flapping, saw " + sim.totalChanges() + " changes");
    }

    @Test
    void theThresholdsMustLeaveAGap() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SolveGovernor.Settings(10, 2, 2, 0.85, 0.85, 200, 1200, 8), "release equal to attack target");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SolveGovernor.Settings(10, 2, 2, 0.85, 0.9, 200, 1200, 8), "release above attack target");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SolveGovernor.Settings(10, 2, 2, 1.0, 0.75, 200, 1200, 8), "attack target at the budget itself");
    }

    // ------------------------------------------------------------------ status must match what actually ran

    @Test
    void statusReflectsAHeldBackRiseOnTheSameTick() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 32, 8, linear(0.2));      // 6.4 ms: comfortable
        sim.run(100);
        // The rotor spins up: wanted jumps to 128, which would cost 25.6 ms and is held back
        // before it ever runs (aRateRiseThatWouldBreakTheBudgetIsHeldBackBeforeItIsRun).
        sim.wanted("alt", 128);
        sim.tick();
        var applied = sim.rate("alt");
        Assertions.assertTrue(applied < 128, "the rise should have been held back this same tick");
        var reported = sim.governor.status().units().get(0).rate();
        Assertions.assertEquals(applied, reported,
                "status must report the rate actually applied this tick, not the full wanted rate");
    }

    // ------------------------------------------------------------------ release order and the cost model's tie-break

    @Test
    void releaseGoesToTheCheapestCappedUnitFirst() {
        var sim = new Sim(budget(10), 128);
        // Same per-step cost for both; only the floor differs, so once both are driven all the way
        // down under a heavy, unshakeable load, "cheap" is unambiguously the cheaper total ms.
        var load = new double[]{ 0.3 };
        sim.unit("cheap", 128, 16, r -> load[0] * MS);
        sim.unit("dear", 128, 64, r -> load[0] * MS);
        sim.run(400);
        Assertions.assertEquals(16, sim.rate("cheap"), "cheap should be driven to its own floor");
        Assertions.assertEquals(64, sim.rate("dear"), "dear should be driven to its own floor");
        Assertions.assertEquals(2, sim.governor.cappedUnits(), sim.governor.status().toString());

        // The load disappears for both at once: whichever is cheapest right now must release first.
        load[0] = 0.01;
        String firstToRise = null;
        var prevCheap = sim.rate("cheap");
        var prevDear = sim.rate("dear");
        var releaseWait = sim.governor.settings().releaseWaitTicks();
        for(int t = 0; t < 40 * releaseWait && firstToRise == null; ++t) {
            sim.tick();
            var c = sim.rate("cheap");
            var d = sim.rate("dear");
            if(c != prevCheap)
                firstToRise = "cheap";
            else if(d != prevDear)
                firstToRise = "dear";
            prevCheap = c;
            prevDear = d;
        }
        Assertions.assertEquals("cheap", firstToRise,
                "release() must try the cheapest capped unit first: " + sim.governor.status());
    }

    @Test
    void bestRateOnANearTiePrefersTheHigherRememberedRate() {
        var sim = new Sim(budget(10), 128);
        // 128 costs only 1% more in total than 64: a near tie inside bestRate()'s own 3% band. A
        // plain lowest-cost comparison would settle for 64 (marginally cheaper, and the first entry
        // TreeMap hands it); the documented rule prefers the higher rate on a near tie "because it
        // is the better waveform". The 10% MIN_GAIN bar makes a 1% saving a regret either way, which
        // is what puts bestRate() in the driver's seat instead of the plain attack/release path.
        // Neither rate is actually under budget here (both cost about 15 ms against a 10 ms budget),
        // so the unit is judged, uncapped, and immediately re-capped by holdBackRises() within the
        // same couple of ticks -- capOf() is read right at the judgement, before that re-cap can
        // hide what bestRate() actually decided.
        sim.unit("u", 128, 8, rate -> (rate <= 64 ? 15.0 : 15.15) / rate * MS);
        var before = sim.governor.status().regrets();
        var capAtJudgement = Integer.MIN_VALUE;
        for(int t = 0; t < 10 && sim.governor.status().regrets() == before; ++t) {
            sim.tick();
            if(sim.governor.status().regrets() > before)
                capAtJudgement = sim.governor.capOf("u");
        }
        Assertions.assertNotEquals(Integer.MIN_VALUE, capAtJudgement,
                "expected the first cut (128 -> 64) to be judged a regret: " + sim.governor.status());
        Assertions.assertEquals(Integer.MAX_VALUE, capAtJudgement,
                "a near-tie must prefer the higher remembered rate (128, i.e. no cap) over the "
                        + "marginally cheaper 64: cap was " + capAtJudgement);
    }

    // ------------------------------------------------------------------ it must never make things worse

    @Test
    void aCutThatMakesTheIslandDearerIsUndoneAndNotRepeated() {
        var sim = new Sim(budget(20), 128);
        // A mesh with rectifier branches: per step cheap at 32 and above, but four times dearer per
        // step at 16 and below, where the Newton iteration needs many more passes (bench h_mesh300_3diodes).
        sim.unit("mesh", 128, 8, rate -> (rate >= 32 ? 0.8 : 3.9) * MS);
        sim.run(200);
        // The cheapest measured rate is 32 (25.6 ms) - not 16 (62 ms), and not the floor.
        Assertions.assertEquals(32, sim.rate("mesh"));
        Assertions.assertTrue(sim.governor.status().regrets() >= 1);
        var changes = sim.totalChanges();
        sim.run(500);
        Assertions.assertEquals(changes, sim.totalChanges(), "it kept trying rates it had already found worse");
    }

    // ------------------------------------------------------------------ bookkeeping

    @Test
    void aUnitThatDisappearsEndsTheEpisode() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 128, 20, linear(0.4));
        sim.run(30);
        Assertions.assertEquals(1, sim.governor.cappedUnits());
        sim.remove("alt");
        sim.unit("quiet", 1, 1, linear(0.1));
        sim.tick();
        Assertions.assertEquals(0, sim.governor.cappedUnits());
        Assertions.assertEquals(1, sim.count(SolveGovernor.EventKind.RELEASED));
    }

    @Test
    void aDemandBelowTheCapEndsTheCap() {
        var sim = new Sim(budget(10), 128);
        sim.unit("alt", 128, 20, linear(0.4));
        sim.run(30);
        Assertions.assertTrue(sim.governor.cappedUnits() > 0);
        sim.wanted("alt", 20);      // the machine slowed down: it no longer wants more than the cap
        sim.tick();
        Assertions.assertEquals(20, sim.rate("alt"));
        Assertions.assertEquals(0, sim.governor.cappedUnits());
        Assertions.assertEquals(Integer.MAX_VALUE, sim.governor.capOf("alt"), "a cap that restrains nothing is forgotten, not kept");
    }

    @Test
    void theSameInputsGiveTheSameDecisions() {
        var traces = new ArrayList<List<Integer>>();
        for(int run = 0; run < 2; ++run) {
            var sim = new Sim(budget(10), 128);
            sim.unit("a", 128, 20, cliff(0.1, 0.25));
            sim.unit("b", 96, 16, linear(0.05));
            sim.unit("c", 64, 16, linear(0.09));
            // Two islands that cost exactly the same: a tie the governor must break the same way every run.
            sim.unit("d", 96, 16, linear(0.06));
            sim.unit("e", 96, 64, linear(0.06));
            var trace = new ArrayList<Integer>();
            for(int t = 0; t < 1500; ++t) {
                sim.tick();
                trace.add(sim.rate("a") * 10000 + sim.rate("b") * 100 + sim.rate("c") + sim.rate("d") * 1000000 + sim.rate("e") * 100000000);
            }
            traces.add(trace);
        }
        Assertions.assertEquals(traces.get(0), traces.get(1));
    }

    @Test
    void theInjectedClockIsTheOnlyTimeSource() {
        // now() is exactly the supplied clock, so a caller timing through it is timing the fake.
        var t = new long[]{ 123 };
        var governor = new SolveGovernor(() -> t[0], RateLadder.of(4, 128), budget(10));
        Assertions.assertEquals(123, governor.now());
        t[0] = 456;
        Assertions.assertEquals(456, governor.now());
    }

    @Test
    void statusReportsWhatTheCommandPrints() {
        var sim = new Sim(budget(10), 128);
        sim.unit("dc", 1, 1, linear(0.1));
        sim.unit("alt", 128, 20, linear(0.4));
        sim.run(40);
        var status = sim.governor.status();
        Assertions.assertTrue(status.enabled());
        Assertions.assertEquals(10, status.budgetMs());
        Assertions.assertEquals(1, status.cappedUnits());
        Assertions.assertEquals(2, status.units().size());
        Assertions.assertTrue(status.units().get(0).capped(), "the most expensive island is listed first");
        Assertions.assertTrue(status.averageMs() > 0 && status.lastMs() > 0);
    }
}
