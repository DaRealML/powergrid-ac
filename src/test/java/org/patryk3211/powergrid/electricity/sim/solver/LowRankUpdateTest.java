package org.patryk3211.powergrid.electricity.sim.solver;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * {@link LowRankUpdate} on its own: the Woodbury solve against a dense solve of the same matrix.
 * The package is the one of the class because it is package-private, as is what it works on.
 */
public class LowRankUpdateTest {
    private static final int N = 14;

    /** A sparse-looking, diagonally dominant matrix, held both as the solver's matrix and as a dense reference. */
    private static final class Pair {
        final DynamicallyTypedMatrix matrix = new DynamicallyTypedMatrix(N, N, DynamicallyTypedMatrix.Solver.LU);
        final DMatrixRMaj dense = new DMatrixRMaj(N, N);

        Pair(Random random) {
            for(int i = 0; i < N; ++i) {
                double off = 0;
                for(int j = 0; j < N; ++j) {
                    if(i != j && random.nextInt(4) == 0) {
                        double v = random.nextGaussian();
                        add(i, j, v);
                        off += Math.abs(v);
                    }
                }
                add(i, i, off + 1 + random.nextDouble());
            }
            matrix.optimize();
        }

        void add(int row, int column, double value) {
            matrix.add(row, column, value);
            dense.add(row, column, value);
        }
    }

    private static DMatrixRMaj vector(Random random) {
        var b = new DMatrixRMaj(N, 1);
        for(int i = 0; i < N; ++i)
            b.data[i] = random.nextGaussian();
        return b;
    }

    private static double largestDifference(DMatrixRMaj a, DMatrixRMaj b) {
        double worst = 0;
        for(int i = 0; i < N; ++i)
            worst = Math.max(worst, Math.abs(a.data[i] - b.data[i]));
        return worst;
    }

    @Test
    void solvesAPerturbedMatrixWithoutFactoringItAgain() {
        var random = new Random(7);
        for(int trial = 0; trial < 40; ++trial) {
            var pair = new Pair(random);
            var update = new LowRankUpdate();
            update.reset(N);
            var x = new DMatrixRMaj(N, 1);
            // First solve: nothing recorded, the base is factored here.
            Assertions.assertTrue(update.solve(pair.matrix, vector(random), x));
            Assertions.assertEquals(1, update.rebases);

            // Then a diode-like change: a few entries among a few nodes, non-symmetric like a controlled source.
            for(int k = 0; k < 6; ++k) {
                int row = random.nextInt(4), column = random.nextInt(4);
                double delta = 0.3 * random.nextGaussian();
                pair.add(row, column, delta);
                update.record(row, column, delta);
            }
            var b = vector(random);
            Assertions.assertTrue(update.solve(pair.matrix, b, x));

            var expected = new DMatrixRMaj(N, 1);
            Assertions.assertTrue(CommonOps_DDRM.solve(pair.dense.copy(), b, expected));
            Assertions.assertEquals(0, largestDifference(x, expected), 1e-9 * (1 + CommonOps_DDRM.elementMaxAbs(expected)),
                    "trial " + trial);
            Assertions.assertEquals(1, update.rebases, "the update must not refactor for a mild change (trial " + trial + ")");
        }
    }

    @Test
    void refactorsWhenTheBaseHasDriftedTooFarAndStillAnswersCorrectly() {
        var random = new Random(11);
        var pair = new Pair(random);
        var update = new LowRankUpdate();
        update.reset(N);
        var x = new DMatrixRMaj(N, 1);
        Assertions.assertTrue(update.solve(pair.matrix, vector(random), x));

        // Take one diagonal entry to a millionth of the way from singular: I + Z D is 1e-6.
        var inverse = new DMatrixRMaj(N, N);
        Assertions.assertTrue(CommonOps_DDRM.invert(pair.dense.copy(), inverse));
        double delta = -(1 - 1e-6) / inverse.get(2, 2);
        pair.add(2, 2, delta);
        update.record(2, 2, delta);

        var b = vector(random);
        Assertions.assertTrue(update.solve(pair.matrix, b, x));
        Assertions.assertEquals(2, update.rebases, "a nearly singular correction must rebase");
        var expected = new DMatrixRMaj(N, 1);
        Assertions.assertTrue(CommonOps_DDRM.solve(pair.dense.copy(), b, expected));
        Assertions.assertEquals(0, largestDifference(x, expected), 1e-4 * (1 + CommonOps_DDRM.elementMaxAbs(expected)));
    }

