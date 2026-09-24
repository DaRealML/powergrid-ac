package org.patryk3211.electricity;

import org.junit.jupiter.api.RepeatedTest;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves independent islands can be solved concurrently without corrupting each other's result.
 * <p>
 * {@code JavaMNA} always factorises through {@code DynamicallyTypedMatrix.Solver.LU} (see its
 * constructor calls), dense ({@code LUDecompositionAlt_DDRM}) below the 6-row sparse threshold and
 * sparse ({@code LuUpLooking_DSCC}) above it. Reading both classes' compiled fields (no sources jar
 * is on the classpath, so {@code javap -p} was used instead of guessing) found every field —
 * including the sparse decomposition's scratch arrays {@code gxi}/{@code gw}/{@code x}/{@code pinv}
 * — declared on the instance, not the class: no {@code static} field anywhere in
 * {@code LuUpLooking_DSCC}, {@code ApplyFillReductionPermutation_DSCC}, {@code LinearSolverLu_DSCC},
 * {@code LUDecompositionAlt_DDRM} or {@code CholeskyDecompositionCommon_DDRM}. Each
 * {@code DynamicallyTypedMatrix} owns one {@code LinearSolver}, so per the field layout there is no
 * shared workspace for two islands' solves to race on.
 * <p>
 * That is a static argument, not a proof against a scheduling-dependent bug the field layout alone
 * cannot rule out (e.g. a solver whose {@code decompose()} temporarily mutates the INPUT matrix in
 * a way that matters under a specific interleaving). The direct check: build a set of islands ahead
 * of time (small ones that stay dense, and >6-node meshes with diode branches that hit sparse LU and
 * refactorise every tick), solve each sequentially to get a reference trajectory of node voltages,
 * then rebuild fresh copies and step them from a large thread pool, many islands at once, many
 * iterations, and diff every final voltage against the reference. Circuit construction (which uses
 * {@code SolverGolden.Mutation}, an unsynchronized static) happens single-threaded before any pool
 * work starts; only {@code singleTick()} runs concurrently, which is exactly what the proposed
 * parallel stepper would do.
 * <p>
 * <h2>The comparison is a tight tolerance, not bit-equality</h2>
 * A first version of this test compared bits and failed on {@code sparse_mesh_linear} about one
 * run in three, always by 1-3 ULPs. Rerunning with the pool forced to exactly one thread (so there
 * was no concurrency at all, only two separate calls to identical code) reproduced the same
 * drift, which rules out a threading cause: the reference call runs cold (interpreter/C1) while the
 * later calls are hot enough that C2 has auto-vectorised the LU inner loops by then, and summation
 * is not associative in floating point, so a different accumulation order rounds differently in the
 * last bit. That is expected and harmless. {@link #TOLERANCE} is far tighter than a real corruption
 * (a wrong island's data, a stale factorisation, a torn write) would ever land within, so it still
 * catches the bug this test exists for.
 */
public class SolverConcurrencyStressTest {
    private static final int TICKS_PER_ISLAND = 40;
    /** Relative tolerance for the final-voltage comparison; see the JIT/vectorisation note above. */
    private static final double TOLERANCE = 1e-9;

    /** A freshly built island paired with the node whose voltage this recipe checks. */
    private record BuiltIsland(TestHelper.Network net, IElectricNode probe) { }

    /** One buildable, steppable circuit, named for its diagnostic. */
    private record Recipe(String name, Supplier<BuiltIsland> build) { }

    private static final List<Recipe> RECIPES = List.of(
            // Small, stays dense (< 6 rows): a loaded divider under an AC source.
            new Recipe("dense_divider", () -> {
                var net = new TestHelper.Network(true);
                var term = net.N();
                SolverGolden.acSource(net, term, null, 1, 30, 5);
                var mid = net.N();
                SolverGolden.res(net, 10, term, mid);
                SolverGolden.res(net, 20, mid, SolverGolden.ground(net));
                return new BuiltIsland(net, mid);
            }),
            // > 6 nodes: sparse LU, no nonlinearity, refactorises only on the first tick.
            new Recipe("sparse_mesh_linear", () -> {
                var net = new TestHelper.Network(false);
                var gnd = SolverGolden.ground(net);
                var nodes = new IElectricNode[12];
                for(int i = 0; i < nodes.length; ++i)
                    nodes[i] = net.N();
                for(int i = 0; i < nodes.length; ++i) {
                    SolverGolden.res(net, 5 + i, nodes[i], nodes[(i + 1) % nodes.length]);
                    SolverGolden.res(net, 50, nodes[i], gnd);
                }
                SolverGolden.acSource(net, nodes[0], null, 0.2, 40, 5);
                return new BuiltIsland(net, nodes[6]);
            }),
            // > 6 nodes plus a diode: sparse LU, nonlinear, refactorises most ticks.
            new Recipe("sparse_mesh_diode", () -> {
                var net = new TestHelper.Network(false);
                var gnd = SolverGolden.ground(net);
                var hot = net.N();
                SolverGolden.acSource(net, hot, null, 0.5, 60, 5);
                var a = net.N();
                var b = net.N();
                var c = net.N();
                var out = net.N();
                SolverGolden.res(net, 2, hot, a);
                SolverGolden.res(net, 3, a, b);
                SolverGolden.res(net, 4, b, c);
                net.network.addWire(SolverGolden.diode(c, out));
                SolverGolden.cap(net, 1e-3, 0.05, out, gnd);
                SolverGolden.res(net, 100, out, gnd);
                SolverGolden.res(net, 1e6, a, gnd);
                SolverGolden.res(net, 1e6, b, gnd);
                return new BuiltIsland(net, out);
            })
    );

    private static double runReference(Recipe recipe) {
        var island = recipe.build().get();
        island.net().network.warmUp(-1);
        for(int t = 0; t < TICKS_PER_ISLAND; ++t)
            island.net().calculate();
        return island.probe().getVoltage();
    }

    @RepeatedTest(3)
    void manyIndependentIslandsSolvedConcurrentlyMatchSequentialReference() throws InterruptedException {
        var references = new double[RECIPES.size()];
        for(int r = 0; r < RECIPES.size(); ++r)
            references[r] = runReference(RECIPES.get(r));

        // Build every island up front, single-threaded, exactly as a real world would: circuit
        // construction (which touches SolverGolden.Mutation, an unsynchronized static) is not part
        // of what is being proposed for the thread pool, only singleTick()/calculate() is. Building
        // concurrently was tried first and produced a handful of 1-ULP mismatches traced to this
        // construction-time state, not to the solver — see the class-level comment on that trap.
        int islandsPerRecipe = 60;
        record Prepared(String name, BuiltIsland island, double expected) { }
        var prepared = new ArrayList<Prepared>();
        for(int r = 0; r < RECIPES.size(); ++r) {
            var recipe = RECIPES.get(r);
            for(int copy = 0; copy < islandsPerRecipe; ++copy)
                prepared.add(new Prepared(recipe.name(), recipe.build().get(), references[r]));
        }

        int poolThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        var pool = Executors.newFixedThreadPool(poolThreads);
        var tasks = new ArrayList<Callable<Void>>();
        for(var p : prepared) {
            tasks.add(() -> {
                for(int t = 0; t < TICKS_PER_ISLAND; ++t)
                    p.island().net().calculate();
                var actual = p.island().probe().getVoltage();
                var scale = Math.max(Math.abs(p.expected()), 1e-12);
                if(Math.abs(actual - p.expected()) / scale > TOLERANCE)
                    throw new AssertionError(p.name() + ": expected " + p.expected() + " got " + actual);
                return null;
            });
        }

        List<Future<Void>> futures;
        try {
            futures = pool.invokeAll(tasks);
        } finally {
            pool.shutdown();
        }
        int failures = 0;
        Throwable firstFailure = null;
        for(var future : futures) {
            try {
                future.get();
            } catch(Exception e) {
                ++failures;
                if(firstFailure == null)
                    firstFailure = e.getCause() != null ? e.getCause() : e;
            }
        }
        if(failures > 0)
            fail(failures + "/" + tasks.size() + " concurrent island solves diverged from the sequential reference; first: " + firstFailure);
    }
}
