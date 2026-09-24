package org.patryk3211.electricity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.JavaMNA;
import org.patryk3211.powergrid.electricity.sim.solver.JavaMNA.Tuning;
import org.patryk3211.powergrid.electricity.sim.special.PNJunctionWire;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The nonlinear (Newton) path against the path it replaced.
 * <p>
 * Everything the fast path adds can be switched off ({@link Tuning#legacy()} and
 * {@link PNJunctionWire#legacyLimiter}), which gives back the solver of the base commit. These tests
 * run old and new on the same circuits and say where they may differ: nowhere, except on circuits
 * where the old one did not converge, which is where its answer is not an answer.
 */
public class NewtonSolverTest {
    @AfterEach
    void restoreShippedSwitches() {
        shipped();
        criterion = 0;
        SolverGolden.Mutation.reset();
    }

    static void original() {
        Tuning.legacy();
        PNJunctionWire.legacyLimiter = true;
    }

    static void shipped() {
        Tuning.shipped();
        PNJunctionWire.legacyLimiter = false;
    }

    // ------------------------------------------------------------------ the recorded data

    /**
     * Three golden files were regenerated because the original solver had not converged when they
     * were recorded. The originals are kept under golden/legacy: the original solver, switched back
     * on, must still reproduce them, or the switches no longer mean what they say.
     */
    @Test
    void theOriginalSolverStillReproducesTheOriginalRecordings() throws Exception {
        original();
        for(var name : List.of("ill_rectifier_capacitive_only", "ill_rect_3ph_alternator", "ill_rect_3ph_floating_1e6")) {
            var current = SolverGolden.circuit(name);
            // As recorded: the two alternator circuits were held to derived statistics at 5 percent.
            var recorded = name.equals("ill_rectifier_capacitive_only") ? current
                    : new SolverGolden.Circuit(name, current.description(), current.ticks(), current.subTicks(),
                    current.stride(), false, SolverGolden.Tolerance.nonlinear(5e-2), current.build());
            var report = SolverGolden.compare(SolverGolden.run(recorded), SolverGolden.readOriginal(name));
            Assertions.assertTrue(report.ok(), report.summary());
        }
    }

    /**
     * The claim that justifies regenerating those files: they were recorded from solves the solver
     * itself called unconverged, and the shipped solver converges every one.
     */
    @Test
    void theRegeneratedCircuitsConvergeOnEverySolveWhereTheOriginalDidNot() {
        for(var name : List.of("ill_rectifier_capacitive_only", "ill_rect_3ph_alternator", "ill_rect_3ph_floating_1e6")) {
            var circuit = SolverGolden.circuit(name);
            original();
            var before = SolverGolden.run(circuit);
            shipped();
            var after = SolverGolden.run(circuit);
            Assertions.assertTrue(before.nonConvergedMessages > 0, name + ": the original solver converged, so nothing was wrong with its recording");
            Assertions.assertEquals(0, after.nonConvergedMessages, name + " still has unconverged solves");
            Assertions.assertEquals(circuit.ticks() * circuit.subTicks(), after.convergedMessages, name);
        }
    }

    // ------------------------------------------------------------------ randomised differential test

    /** What a run of one random circuit leaves behind. */
    record Trace(double[][] volts, double weakestLeak, long solves, long nonConverged, long capHits, long compensated, long fallbacks) {
        double peak() {
            double peak = 1;
            for(var row : volts)
                for(var v : row)
                    peak = Math.max(peak, Math.abs(v));
            return peak;
        }

        /** Largest difference to {@code other} in volts. */
        double deviation(Trace other) {
            double worst = 0;
            for(int t = 0; t < volts.length; ++t)
                for(int n = 0; n < volts[t].length; ++n)
                    worst = Math.max(worst, Math.abs(volts[t][n] - other.volts[t][n]));
            return worst;
        }

        /**
         * How far two solutions that both stop at the Newton criterion may lie apart on this circuit.
         * <p>
         * A node is only ever pinned to the accuracy of the stopping criterion divided by the
         * conductance that holds it: 1e-7 A across 1e-6 S is 0.1 V. That is a property of the
         * criterion, not of either solver, so it is what the bound is made of, with a margin of four
         * and a floor of 1e-5 of the peak for rounding.
         */
        double allowedDeviation(Trace other) {
            return 1e-5 * Math.max(peak(), other.peak()) + 4 * 1e-7 / Math.min(weakestLeak, other.weakestLeak);
        }
    }

    /** When positive, the Newton absolute criterion the random islands run with (shipped: 1e-7). */
    static double criterion = 0;

    private static double logUniform(Random random, double low, double high) {
        return Math.exp(Math.log(low) + random.nextDouble() * (Math.log(high) - Math.log(low)));
    }

    /**
     * A random island of resistors, capacitors and diodes on one or two AC sources.
     * <p>
     * {@code ill} widens the resistors to eleven decades and lets nodes float behind a megaohm, which
     * is what makes the DC level of a rectifier depend on leakage currents. The same seed builds the
     * same circuit every time, so old and new see identical input.
     */
    static Trace run(long seed, boolean ill, int ticks, int subTicks) {
        var random = new Random(seed);
        var net = new TestHelper.Network(true);
        if(criterion > 0) {
            // Same call the golden harness makes for its tight reference runs.
            net.network.setPrecision(criterion, 1e-14, 1e-6, 0.99);
            Tuning.stepTolerance = 0;
        }
        var ground = SolverGolden.ground(net);
        int count = 4 + random.nextInt(ill ? 8 : 5);
        var nodes = new ArrayList<FloatingNode>();
        for(int i = 0; i < count; ++i)
            nodes.add(net.N());

        int sources = 1 + random.nextInt(2);
        for(int s = 0; s < sources; ++s) {
            var positive = nodes.get(random.nextInt(count));
            var negative = random.nextBoolean() ? null : nodes.get(random.nextInt(count));
            if(negative == positive)
                negative = null;
            SolverGolden.acSource(net, positive, negative, logUniform(random, 0.02, 2), logUniform(random, 5, 400),
                    5 + random.nextInt(56));
        }

        double lowR = ill ? 1e-2 : 1, highR = ill ? 1e7 : 1e5;
        int elements = count + random.nextInt(count + 4);
        int diodes = 0;
        for(int e = 0; e < elements || diodes < 1; ++e) {
            var a = nodes.get(random.nextInt(count));
            IElectricNode b = random.nextInt(4) == 0 ? ground : nodes.get(random.nextInt(count));
            if(b == a)
                continue;
            switch(random.nextInt(3)) {
                case 0 -> SolverGolden.res(net, logUniform(random, lowR, highR), a, b);
                case 1 -> SolverGolden.cap(net, logUniform(random, 1e-7, 1e-3), logUniform(random, 0.01, 1), a, b);
                default -> {
                    net.network.addWire(random.nextBoolean() ? SolverGolden.diode(a, b) : SolverGolden.diode(b, a));
                    ++diodes;
                }
            }
        }
        // Every node is held to ground by something. A node held only by the 1e-8 siemens the solver
        // adds is pinned to 10 volts by a 1e-7 amp criterion, and old and new then disagree about
        // nothing but where in that band they stopped (seed 49: a diode cathode with a 1 uA offset
        // current settles anywhere between 15 V and 111 V, and neither is wrong to 1e-7 A).
        double weakest = 1e-3;
        double leakLow = ill ? 1e4 : 1e3, leakHigh = ill ? 1e7 : 1e5;
        for(int i = 0; i < count; ++i) {
            double rg = logUniform(random, leakLow, leakHigh);
            SolverGolden.res(net, rg, nodes.get(i), ground);
            weakest = Math.min(weakest, 1 / rg);
        }

        var volts = new double[ticks * subTicks][count];
        var original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            int at = 0;
            for(int t = 0; t < ticks; ++t) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s, ++at) {
                    net.network.singleTick();
                    for(int n = 0; n < count; ++n)
                        volts[at][n] = nodes.get(n).getVoltage();
                }
            }
        } finally {
            System.setOut(original);
        }
        var stats = net.network.solverStatistics();
        return new Trace(volts, weakest, stats.solves, stats.nonConverged, stats.capHits, stats.compensatedSolves, stats.compensationFallbacks);
    }

    /**
     * Old and new solve the same random circuits sub-tick by sub-tick.
     * <p>
     * Both are held to the same standard, agreement to a fraction of the peak voltage, but only on
     * draws where both converged on every solve without reaching the iteration cap: an unconverged
     * solve is a state that satisfies no circuit equation and there is nothing to agree with. On a
     * floating node the original solver can wander (seed 38 of the ordinary draws: the voltage of
     * one node moves by 23 V when only the stopping criterion is tightened from 1e-7 to 1e-12, and
     * it hits the cap 44 times), while the shipped one returns the same 420.696 V at both criteria.
     * Those draws are counted, and the new solver must have at most as many of them as the old one.
     */
    private void differential(String label, boolean ill, int draws, Runnable reference) {
        int compared = 0, oldFailed = 0, newFailed = 0, usedUpdate = 0, regressions = 0;
        double worst = 0;
        long worstSeed = -1;
        var failures = new ArrayList<String>();
        for(long seed = 1; seed <= draws; ++seed) {
            reference.run();
            var before = run(seed, ill, 3, 32);
            shipped();
            var after = run(seed, ill, 3, 32);
            // Reaching the iteration cap counts as failing even when the last residual was small enough
            // to pass: a state the iteration wandered into is not a fixed point (see the seed 38 note).
            boolean oldBad = before.nonConverged > 0 || before.capHits > 0;
            boolean newBad = after.nonConverged > 0 || after.capHits > 0;
            if(oldBad)
                ++oldFailed;
            if(newBad)
                ++newFailed;
            if(after.compensated > 0)
                ++usedUpdate;
            if(newBad && !oldBad)
                ++regressions;
            if(oldBad || newBad)
                continue;
            ++compared;
            double dev = before.deviation(after), allowed = before.allowedDeviation(after);
            if(dev / allowed > worst) {
                worst = dev / allowed;
                worstSeed = seed;
            }
            if(dev > allowed)
                failures.add("seed " + seed + " differs by " + dev + " V, allowed " + allowed);
        }
        System.out.printf("differential %-8s %d draws, %d compared, worst deviation %.2f of what the criterion allows (seed %d); "
                        + "unconverged draws: reference %d, shipped %d, of which the reference converged on %d; "
                        + "draws that used the low-rank update: %d%n",
                label, draws, compared, worst, worstSeed, oldFailed, newFailed, regressions, usedUpdate);
        Assertions.assertTrue(failures.isEmpty(), label + ": " + failures.subList(0, Math.min(5, failures.size())));
        Assertions.assertTrue(compared >= draws / 2, label + ": only " + compared + " of " + draws + " draws were comparable");
        Assertions.assertTrue(newFailed <= oldFailed, label + ": the shipped solver failed to converge on more draws than the reference");
        // The junction limiter alone left 16 of 200 draws hitting the cap that the original converged
        // on (a cycle of three residuals on a chain of diodes); the fallback after 40 iterations brings
        // that to 0 (ordinary) and 1 (ill, seed 199). More than two means the fallback stopped working.
        Assertions.assertTrue(regressions <= 2, label + ": the shipped solver failed to converge on " + regressions
                + " draws the reference converged on");
        Assertions.assertTrue(usedUpdate >= draws / 4, label + ": the low-rank update ran on only " + usedUpdate + " draws, so this compared nothing");
    }

    @Test
    void randomWellConditionedCircuitsAgreeWithTheOriginalSolver() {
        differential("ordinary", false, 200, NewtonSolverTest::original);
    }

    /**
     * On ill-conditioned draws the reference is the original solver with one defect corrected: the
     * conductance changes below 0.1 * G_MIN that {@code updateConductance} dropped, which leave the
     * matrix different from the linearisation an element believes it stamped. Against the uncorrected
     * original the answers differ by more than the criterion allows on 2 of 200 draws (seeds 118 and
     * 181: 10.4 V and 6.8 V), and against the corrected one they agree to 0.2 V, so the corrected one
     * is the reference that isolates everything else this series changed.
     */
    @Test
    void randomIllConditionedCircuitsAgreeWithTheOriginalSolverWithItsDroppedUpdatesRestored() {
        differential("ill", true, 200, () -> {
            original();
            Tuning.exactHookUpdates = true;
        });
    }

    /** The low-rank update alone, on and off, with everything else as shipped. */
    @Test
    void theLowRankUpdateAgreesWithRefactoringOnRandomCircuits() {
        differential("update", true, 200, () -> {
            shipped();
            Tuning.compensation = false;
        });
        differential("update", false, 200, () -> {
            shipped();
            Tuning.compensation = false;
        });
    }

    // ------------------------------------------------------------------ the fallbacks

    /** A diode bridge on a mesh: big enough for the low-rank update to be in play. */
    private static JavaMNA.Statistics meshStatistics(int diodes) {
        var world = SolverBench.mesh(16, 10, 10, diodes, false);
        var original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            for(int t = 0; t < 4; ++t)
                world.tick();
            return world.statistics();
        } finally {
            System.setOut(original);
        }
    }

    private static double meshCheckSum(int diodes) {
        var world = SolverBench.mesh(16, 10, 10, diodes, false);
        var original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            double sum = 0;
            for(int t = 0; t < 3; ++t) {
                world.tick();
                for(var island : world.islands)
                    for(var node : island.getNodes())
                        sum += island.getValue(node);
            }
            return sum;
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void anIslandTouchingTooManyNodesFallsBackToRefactoringAndGivesTheSameAnswer() {
        shipped();
        var used = meshStatistics(3);
        Assertions.assertTrue(used.compensatedSolves > 0, "the update was not used on a 100 node mesh with three diodes");
        double with = meshCheckSum(3);

        Tuning.maxTouchedNodes = 2;
        var declined = meshStatistics(3);
        Assertions.assertEquals(0, declined.compensatedSolves, "touched nodes over the limit must not use the update");
        Assertions.assertTrue(declined.compensationFallbacks > 0, "the refusal was not counted");
        Assertions.assertTrue(declined.refactorizations > used.refactorizations * 5, "the fallback must refactor, as the original did");
        Assertions.assertEquals(with, meshCheckSum(3), 1e-6 * Math.max(1, Math.abs(with)), "the fallback changed the answer");
    }

    @Test
    void islandsBelowTheSparseSizeDoNotUseTheUpdate() {
        var result = SolverBench.measure(SolverBench.scenario("c_halfwave"), 8, SolverBench.Config.QUICK);
        Assertions.assertTrue(result.newtonSolvesPerTick() > 0);
        var world = SolverBench.scenario("c_halfwave").build().apply(8);
        var original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            for(int t = 0; t < 3; ++t)
                world.tick();
        } finally {
            System.setOut(original);
        }
        Assertions.assertEquals(0, world.statistics().compensatedSolves, "a 3 node island is dense, the update only costs there");
    }

    // ------------------------------------------------------------------ the diagnostics keep their meaning

    /**
     * Whatever else changed, an island that runs out of iterations must still say so: the counter,
     * the unconverged count and the flag the rest of the mod reads.
     */
    @Test
    void anIslandThatRunsOutOfIterationsStillReportsIt() {
        shipped();
        var net = new TestHelper.Network(true);
        var ground = SolverGolden.ground(net);
        var hot = net.N();
        SolverGolden.acSource(net, hot, null, 0.5, 325, 50);
        var out = net.N();
        net.network.addWire(SolverGolden.diode(hot, out));
        SolverGolden.cap(net, 470e-6, 0.01, out, ground);
        SolverGolden.res(net, 100, out, ground);
        net.network.maxIterations = b -> 1;
        var original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            for(int t = 0; t < 2; ++t) {
                net.network.prepare(32);
                for(int s = 0; s < 32; ++s)
                    net.network.singleTick();
            }
        } finally {
            System.setOut(original);
        }
        var stats = net.network.solverStatistics();
        Assertions.assertTrue(stats.capHits > 0, "reaching the iteration limit was not counted");
        Assertions.assertTrue(stats.nonConverged > 0, "an unconverged solve was not counted");
        Assertions.assertFalse(net.network.isConverged(), "the island still claims to have converged");
    }
}
