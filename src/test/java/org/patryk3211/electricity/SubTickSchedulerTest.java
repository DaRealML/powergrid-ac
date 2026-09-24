package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.schedule.SolveGovernor;
import org.patryk3211.powergrid.electricity.sim.schedule.SubTickScheduler;
import org.patryk3211.powergrid.electricity.sim.solver.JavaMNA;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePort;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The scheduler against real islands: that with everything off it steps exactly as the original
 * loop did, that the governor changes nothing while the budget holds, and that the new rates and
 * the lockstep groups do what they claim.
 */
public class SubTickSchedulerTest {
    /** Steps a {@link SolverBench.World} through a {@link SubTickScheduler} instead of its own loop. */
    static final class Scheduled {
        final SolverBench.World world;
        final SubTickScheduler scheduler;
        final SubTickScheduler.Settings settings = new SubTickScheduler.Settings();

        Scheduled(SolverBench.World world, LongSupplier clock) {
            this.world = world;
            this.scheduler = clock == null ? new SubTickScheduler() : new SubTickScheduler(clock);
        }

        void tick() {
            scheduler.plan(world.islands, settings);
            scheduler.prepare(world.islands, world.worldTick);
            scheduler.step(world.islands);
            ++world.worldTick;
            world.afterTick.run();
        }
    }

    /** Every counter of every island, as one comparable string. */
    static String fingerprint(SolverBench.World world) {
        var sb = new StringBuilder();
        for(var island : world.islands) {
            var s = island.solverStatistics();
            sb.append(s.solves).append(',').append(s.linearSolves).append(',').append(s.newtonSolves).append(',')
                    .append(s.newtonIterations).append(',').append(s.capHits).append(',').append(s.nonConverged).append(',')
                    .append(s.refactorizations).append(';');
                    // jacobianAdds is left out on purpose: it counts stamps in hash-set order, which moves
                    // with object identity and so differs between two identically built worlds. Since the
                    // Newton solve's step-tolerance extra-iteration search (see JavaMNA.Tuning.stepTolerance)
                    // was added, lineSearchProbes and residualBuilds are left out for the same reason: on a
                    // circuit whose convergence sits right at that boundary (seen on b_src_bridge@64 tick 4,
                    // only when other tests ran first in the same JVM and so built other objects first,
                    // shifting hash-set order upstream) one extra probe can be accepted or rejected by an
                    // ULP either way, with no effect on the iteration count, the solves, or the converged
                    // answer - SolverGoldenTest's tolerance-based check is what actually guards correctness.
        }
        return sb.toString();
    }

    private static final String[][] SCENARIOS = {
            // scenario id, rates at which the elements' own request equals the bench's rate
            { "a3_small", "8", "32" },
            { "a3_120", "16" },
            { "b_src_bridge", "16", "64" },
            { "e_xfmr_bank1", "32" },
            { "f_motors1", "16" },
            { "g_50_islands", "8" },
            { "i_fast_plus_50_slow", "16", "64" },
            { "d_arc", "32" },
    };

    // ------------------------------------------------------------------ nothing changes when nothing is switched on

    @Test
    void withEverythingOffTheSchedulerStepsExactlyLikeTheOriginalLoop() {
        for(var row : SCENARIOS) {
            for(int i = 1; i < row.length; ++i) {
                var rate = Integer.parseInt(row[i]);
                var reference = SolverBench.scenario(row[0]).build().apply(rate);
                var scheduled = new Scheduled(SolverBench.scenario(row[0]).build().apply(rate), null);
                scheduled.scheduler.conservativeLockstep = true;
                scheduled.settings.ceiling = 128;

                for(int t = 0; t < 12; ++t) {
                    reference.tick();
                    scheduled.tick();
                    for(int k = 0; k < reference.islands.size(); ++k) {
                        Assertions.assertEquals(reference.islands.get(k).getSubTicks(), scheduled.world.islands.get(k).getSubTicks(),
                                row[0] + "@" + rate + " island " + k + " rate at tick " + t);
                    }
                    Assertions.assertEquals(fingerprint(reference), fingerprint(scheduled.world), row[0] + "@" + rate + " tick " + t);
                }
                Assertions.assertEquals(reference.maxSubTicks, scheduled.scheduler.maxSubTicks());
            }
        }
    }