    @Test
    void declinesWhenMoreNodesAreTouchedThanAllowed() {
        var random = new Random(3);
        var pair = new Pair(random);
        var update = new LowRankUpdate();
        update.reset(N);
        update.setMaxTouched(2);
        update.record(0, 1, 0.5);
        update.record(2, 2, 0.5);
        Assertions.assertEquals(3, update.touchedNodes());
        Assertions.assertFalse(update.solve(pair.matrix, vector(random), new DMatrixRMaj(N, 1)));
    }

    @Test
    void declinesASingularMatrix() {
        var matrix = new DynamicallyTypedMatrix(N, N, DynamicallyTypedMatrix.Solver.LU);
        for(int i = 0; i < N - 1; ++i)
            matrix.add(i, i, 1);
        // Row and column N-1 stay empty.
        matrix.optimize();
        var update = new LowRankUpdate();
        update.reset(N);
        Assertions.assertFalse(update.solve(matrix, vector(new Random(1)), new DMatrixRMaj(N, 1)));
    }

    private static DMatrixRMaj vector(Random random, int n) {
        var b = new DMatrixRMaj(n, 1);
        for(int i = 0; i < n; ++i)
            b.data[i] = random.nextGaussian();
        return b;
    }

    /**
     * {@code JavaMNA.allocate} calls {@code lowRank.reset(size)} with a bigger size whenever an
     * island grows (a new wire lands on an existing node, {@code ElectricalNetwork.addNode} calls
     * {@code setDirty}). {@link LowRankUpdate#reset} must forget the cached {@code w} columns from
     * the old, smaller matrix, not just the touched-node bookkeeping.
     */
    @Test
    void growingAfterUseDoesNotReuseAStaleCachedColumn() {
        var random = new Random(21);
        var update = new LowRankUpdate();

        var small = new DynamicallyTypedMatrix(5, 5, DynamicallyTypedMatrix.Solver.LU);
        for(int i = 0; i < 5; ++i)
            small.add(i, i, 1 + random.nextDouble());
        small.optimize();
        update.reset(5);
        update.record(0, 0, 0.2);
        // This solve caches a w[0] column of length 5.
        Assertions.assertTrue(update.solve(small, vector(random, 5), new DMatrixRMaj(5, 1)));

        var bigDense = new DMatrixRMaj(10, 10);
        var big = new DynamicallyTypedMatrix(10, 10, DynamicallyTypedMatrix.Solver.LU);
        for(int i = 0; i < 10; ++i) {
            double v = 1 + random.nextDouble();
            big.add(i, i, v);
            bigDense.add(i, i, v);
        }
        big.optimize();
        // The matrix was reallocated bigger, as JavaMNA.allocate does on a grown island.
        // Nothing is recorded yet, so this solve only establishes the new base.
        update.reset(10);
        Assertions.assertTrue(update.solve(big, vector(random, 10), new DMatrixRMaj(10, 1)));

        // A diode-like change on the grown matrix touches node 0 for the first time since the
        // reset. Used to throw ArrayIndexOutOfBoundsException inside ensureColumns: it copies a
        // column of 10 into the w[0] left over from the 5x5 matrix, only 5 doubles long.
        double delta = 0.3;
        big.add(0, 0, delta);
        bigDense.add(0, 0, delta);
        update.record(0, 0, delta);
        var b = vector(random, 10);
        var x = new DMatrixRMaj(10, 1);
        Assertions.assertTrue(update.solve(big, b, x));

        var expected = new DMatrixRMaj(10, 1);
        Assertions.assertTrue(CommonOps_DDRM.solve(bigDense.copy(), b, expected));
        double worst = 0;
        for(int i = 0; i < 10; ++i)
            worst = Math.max(worst, Math.abs(x.data[i] - expected.data[i]));
        Assertions.assertEquals(0, worst, 1e-9 * (1 + CommonOps_DDRM.elementMaxAbs(expected)));
    }
}
