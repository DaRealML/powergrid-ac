package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

/**
 * Smoke tests for {@link SolverBench}: that every scenario builds and steps, and that the counters
 * it reports mean what its documentation says. The figures themselves are not asserted; timing on a
 * shared machine is not a test.
 */
public class SolverBenchTest {
    @Test
    void everyScenarioBuildsAndSteps() {
        // Rate 1 keeps the nonlinear scenarios cheap: one solve per tick, however slow the island.
        for(var scenario : SolverBench.SCENARIOS) {
            var result = SolverBench.measure(scenario, 1, SolverBench.Config.QUICK);
            Assertions.assertTrue(result.medianMs() > 0 && Double.isFinite(result.medianMs()), scenario.id());
            Assertions.assertTrue(result.solvesPerTick() >= 1, scenario.id() + " never solved");
        }
    }

    @Test
    void linearIslandsTakeTheFastPathAndCountOneSolvePerSubTick() {
        var result = SolverBench.measure(SolverBench.scenario("a3_small"), 8, SolverBench.Config.QUICK);
        Assertions.assertEquals(8, result.solvesPerTick(), 1e-9);
        Assertions.assertEquals(0, result.newtonSolvesPerTick(), 1e-9);
        Assertions.assertEquals(0, result.newtonIterationsPerNewtonSolve(), 1e-9);
        Assertions.assertEquals(0, result.refactorizationsPerTick(), 1e-9,
                "a linear island with nothing changing must not refactorise");
    }

    @Test
    void nonlinearIslandsRunNewtonAndAbsorbConductanceChanges() {
        var result = SolverBench.measure(SolverBench.scenario("c_halfwave"), 8, SolverBench.Config.QUICK);
        Assertions.assertEquals(8, result.solvesPerTick(), 1e-9);
        Assertions.assertEquals(8, result.newtonSolvesPerTick(), 1e-9);
        Assertions.assertTrue(result.newtonIterationsPerNewtonSolve() >= 1, "a diode needs at least one Newton step");
        // A diode changes its conductance on every iteration; the low-rank update absorbs that, so the
        // factors are rebuilt far less often than Newton iterates (they were once rebuilt on each one).
        Assertions.assertTrue(result.refactorizationsPerTick() < result.newtonIterationsPerNewtonSolve() * result.newtonSolvesPerTick() / 2,
                "a diode's conductance changes must not each cost a factorisation");
    }

    @Test
    void theSteppingLoopRunsEachIslandAtItsOwnRate() {
        // Fifty DC islands at one sub-tick beside a machine at 16: the machine solves 16 times a tick,
        // each slow island once, exactly as WorldNetworks.preTick schedules them.
        var world = SolverBench.scenario("i_fast_plus_50_slow").build().apply(16);
        var before = world.statistics().copy();
        for(int t = 0; t < 5; ++t)
            world.tick();
        var delta = world.statistics().minus(before);
        Assertions.assertEquals(5 * (16 + 50), delta.solves);
        Assertions.assertEquals(16, world.maxSubTicks);
        Assertions.assertEquals(16, world.islands.get(0).getSubTicks());
        Assertions.assertEquals(1, world.islands.get(1).getSubTicks());
    }

    @Test
    void statisticsSubtractionGivesPerIntervalCounts() {
        var world = SolverBench.scenario("b_src_bridge").build().apply(8);
        for(int t = 0; t < 3; ++t)
            world.tick();
        var a = world.statistics().copy();
        world.tick();
        var b = world.statistics().copy();
        var delta = b.minus(a);
        Assertions.assertEquals(8, delta.solves);
        Assertions.assertEquals(8, delta.newtonSolves);
        Assertions.assertTrue(delta.newtonIterations >= 8);
        // Every Newton update refactorises the matrix its diodes changed, bar the odd stale one.
        Assertions.assertTrue(delta.refactorizations > 0 && delta.refactorizations <= delta.linearSystemSolves);
        Assertions.assertTrue(delta.residualBuilds >= delta.newtonIterations);
    }

    @Test
    void anIslandWithoutSourcesIsCountedNowhereBecauseItIsNeverSolved() {
        // ElectricalNetwork.singleTick returns before reaching the solver when sourceCount is zero.
        var world = new SolverBench.World();
        var net = world.island(false, 4);
        var a = net.N();
        var b = net.N();
        net.W(10f, a, b);
        net.network.warmUp(-1);
        for(int t = 0; t < 3; ++t)
            world.tick();
        Assertions.assertEquals(0, world.statistics().solves);
        Assertions.assertNotNull(net.network.solverStatistics(), "the Java backend exposes counters");
        // A source makes it live.
        var source = new VoltageSourceCoupling(a, null, 0f, 5f);
        net.network.addNode(source);
        world.tick();
        Assertions.assertEquals(4, world.statistics().solves);
    }
}