    @Test
    void aGovernorThatIsNotOverBudgetChangesNothingAtAll() {
        // A budget of a thousand seconds: the governor is on, times every step, and must not act.
        for(var row : SCENARIOS) {
            var rate = Integer.parseInt(row[1]);
            var off = new Scheduled(SolverBench.scenario(row[0]).build().apply(rate), null);
            var on = new Scheduled(SolverBench.scenario(row[0]).build().apply(rate), null);
            for(var s : List.of(off, on)) {
                s.scheduler.conservativeLockstep = true;
                s.settings.ceiling = 128;
            }
            on.settings.budgetMs = 1_000_000;
            for(int t = 0; t < 30; ++t) {
                off.tick();
                on.tick();
                Assertions.assertEquals(fingerprint(off.world), fingerprint(on.world), row[0] + " tick " + t);
            }
            Assertions.assertEquals(0, on.scheduler.governor().cappedUnits());
            Assertions.assertEquals(0, on.scheduler.governor().status().attacks());
        }
    }

    @Test
    void nodeVoltagesAreTheSameWithAndWithoutTheGovernor() {
        // Counters agreeing is strong evidence; the voltages themselves are the claim.
        var a = new double[2][];
        for(int pass = 0; pass < 2; ++pass) {
            var world = new SolverBench.World();
            var net = world.island(false, 1);
            var pos = net.N();
            var neg = SolverGolden.ground(net);
            var terminal = net.N();
            SolverGolden.acSource(net, terminal, null, 0.5, 325, 50).setSamplingPolicy(32, 64);
            net.network.addWire(SolverGolden.diode(terminal, pos));
            net.network.addWire(SolverGolden.diode(neg, terminal));
            SolverGolden.cap(net, 470e-6, 0.01, pos, neg);
            SolverGolden.res(net, 50, pos, neg);
            var scheduled = new Scheduled(world, null);
            scheduled.settings.ceiling = 64;
            scheduled.settings.budgetMs = pass == 0 ? 0 : 1_000_000;
            var trace = new double[40];
            for(int t = 0; t < 40; ++t) {
                scheduled.tick();
                trace[t] = pos.getVoltage();
            }
            a[pass] = trace;
        }
        // Two separately built worlds sum their stamps in hash-set order, which follows object
        // identity, so the last bits can differ for reasons that have nothing to do with the governor.
        // The Newton counters compared above are exact; the voltages are held to 1e-9 of a volt.
        Assertions.assertArrayEquals(a[0], a[1], 1e-9, "voltages must not depend on the governor");
        Assertions.assertTrue(a[0][39] > 100, "the rectifier should have charged its reservoir: " + a[0][39]);
    }

    // ------------------------------------------------------------------ the governor, on real islands, against a scripted clock

    /** A clock that advances by a fixed step every time it is read, so cost is proportional to reads. */
    static LongSupplier ticking(long stepNanos) {
        var now = new long[]{ 0 };
        return () -> now[0] += stepNanos;
    }

    @Test
    void anOverBudgetIslandIsCutToASteadyRateAboveItsFloor() {
        // b_src_bridge at 64: each timed step reads the clock twice, so 64 steps cost 64 * 2 * 100 us.
        var scheduled = new Scheduled(SolverBench.scenario("b_src_bridge").build().apply(64), ticking(100_000));
        scheduled.settings.ceiling = 64;
        scheduled.settings.budgetMs = 8;      // 12.8 ms at 64 steps; 8 ms allows about 40
        var island = scheduled.world.islands.get(0);
        var events = new ArrayList<SolveGovernor.Event>();
        scheduled.scheduler.governor().setListener(events::add);

        var before = scheduled.world.statistics().copy();
        scheduled.tick();
        Assertions.assertEquals(64, island.getSubTicks());
        Assertions.assertEquals(64, scheduled.world.statistics().minus(before).solves);
        for(int t = 0; t < 60; ++t)
            scheduled.tick();
        var settled = island.getSubTicks();
        // 50 Hz at 32 samples per cycle and a floor of 8 per cycle: 64 / 4 = 16.
        Assertions.assertTrue(settled < 64 && settled >= 16, "settled at " + settled);
        var mark = scheduled.world.statistics().copy();
        for(int t = 0; t < 20; ++t)
            scheduled.tick();
        Assertions.assertEquals(20L * settled, scheduled.world.statistics().minus(mark).solves, "one solve per sub-tick at the settled rate");
        Assertions.assertEquals(settled, island.getSubTicks(), "the rate must hold");
        Assertions.assertEquals(1, events.stream().filter(e -> e.kind() == SolveGovernor.EventKind.ENGAGED).count());
        Assertions.assertEquals(island.size(), events.get(0).nodes(), "the log line names the island by its size");
    }

