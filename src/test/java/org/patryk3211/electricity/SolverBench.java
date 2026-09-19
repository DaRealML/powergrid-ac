package org.patryk3211.electricity;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.JavaMNA;
import org.patryk3211.powergrid.electricity.sim.special.ArcWire;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Benchmarks and profiles the circuit solver on scenarios that stand in for what players build.
 *
 * <h2>Why counts come first</h2>
 * Wall-clock time on a development machine moves by a factor of two whenever another build starts,
 * so every {@link Result} carries the solver's own counters next to the timing: Newton iterations
 * per Newton solve, refactorisations per tick, solves per tick, the fraction of solves that ended
 * above the minimum precision. Those do not depend on what else the machine is doing, and they are
 * what to compare between two versions of the solver. Milliseconds are for ratios measured in the
 * same JVM run.
 *
 * <h2>How a world tick is reproduced</h2>
 * {@link World#tick()} copies the stepping loop of {@code WorldNetworks.preTick} (rate planning through
 * {@code computeSubTicks}, the lockstep pass, {@code prepare}, then {@code maxSubTicks} outer
 * iterations that step each island when {@code (i+1)*n/max > i*n/max}), without a Minecraft world.
 * It does not reproduce island discovery, probe samplers, or the sync packets in
 * {@code postTick}: see docs/PERFORMANCE.md for what the static audit says about those.
 *
 * <h2>What the timing function does</h2>
 * {@link #measure} warms the JIT (at least {@code warmupTicks} ticks and {@code warmupMillis} ms),
 * then times {@code repeats} blocks of ticks one tick at a time and returns the median over every
 * tick sample, the 10th and 90th percentile, the minimum, and how far the block medians disagree
 * with each other, which is the honest noise figure for that row.
 *
 * <h2>Console output</h2>
 * With no logger installed the solver prints a line for every converged nonlinear solve and every
 * non-converged one ({@code JavaMNA.verifyConvergence}). In the game a logger exists and the
 * converged line is skipped, so timing runs swallow {@code System.out} unless asked not to.
 * {@code -Dbench.console=true} keeps the output, which is how the cost of that printing was
 * measured.
 *
 * <p>Run from a prompt with the test classpath:
 * {@code java -cp ... org.patryk3211.electricity.SolverBench [scenarioFilter] [rates] [--quick] [--jfr]}.
 */
public final class SolverBench {
    private SolverBench() { }

    public static final int[] RATES = { 1, 8, 16, 32, 64, 128 };

    // ------------------------------------------------------------------ world

    /** Islands stepped the way {@code WorldNetworks.preTick} steps them. */
    public static final class World {
        public final List<ElectricalNetwork> islands = new ArrayList<>();
        /** Sub-ticks each island runs per world tick, parallel to {@link #islands}. */
        public final List<Integer> rates = new ArrayList<>();
        public Runnable afterTick = () -> { };
        public long worldTick = 1000;
        /** The outer loop's iteration count on the last tick: the largest island rate. */
        public int maxSubTicks = 1;

        public TestHelper.Network island(boolean addGMin, int rate) {
            var wrapper = new TestHelper.Network(addGMin);
            var network = SolverGolden.networkFactory.apply(addGMin);
            network.warmUp(-1);
            wrapper.network = network;
            islands.add(network);
            rates.add(rate);
            return wrapper;
        }

        /** Adopts every island of a {@link SolverGolden.Rig}, all at one rate. */
        public static World of(SolverGolden.Rig rig, int rate) {
            var world = new World();
            for(var network : rig.networks) {
                world.islands.add(network);
                world.rates.add(rate);
            }
            world.afterTick = rig.afterTick;
            return world;
        }

        /** One world tick. */
        public void tick() {
            // Rate planning, as WorldNetworks does every tick. The planned value is discarded in
            // favour of the scenario's rate so a scenario means the same thing on every machine and
            // config, but the cost of asking (a walk over each island's ISubTickRate providers) is real.
            int max = 1;
            for(int k = 0; k < islands.size(); ++k) {
                var network = islands.get(k);
                network.computeSubTicks(1);
                network.setSubTicks(rates.get(k));
                max = Math.max(max, network.getSubTicks());
            }
            if(max > 1) {
                for(var network : islands) {
                    if(network.requiresLockstep())
                        network.setSubTicks(max);
                }
            }
            maxSubTicks = max;

            for(var network : islands) {
                network.setWorldTick(worldTick);
                network.prepare(network.getSubTicks());
            }
            for(int i = 0; i < max; ++i) {
                for(var network : islands) {
                    var subTicks = network.getSubTicks();
                    if((i + 1) * subTicks / max > i * subTicks / max)
                        network.singleTick();
                }
            }
            ++worldTick;
            afterTick.run();
        }

        /** Total nodes over every island. */
        public int nodes() {
            var n = 0;
            for(var network : islands)
                n += network.size();
            return n;
        }

        /** Sum of the Java backend's counters over every island, as a copy. */
        public JavaMNA.Statistics statistics() {
            var sum = new JavaMNA.Statistics();
            for(var network : islands) {
                var stats = network.solverStatistics();
                if(stats != null)
                    sum.add(stats);
            }
            return sum;
        }
    }

    /** A named scenario: {@code build(rate)} makes a fresh world stepping at that many sub-ticks. */
    public record Scenario(String id, String group, String description, IntFunction<World> build) { }

    // ------------------------------------------------------------------ scenario builders

    private static SolverGolden.Shaft shaft(World world) {
        var shaft = new SolverGolden.Shaft(272);
        world.afterTick = shaft::advance;
        return shaft;
    }

    /** {@code windings} alternator windings on one shaft, each into a line of resistive nodes and a load. */
    static World linearAc(int rate, int windings, int segments) {
        var world = new World();
        var net = world.island(true, rate);
        var shaft = shaft(world);
        var neutral = SolverGolden.ground(net);
        for(int k = 0; k < windings; ++k) {
            var terminal = net.N();
            SolverGolden.winding(net, shaft, 360.0 * k / windings, 11, terminal, neutral)
                    .setSamplingPolicy(32, rate);
            IElectricNode previous = terminal;
            for(int s = 0; s < segments; ++s) {
                var node = net.N();
                SolverGolden.res(net, 0.05, previous, node);
                previous = node;
            }
            SolverGolden.res(net, 12.0 * windings, previous, neutral);
        }
        return world;
    }

    /**
     * Three 0.5 ohm AC sources, each into a line of {@code segments} resistive nodes, then a
     * six-diode bridge with reservoir and load. The healthy nonlinear case: Newton converges.
     */
    static World sourceBridge(int rate, int segments) {
        var world = new World();
        var net = world.island(false, rate);
        var pos = net.N();
        var neg = SolverGolden.ground(net);
        for(int k = 0; k < 3; ++k) {
            var terminal = net.N();
            var source = SolverGolden.acSource(net, terminal, null, 0.5, 325, 50);
            source.setPhaseOffset(Math.toRadians(-120 * k));
            source.setSamplingPolicy(32, rate);
            IElectricNode previous = terminal;
            for(int s = 0; s < segments; ++s) {
                var node = net.N();
                SolverGolden.res(net, 0.05, previous, node);
                previous = node;
            }
            net.network.addWire(SolverGolden.diode(previous, pos));
            net.network.addWire(SolverGolden.diode(neg, previous));
        }
        SolverGolden.cap(net, 470e-6, 0.01, pos, neg);
        SolverGolden.res(net, 50, pos, neg);
        return world;
    }

    /** One ArcWire on a 50 Hz supply: strikes and restrikes every half cycle. */
    static World arc50(int rate) {
        var world = new World();
        var net = world.island(true, rate);
        var gnd = SolverGolden.ground(net);
        var hot = net.N();
        SolverGolden.acSource(net, hot, null, 0.001, 3000, 50).setSamplingPolicy(32, rate);
        var mid = net.N();
        SolverGolden.res(net, 200, hot, mid);
        net.network.addWire(new ArcWire(30, 5000, 50, 3e6f, 0.002f, 0.0005f, mid, gnd));
        return world;
    }

    /** {@code banks} delta-star transformer banks in one island, each loaded, on a three-phase 50 Hz supply. */
    static World transformerBanks(int rate, int banks) {
        var world = new World();
        var net = world.island(false, rate);
        var neutral = SolverGolden.ground(net);
        var lines = new IElectricNode[3];
        for(int k = 0; k < 3; ++k) {
            lines[k] = net.N();
            var source = SolverGolden.acSource(net, lines[k], null, 0.001, 325, 50);
            source.setPhaseOffset(Math.toRadians(-120 * k));
            source.setSamplingPolicy(32, rate);
        }
        for(int b = 0; b < banks; ++b) {
            var out = new FloatingNode[]{ net.N(), net.N(), net.N() };
            var star = net.N();
            for(int k = 0; k < 3; ++k)
                SolverGolden.transformer(net, 10, 10, lines[k], lines[(k + 1) % 3], out[k], star);
            for(int k = 0; k < 3; ++k)
                SolverGolden.res(net, 40, out[k], star);
            // A reference for the floating secondary, as a real bank's load would provide.
            SolverGolden.res(net, 1e6, star, null);
        }
        return world;
    }

    /** {@code motors} LR coils (what InductorComponent builds) on one 50 Hz source. */
    static World motors(int rate, int motors) {
        var world = new World();
        var net = world.island(true, rate);
        var gnd = SolverGolden.ground(net);
        var hot = net.N();
        SolverGolden.acSource(net, hot, null, 0.05, 325, 50).setSamplingPolicy(32, rate);
        for(int m = 0; m < motors; ++m) {
            var a = net.N();
            SolverGolden.res(net, 1, hot, a);
            net.network.addWire(new LRSeriesWire(0.05, 2, a, gnd));
        }
        return world;
    }

    /** Fifty independent two-winding sources, each with its own load and its own island. */
    static World fiftyIslands(int rate) {
        var world = new World();
        var shaft = shaft(world);
        for(int i = 0; i < 50; ++i) {
            var net = world.island(true, rate);
            var neutral = SolverGolden.ground(net);
            for(int k = 0; k < 2; ++k) {
                var terminal = net.N();
                SolverGolden.winding(net, shaft, 90.0 * k, 11, terminal, neutral).setSamplingPolicy(32, rate);
                SolverGolden.res(net, 4, terminal, neutral);
            }
        }
        return world;
    }

    /**
     * One island of about 300 nodes: a 15 by 20 resistive mesh with a load on every node, fed by a
     * 50 Hz source at one corner; optionally three rectifier branches hanging off it.
     * <p>
     * {@code hub} chooses how the loads reach ground. False (the realistic case, and what
     * {@code ElectricWire} with a null terminal does) stamps only the node's own diagonal. True ties
     * every load to one explicit ground node, which puts a 300-entry row and column in the
     * admittance matrix: harmless to a linear island that factorises once, ruinous to a Newton island
     * whose sparse LU (natural ordering, partial pivoting) refactorises every iteration.
     */
    static World bigMesh(int rate, int diodes, boolean hub) {
        var world = new World();
        var net = world.island(false, rate);
        var gnd = SolverGolden.ground(net);
        final int rows = 15, cols = 20;
        var grid = new FloatingNode[rows * cols];
        for(int i = 0; i < grid.length; ++i)
            grid[i] = net.N();
        for(int r = 0; r < rows; ++r) {
            for(int c = 0; c < cols; ++c) {
                var node = grid[r * cols + c];
                if(c + 1 < cols)
                    SolverGolden.res(net, 0.5, node, grid[r * cols + c + 1]);
                if(r + 1 < rows)
                    SolverGolden.res(net, 0.5, node, grid[(r + 1) * cols + c]);
                SolverGolden.res(net, 200, node, hub ? gnd : null);
            }
        }
        var feed = net.N();
        SolverGolden.acSource(net, feed, null, 0.05, 325, 50).setSamplingPolicy(32, rate);
        SolverGolden.res(net, 0.1, feed, grid[0]);
        for(int d = 0; d < diodes; ++d) {
            var out = net.N();
            net.network.addWire(SolverGolden.diode(grid[(d + 1) * 83 % grid.length], out));
            SolverGolden.cap(net, 470e-6, 0.01, out, gnd);
            SolverGolden.res(net, 100, out, gnd);
        }
        return world;
    }

    /** A three-winding machine at {@code rate} beside {@code slow} DC islands that step once per tick. */
    static World fastBesideSlow(int rate, int slow) {
        var world = new World();
        var fast = world.island(true, rate);
        var shaft = shaft(world);
        var neutral = SolverGolden.ground(fast);
        for(int k = 0; k < 3; ++k) {
            var terminal = fast.N();
            SolverGolden.winding(fast, shaft, 120.0 * k, 11, terminal, neutral).setSamplingPolicy(32, rate);
            SolverGolden.res(fast, 12, terminal, neutral);
        }
        for(int i = 0; i < slow; ++i) {
            var net = world.island(true, 1);
            var source = net.V(12f);
            SolverGolden.res(net, 10, source, null);
        }
        return world;
    }

    /** Every scenario, in the order they are reported. Ids are stable; later agents refer to them. */
    public static final List<Scenario> SCENARIOS = List.of(
            new Scenario("a1_small", "a", "linear AC: 1 winding, small island", r -> linearAc(r, 1, 0)),
            new Scenario("a1_120", "a", "linear AC: 1 winding, ~120 nodes", r -> linearAc(r, 1, 120)),
            new Scenario("a3_small", "a", "linear AC: 3 windings, small island", r -> linearAc(r, 3, 0)),
            new Scenario("a3_120", "a", "linear AC: 3 windings, ~120 nodes (3 lines of 40)", r -> linearAc(r, 3, 40)),
            new Scenario("b_seed_floating", "b", "3 windings + six-diode bridge, DC side floating behind 1e6 ohm (the reported rig)",
                    r -> World.of(SolverGolden.rectifierThreePhaseFloating(), r)),
            new Scenario("b_alt_grounded", "b", "3 windings + six-diode bridge, DC side grounded",
                    r -> World.of(SolverGolden.rectifierThreePhaseAlternator(), r)),
            new Scenario("b_src_bridge", "b", "3 AC sources (0.5 ohm) + six-diode bridge: healthy Newton", r -> sourceBridge(r, 0)),
            new Scenario("b_src_bridge_120", "b", "same with 40 line nodes per phase: 6 diodes in ~130 nodes", r -> sourceBridge(r, 40)),
            new Scenario("c_halfwave", "c", "half-wave rectifier, reservoir and load", r -> World.of(SolverGolden.rectifierHalfWave(), r)),
            new Scenario("d_arc", "d", "one ArcWire on 50 Hz, striking every half cycle", SolverBench::arc50),
            new Scenario("e_xfmr_bank1", "e", "one three-phase transformer bank", r -> transformerBanks(r, 1)),
            new Scenario("e_xfmr_bank4", "e", "four three-phase banks in one island", r -> transformerBanks(r, 4)),
            new Scenario("f_motors1", "f", "one LR motor on 50 Hz", r -> motors(r, 1)),
            new Scenario("f_motors20", "f", "twenty LR motors on 50 Hz", r -> motors(r, 20)),
            new Scenario("g_50_islands", "g", "fifty independent two-winding islands", SolverBench::fiftyIslands),
            new Scenario("h_mesh300", "h", "one linear island of ~300 nodes", r -> bigMesh(r, 0, false)),
            new Scenario("h_mesh300_3diodes", "h", "the same with three diode branches", r -> bigMesh(r, 3, false)),
            new Scenario("h_mesh300_hub_3diodes", "h", "as above but every load tied to one ground node (a matrix hub)", r -> bigMesh(r, 3, true)),
            new Scenario("i_fast_alone", "i", "3-winding island at the rate, nothing else", r -> fastBesideSlow(r, 0)),
            new Scenario("i_fast_plus_50_slow", "i", "the same beside 50 DC islands stepped once per tick", r -> fastBesideSlow(r, 50)),
            new Scenario("i_50_slow_only", "i", "the 50 DC islands alone (rate argument ignored)", r -> fastBesideSlow(1, 50))
    );

    public static Scenario scenario(String id) {
        for(var s : SCENARIOS) {
            if(s.id().equals(id))
                return s;
        }
        throw new IllegalArgumentException("No scenario " + id);
    }

    // ------------------------------------------------------------------ measuring

    /** How long to warm and measure. {@link #QUICK} is for smoke tests, {@link #STANDARD} for figures. */
    public record Config(int warmupTicks, int warmupMillis, int repeats, int blockMillis, int minBlockTicks,
                         int maxRowMillis, boolean console) {
        /** {@code maxRowMillis} bounds warm-up plus measurement, so a scenario costing seconds per tick still finishes. */
        public static final Config STANDARD = new Config(20, 400, 5, 150, 8, 12_000, false);
        public static final Config QUICK = new Config(3, 0, 2, 0, 2, 2_000, false);

        public Config withConsole(boolean console) {
            return new Config(warmupTicks, warmupMillis, repeats, blockMillis, minBlockTicks, maxRowMillis, console);
        }
    }

    /** One row of the report: a scenario at a rate. Times are milliseconds per world tick. */
    public record Result(String scenario, int rate, int islands, int nodes,
                         double medianMs, double p10Ms, double p90Ms, double minMs, double repeatSpreadPercent,
                         double solvesPerTick, double newtonSolvesPerTick, double newtonIterationsPerNewtonSolve,
                         double refactorizationsPerTick, double residualBuildsPerTick, double lineSearchProbesPerTick,
                         double nonConvergedPercent, double capHitsPerTick, double jacobianAddsPerTick,
                         double jacobianRebuildsPerTick, int ticksMeasured) {
        public static String header() {
            return String.format(Locale.ROOT, "%-20s %5s %4s %5s | %9s %9s %9s %6s | %8s %7s %6s %7s %8s %6s",
                    "scenario", "rate", "isl", "nodes", "median ms", "p10", "p90", "noise%", "solves/t", "newt/t", "it/sol",
                    "refac/t", "nonconv%", "cap/t");
        }

        public String row() {
            return String.format(Locale.ROOT, "%-20s %5d %4d %5d | %9.4f %9.4f %9.4f %6.1f | %8.1f %7.1f %6.1f %7.1f %8.1f %6.1f",
                    scenario, rate, islands, nodes, medianMs, p10Ms, p90Ms, repeatSpreadPercent, solvesPerTick,
                    newtonSolvesPerTick, newtonIterationsPerNewtonSolve, refactorizationsPerTick, nonConvergedPercent,
                    capHitsPerTick);
        }
    }

    private static final PrintStream DISCARD = new PrintStream(OutputStream.nullOutputStream());

    private static double percentile(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1) + 0.5))];
    }

    /** Warms, then times {@code scenario} at {@code rate}. The scenario is built fresh. */
    public static Result measure(Scenario scenario, int rate, Config config) {
        var original = System.out;
        if(!config.console())
            System.setOut(DISCARD);
        try {
            return measureQuietly(scenario, rate, config);
        } finally {
            System.setOut(original);
        }
    }

    private static Result measureQuietly(Scenario scenario, int rate, Config config) {
        var world = scenario.build().apply(rate);

        // Warm up: enough ticks for the JIT, and enough time that a fast scenario is not measured cold.
        // A scenario that costs seconds per tick is dominated by arithmetic, not by the JIT, so the
        // warm-up stops at a third of the row budget (but never before two ticks).
        var warmStart = System.nanoTime();
        int warmed = 0;
        while(warmed < 2 || ((warmed < config.warmupTicks() || (System.nanoTime() - warmStart) / 1e6 < config.warmupMillis())
                && (System.nanoTime() - warmStart) / 1e6 < config.maxRowMillis() / 3.0)) {
            world.tick();
            ++warmed;
        }
        var estimateMs = (System.nanoTime() - warmStart) / 1e6 / warmed;
        var blockTicks = Math.max(config.minBlockTicks(), (int) Math.min(300, config.blockMillis() / Math.max(estimateMs, 1e-3)));
        var repeats = config.repeats();
        // Shrink the measurement to fit the row budget: fewer repeats first (three keeps a median
        // meaningful), then fewer ticks per block, down to one.
        var budgetTicks = (int) Math.max(3, (config.maxRowMillis() * 2.0 / 3) / Math.max(estimateMs, 1e-3));
        while(repeats * blockTicks > budgetTicks && repeats > 3)
            --repeats;
        blockTicks = Math.max(1, Math.min(blockTicks, budgetTicks / repeats));

        var before = world.statistics().copy();
        var samples = new double[repeats * blockTicks];
        var blockMedians = new double[repeats];
        int at = 0;
        for(int repeat = 0; repeat < repeats; ++repeat) {
            var block = new double[blockTicks];
            for(int t = 0; t < blockTicks; ++t) {
                var start = System.nanoTime();
                world.tick();
                block[t] = (System.nanoTime() - start) / 1e6;
            }
            System.arraycopy(block, 0, samples, at, blockTicks);
            at += blockTicks;
            Arrays.sort(block);
            blockMedians[repeat] = percentile(block, 0.5);
        }
        var delta = world.statistics().minus(before);

        Arrays.sort(samples);
        Arrays.sort(blockMedians);
        var median = percentile(samples, 0.5);
        var ticks = samples.length;
        var spread = (blockMedians[blockMedians.length - 1] - blockMedians[0]) / Math.max(median, 1e-9) * 100;
        return new Result(scenario.id(), rate, world.islands.size(), world.nodes(),
                median, percentile(samples, 0.1), percentile(samples, 0.9), samples[0], spread,
                (double) delta.solves / ticks,
                (double) delta.newtonSolves / ticks,
                delta.newtonSolves == 0 ? 0 : (double) delta.newtonIterations / delta.newtonSolves,
                (double) delta.refactorizations / ticks,
                (double) delta.residualBuilds / ticks,
                (double) delta.lineSearchProbes / ticks,
                delta.solves == 0 ? 0 : 100.0 * delta.nonConverged / delta.solves,
                (double) delta.capHits / ticks,
                (double) delta.jacobianAdds / ticks,
                (double) delta.jacobianRebuilds / ticks,
                ticks);
    }

    /** Every scenario whose id contains one of {@code filters} (all when empty), at every rate asked for. */
    public static List<Result> run(List<String> filters, int[] rates, Config config) {
        var results = new ArrayList<Result>();
        for(var scenario : SCENARIOS) {
            if(!filters.isEmpty() && filters.stream().noneMatch(scenario.id()::contains))
                continue;
            for(var rate : rates)
                results.add(measure(scenario, rate, config));
        }
        return results;
    }

    // ------------------------------------------------------------------ profiling

    /** Samples of a JFR recording folded into self and inclusive counts per method. */
    public static final class Profile {
        public int samples;
        public final Map<String, Integer> self = new HashMap<>();
        public final Map<String, Integer> inclusive = new HashMap<>();
        /** Self time by class.method:line, for finding the exact hot line. */
        public final Map<String, Integer> selfByLine = new HashMap<>();

        public double selfPercent(String method) {
            return 100.0 * self.getOrDefault(method, 0) / Math.max(samples, 1);
        }

        public double inclusivePercent(String methodSubstring) {
            long total = 0;
            for(var e : inclusive.entrySet()) {
                if(e.getKey().contains(methodSubstring))
                    total = Math.max(total, e.getValue());
            }
            return 100.0 * total / Math.max(samples, 1);
        }

        public String top(Map<String, Integer> counts, int limit) {
            var sb = new StringBuilder();
            counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit)
                    .forEach(e -> sb.append(String.format(Locale.ROOT, "  %5.1f%%  %s%n", 100.0 * e.getValue() / Math.max(samples, 1), e.getKey())));
            return sb.toString();
        }
    }

    private static String frameName(RecordedFrame f) {
        var m = f.getMethod();
        var c = m.getType().getName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + m.getName();
    }

    /**
     * Runs {@code scenario} at {@code rate} under JFR's execution sampler at a 1 ms period and folds the
     * samples. Ticks are run for {@code seconds} after a warm-up, all of it under the recording.
     */
    public static Profile profile(Scenario scenario, int rate, double seconds) throws Exception {
        var original = System.out;
        System.setOut(DISCARD);
        var file = Files.createTempFile("solver-bench", ".jfr");
        try(var recording = new Recording(Configuration.getConfiguration("profile"))) {
            var world = scenario.build().apply(rate);
            for(int t = 0; t < 30; ++t)
                world.tick();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            recording.start();
            var end = System.nanoTime() + (long) (seconds * 1e9);
            while(System.nanoTime() < end)
                world.tick();
            recording.stop();
            recording.dump(file);
        } finally {
            System.setOut(original);
        }
        var profile = new Profile();
        for(var event : RecordingFile.readAllEvents(file)) {
            if(!event.getEventType().getName().equals("jdk.ExecutionSample"))
                continue;
            var stack = event.getStackTrace();
            if(stack == null || stack.getFrames().isEmpty())
                continue;
            ++profile.samples;
            var top = stack.getFrames().get(0);
            profile.self.merge(frameName(top), 1, Integer::sum);
            profile.selfByLine.merge(frameName(top) + ":" + top.getLineNumber(), 1, Integer::sum);
            var seen = new java.util.HashSet<String>();
            for(var frame : stack.getFrames()) {
                var name = frameName(frame);
                if(seen.add(name))
                    profile.inclusive.merge(name, 1, Integer::sum);
            }
        }
        Files.deleteIfExists(file);
        return profile;
    }

    // ------------------------------------------------------------------ command line

    /**
     * Usage: {@code [scenario filter, comma separated] [rates, comma separated] [--quick] [--jfr] [--console]}.
     * Both positional arguments may be {@code all}.
     */
    public static void main(String[] args) throws Exception {
        var filters = new ArrayList<String>();
        var rates = RATES;
        var config = Config.STANDARD;
        var jfr = false;
        int positional = 0;
        for(var arg : args) {
            switch(arg) {
                case "--quick" -> config = Config.QUICK;
                case "--jfr" -> jfr = true;
                case "--console" -> config = config.withConsole(true);
                default -> {
                    if(positional == 0 && !arg.equals("all"))
                        filters.addAll(Arrays.asList(arg.split(",")));
                    else if(positional == 1 && !arg.equals("all"))
                        rates = Arrays.stream(arg.split(",")).mapToInt(Integer::parseInt).toArray();
                    ++positional;
                }
            }
        }
        if(Boolean.getBoolean("bench.console"))
            config = config.withConsole(true);
        System.out.println(Result.header());
        for(var scenario : SCENARIOS) {
            if(!filters.isEmpty() && filters.stream().noneMatch(scenario.id()::contains))
                continue;
            for(var rate : rates) {
                System.out.println(measure(scenario, rate, config).row());
                if(jfr) {
                    var profile = profile(scenario, rate, 3);
                    System.out.printf("  JFR %d samples, self time:%n%s", profile.samples, profile.top(profile.self, 12));
                    System.out.printf("  inclusive:%n%s", profile.top(profile.inclusive, 16));
                }
            }
        }
    }
}