    @Test
    void theGovernorNeverGoesBelowTheFloorAtEightSamplesPerCycle() {
        var scheduled = new Scheduled(SolverBench.scenario("a3_small").build().apply(128), ticking(100_000));
        scheduled.settings.ceiling = 128;
        scheduled.settings.budgetMs = 0.001;      // impossible
        for(int t = 0; t < 200; ++t)
            scheduled.tick();
        // 50 Hz needs 128 at 32 samples per cycle; eight per cycle is a quarter of that.
        Assertions.assertEquals(32, scheduled.world.islands.get(0).getSubTicks());
        var status = scheduled.scheduler.governor().status();
        Assertions.assertTrue(status.stuck());
    }

    @Test
    void theFloorNeverDropsBelowTheConfiguredMultiTicks() {
        var scheduled = new Scheduled(SolverBench.scenario("a3_small").build().apply(128), ticking(100_000));
        scheduled.settings.ceiling = 128;
        scheduled.settings.multiTicks = 48;
        scheduled.settings.budgetMs = 0.001;
        for(int t = 0; t < 200; ++t)
            scheduled.tick();
        Assertions.assertTrue(scheduled.world.islands.get(0).getSubTicks() >= 48);
    }

    @Test
    void islandsAtOneSubTickAreNotTimedIndividuallyButStillCountInTheTotal() {
        var reads = new long[]{ 0 };
        LongSupplier counting = () -> ++reads[0];
        var scheduled = new Scheduled(SolverBench.scenario("i_50_slow_only").build().apply(1), counting);
        scheduled.settings.budgetMs = 10;
        scheduled.tick();
        // Only the whole-tick bracket: one read to start it, one to end it. No island was timed.
        Assertions.assertEquals(2, reads[0]);
    }

    // ------------------------------------------------------------------ rates that are not powers of two

    @Test
    void fineRatesGiveEightyForFiftyHertzWherePowersOfTwoGiveOneTwentyEight() {
        for(boolean fine : new boolean[]{ false, true }) {
            var world = SolverBench.scenario("a3_small").build().apply(128);
            var scheduled = new Scheduled(world, null);
            scheduled.settings.ceiling = 128;
            scheduled.settings.fineRates = fine;
            var before = world.statistics().copy();
            scheduled.tick();
            var island = world.islands.get(0);
            var expected = fine ? 80 : 128;
            Assertions.assertEquals(expected, island.getSubTicks());
            Assertions.assertEquals(expected, world.statistics().minus(before).solves);
            Assertions.assertEquals(0.05f / expected, island.getDeltaTime(), 0, "dt is the tick divided by the rate");
        }
    }

    @Test
    void anIslandStepsExactlyItsRateOfTimesWhateverItsNeighboursRunAt() {
        // Eleven islands at rungs that do not divide one another (3, 5, 7, 10, 14, 20, 28, 40, 56, 80, 112).
        var rungs = new int[]{ 3, 5, 7, 10, 14, 20, 28, 40, 56, 80, 112 };
        var world = new SolverBench.World();
        var sources = new ArrayList<org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling>();
        for(var rung : rungs) {
            var net = world.island(true, 1);
            var node = net.N();
            // Frequency chosen so that exactly `rung` samples per tick are wanted at 32 samples per cycle.
            var source = SolverGolden.acSource(net, node, null, 0.1, 10, rung / (32 * 0.05));
            source.setSamplingPolicy(32, 128);
            SolverGolden.res(net, 10, node, null);
            sources.add(source);
        }
        var scheduled = new Scheduled(world, null);
        scheduled.settings.ceiling = 128;
        scheduled.settings.fineRates = true;
        scheduled.tick();
        for(int k = 0; k < rungs.length; ++k)
            Assertions.assertEquals(rungs[k], world.islands.get(k).getSubTicks(), "rung " + rungs[k]);
        var before = new long[rungs.length];
        for(int k = 0; k < rungs.length; ++k)
            before[k] = world.islands.get(k).solverStatistics().solves;
        for(int t = 0; t < 7; ++t)
            scheduled.tick();
        for(int k = 0; k < rungs.length; ++k) {
            Assertions.assertEquals(7L * rungs[k], world.islands.get(k).solverStatistics().solves - before[k],
                    "island at " + rungs[k] + " must step exactly that many times per tick");
        }
    }

    @Test
    void theRateFollowsADriftingFrequencyWithoutFlappingAtARungBoundary() {
        var world = new SolverBench.World();
        var net = world.island(true, 1);
        var node = net.N();
        var source = SolverGolden.acSource(net, node, null, 0.1, 10, 50);
        source.setSamplingPolicy(32, 128);
        SolverGolden.res(net, 10, node, null);
        var scheduled = new Scheduled(world, null);
        scheduled.settings.ceiling = 128;
        scheduled.settings.fineRates = true;
        var changes = 0;
        var last = -1;
        // 50 Hz wants exactly 80, a rung. Wobble the frequency by 0.4% across that boundary.
        for(int t = 0; t < 600; ++t) {
            source.setFrequency(50 + 0.2 * Math.sin(t * 0.9));
            scheduled.tick();
            var rate = world.islands.get(0).getSubTicks();
            if(rate != last)
                ++changes;
            last = rate;
        }
        Assertions.assertTrue(changes <= 2, "the rate changed " + changes + " times");
    }

    // ------------------------------------------------------------------ lockstep

    /** Two islands joined by a transmission line: {@code a} carries a port and so does {@code b}. */
    private static void link(SolverBench.World world, TestHelper.Network a, IElectricNode nodeA,
                             TestHelper.Network b, IElectricNode nodeB) {
        var portA = new TransmissionLinePort(nodeA, 0.5f, null);
        var portB = new TransmissionLinePort(nodeB, 0.5f, null);
        portA.other = portB;
        portB.other = portA;
        a.network.addNode(portA);
        b.network.addNode(portB);
    }

    private static TestHelper.Network dcIsland(SolverBench.World world, IElectricNode[] nodeOut) {
        var net = world.island(true, 1);
        var source = net.V(12f);
        var node = net.N();
        SolverGolden.res(net, 1, source, node);
        SolverGolden.res(net, 10, node, null);
        nodeOut[0] = node;
        return net;
    }

    @Test
    void onlyTheIslandsOnALineAreDraggedToTheFastRateAndOnlyTheOnesItReaches() {
        var world = new SolverBench.World();
        // Island 0: a 50 Hz machine. Islands 1-2: DC joined to each other by a line. Islands 3-4: DC joined to
        // each other. Island 5: DC on its own. Island 6: DC joined to the machine.
        var machine = world.island(true, 1);
        var neutral = SolverGolden.ground(machine);
        var terminal = machine.N();
        SolverGolden.acSource(machine, terminal, null, 0.1, 10, 50).setSamplingPolicy(32, 128);
        SolverGolden.res(machine, 10, terminal, neutral);
        var out = new IElectricNode[1][1];
        var nodes = new IElectricNode[7];
        var dc = new TestHelper.Network[7];
        for(int i = 1; i <= 6; ++i) {
            var holder = new IElectricNode[1];
            dc[i] = dcIsland(world, holder);
            nodes[i] = holder[0];
        }
        link(world, dc[1], nodes[1], dc[2], nodes[2]);
        link(world, dc[3], nodes[3], dc[4], nodes[4]);
        var lineToMachine = machine.N();
        link(world, machine, lineToMachine, dc[6], nodes[6]);

        var grouped = new Scheduled(world, null);
        grouped.settings.ceiling = 128;
        grouped.scheduler.plan(world.islands, grouped.settings);
        Assertions.assertEquals(128, world.islands.get(0).getSubTicks());
        Assertions.assertEquals(128, world.islands.get(6).getSubTicks(), "joined to the machine by a line");
        for(int i : new int[]{ 1, 2, 3, 4, 5 })
            Assertions.assertEquals(1, world.islands.get(i).getSubTicks(), "island " + i + " has no line to anything fast");

        // The original rule, for comparison: every island with a port goes to the fastest rate.
        grouped.scheduler.conservativeLockstep = true;
        grouped.scheduler.plan(world.islands, grouped.settings);
        for(int i : new int[]{ 0, 1, 2, 3, 4, 6 })
            Assertions.assertEquals(128, world.islands.get(i).getSubTicks(), "island " + i + " under the original rule");
        Assertions.assertEquals(1, world.islands.get(5).getSubTicks());
    }

    @Test
    void aChainOfLinesStepsAsOneGroup() {
        var world = new SolverBench.World();
        var machine = world.island(true, 1);
        var neutral = SolverGolden.ground(machine);
        var terminal = machine.N();
        SolverGolden.acSource(machine, terminal, null, 0.1, 10, 50).setSamplingPolicy(32, 128);
        SolverGolden.res(machine, 10, terminal, neutral);
        var holders = new IElectricNode[3][1];
        var dc = new TestHelper.Network[3];
        for(int i = 0; i < 3; ++i)
            dc[i] = dcIsland(world, holders[i]);
        // machine - dc0 = dc1 = dc2, each end of each line on its own node.
        var t0 = machine.N();
        link(world, machine, t0, dc[0], holders[0][0]);
        var a1 = dc[0].N();
        var b1 = dc[1].N();
        link(world, dc[0], a1, dc[1], b1);
        var a2 = dc[1].N();
        var b2 = dc[2].N();
        link(world, dc[1], a2, dc[2], b2);

        var scheduled = new Scheduled(world, null);
        scheduled.settings.ceiling = 128;
        scheduled.scheduler.plan(world.islands, scheduled.settings);
        for(int i = 0; i < 4; ++i)
            Assertions.assertEquals(128, world.islands.get(i).getSubTicks(), "island " + i);
    }

    @Test
    void anElementThatCannotNameItsPartnerFallsBackToTheFastestRateInTheWorld() {
        var world = new SolverBench.World();
        var machine = world.island(true, 1);
        var neutral = SolverGolden.ground(machine);
        var terminal = machine.N();
        SolverGolden.acSource(machine, terminal, null, 0.1, 10, 50).setSamplingPolicy(32, 128);
        SolverGolden.res(machine, 10, terminal, neutral);
        var holder = new IElectricNode[1];
        var orphan = dcIsland(world, holder);
        var port = new TransmissionLinePort(holder[0], 0.5f, null);     // other == null: no partner to name
        orphan.network.addNode(port);
        var bystander = dcIsland(world, new IElectricNode[1]);

        var scheduled = new Scheduled(world, null);
        scheduled.settings.ceiling = 128;
        scheduled.scheduler.plan(world.islands, scheduled.settings);
        Assertions.assertEquals(128, world.islands.get(1).getSubTicks(), "the original rule applies to it");
        Assertions.assertEquals(1, world.islands.get(2).getSubTicks(), "and to nothing else");
    }

    @Test
    void aLineBetweenTwoSlowIslandsKeepsItsOwnRateNotTheWorlds() {
        // With the original rule the delay of a line depended on whether an alternator was running
        // somewhere else in the world. With groups it is a property of the line's own islands.
        var world = new SolverBench.World();
        var machine = world.island(true, 1);
        var neutral = SolverGolden.ground(machine);
        var terminal = machine.N();
        SolverGolden.acSource(machine, terminal, null, 0.1, 10, 50).setSamplingPolicy(32, 128);
        SolverGolden.res(machine, 10, terminal, neutral);
        var h1 = new IElectricNode[1];
        var h2 = new IElectricNode[1];
        var one = dcIsland(world, h1);
        var two = dcIsland(world, h2);
        link(world, one, h1[0], two, h2[0]);

        var scheduled = new Scheduled(world, null);
        scheduled.settings.ceiling = 128;
        scheduled.tick();
        Assertions.assertEquals(1, world.islands.get(1).getSubTicks());
        Assertions.assertEquals(1, world.islands.get(2).getSubTicks());
        // The pair still solves together, once per tick each.
        var stats = new JavaMNA.Statistics[]{ world.islands.get(1).solverStatistics(), world.islands.get(2).solverStatistics() };
        Assertions.assertEquals(stats[0].solves, stats[1].solves);
    }
}
