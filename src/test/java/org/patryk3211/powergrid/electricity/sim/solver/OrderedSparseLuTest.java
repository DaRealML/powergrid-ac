package org.patryk3211.powergrid.electricity.sim.solver;

import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * The fill-reducing sparse LU against EJML's own on the natural ordering.
 * <p>
 * Every test builds the same matrix twice, once solved the way it was before the kernel work
 * ({@link SolverSwitches#legacySparseLu}) and once the new way, and compares. The matrices are the
 * shapes an island produces: a conductance graph (symmetric, diagonally dominant), with
 * voltage-source rows that have a zero diagonal and are where pivoting has something to do.
 */
public class OrderedSparseLuTest {
    @AfterEach
    void restoreSwitches() {
        SolverSwitches.legacySparseLu = false;
    }

    /** A random island: a spanning tree plus extra edges (a mesh has cycles), grounded loads, and some voltage sources. */
    private static DMatrixRMaj randomIsland(Random random, int nodes, int extraEdges, int sources) {
        int n = nodes + sources;
        var a = new DMatrixRMaj(n, n);
        for(int i = 1; i < nodes; ++i)
            stamp(a, i, random.nextInt(i), 0.1 + random.nextDouble() * 10);
        for(int e = 0; e < extraEdges; ++e) {
            int x = random.nextInt(nodes), y = random.nextInt(nodes);
            if(x != y)
                stamp(a, x, y, 0.1 + random.nextDouble() * 10);
        }
        for(int i = 0; i < nodes; ++i)
            a.add(i, i, 0.01 + random.nextDouble());
        // A voltage source between a node and ground: the branch current is an extra unknown.
        // Distinct nodes: two sources on the same node would stamp two identical rows (both just
        // a 1 at that column), which is structurally singular regardless of any solver -- not a
        // case the fill-reducing ordering or the fused solve has any way to rescue.
        var sourceNodes = new java.util.HashSet<Integer>();
        for(int s = 0; s < sources && sourceNodes.size() < nodes; ++s) {
            int node;
            do {
                node = random.nextInt(nodes);
            } while(!sourceNodes.add(node));
            int row = nodes + s;
            a.add(row, node, 1);
            a.add(node, row, 1);
        }
        return a;
    }

    private static void stamp(DMatrixRMaj a, int x, int y, double g) {
        a.add(x, x, g);
        a.add(y, y, g);
        a.add(x, y, -g);
        a.add(y, x, -g);
    }

    private static DynamicallyTypedMatrix sparseCopy(DMatrixRMaj dense) {
        var m = new DynamicallyTypedMatrix(dense.numRows, dense.numCols, DynamicallyTypedMatrix.Solver.LU);
        for(int i = 0; i < dense.numRows; ++i) {
            for(int j = 0; j < dense.numCols; ++j) {
                if(dense.get(i, j) != 0)
                    m.set(i, j, dense.get(i, j));
            }
        }
        m.optimize();
        Assertions.assertEquals(DynamicallyTypedMatrix.State.SPARSE, m.getState(), "test matrix must be big enough to be sparse");
        return m;
    }

    private static double solveError(DMatrixRMaj dense, DMatrixRMaj x, DMatrixRMaj b) {
        // Residual of A x = b relative to the size of b: what a solver is entitled to promise.
        var r = new DMatrixRMaj(b.numRows, 1);
        org.ejml.dense.row.CommonOps_DDRM.mult(dense, x, r);
        double worst = 0, scale = 1e-300;
        for(int i = 0; i < b.numRows; ++i) {
            worst = Math.max(worst, Math.abs(r.get(i, 0) - b.get(i, 0)));
            scale = Math.max(scale, Math.abs(b.get(i, 0)));
        }
        return worst / scale;
    }

    @Test
    void randomIslandsSolveLikeTheNaturalOrderingDoes() {
        var random = new Random(20240607);
        for(int trial = 0; trial < 200; ++trial) {
            int nodes = 7 + random.nextInt(80);
            var dense = randomIsland(random, nodes, random.nextInt(nodes), random.nextInt(4));
            int n = dense.numRows;
            var b = new DMatrixRMaj(n, 1);
            for(int i = 0; i < n; ++i)
                b.set(i, 0, random.nextGaussian());

            var legacy = sparseCopy(dense);
            var fast = sparseCopy(dense);
            var xLegacy = new DMatrixRMaj(n, 1);
            var xFast = new DMatrixRMaj(n, 1);
            SolverSwitches.legacySparseLu = true;
            legacy.solve(b, xLegacy);
            SolverSwitches.legacySparseLu = false;
            fast.solve(b, xFast);

            Assertions.assertTrue(fast.factorNonzeros() > 0, "the new path was not used");
            Assertions.assertEquals(-1, legacy.factorNonzeros(), "the legacy path was not used");
            Assertions.assertTrue(solveError(dense, xFast, b) < 1e-10, "trial " + trial + " residual " + solveError(dense, xFast, b));
            for(int i = 0; i < n; ++i) {
                double scale = Math.max(1, Math.abs(xLegacy.get(i, 0)));
                Assertions.assertEquals(xLegacy.get(i, 0), xFast.get(i, 0), 1e-9 * scale, "trial " + trial + " entry " + i);
            }
        }
    }

    @Test
    void aSingularMatrixSolvesToZeroInBothPaths() {
        // Node 3 is connected to nothing at all, so its row and column are empty.
        var dense = new DMatrixRMaj(8, 8);
        for(int i = 0; i < 8; ++i) {
            if(i != 3)
                dense.set(i, i, 2);
        }
        var b = new DMatrixRMaj(8, 1);
        b.set(0, 0, 1);
        for(var legacy : new boolean[]{ true, false }) {
            SolverSwitches.legacySparseLu = legacy;
            var m = sparseCopy(dense);
            var x = new DMatrixRMaj(8, 1);
            x.set(0, 0, 123);
            m.solve(b, x);
            for(int i = 0; i < 8; ++i)
                Assertions.assertEquals(0, x.get(i, 0), "legacy=" + legacy + " entry " + i);
        }
    }

    @Test
    void orderingIsComputedOncePerSparsityPatternNotOncePerFactorisation() {
        var random = new Random(7);
        var dense = randomIsland(random, 40, 20, 2);
        var m = sparseCopy(dense);
        var b = new DMatrixRMaj(dense.numRows, 1);
        b.set(1, 0, 1);
        var x = new DMatrixRMaj(dense.numRows, 1);
        m.solve(b, x);
        Assertions.assertEquals(1, m.orderings());

        // Same pattern, new values, as a diode does on every Newton iteration.
        for(int k = 0; k < 5; ++k) {
            m.add(0, 0, 0.5);
            m.markRefactorize();
            m.solve(b, x);
        }
        Assertions.assertEquals(1, m.orderings(), "a value change must not recompute the ordering");

        // A new entry is a new pattern.
        int row = 0, col = 0;
        for(int i = 0; i < dense.numRows && col == 0; ++i) {
            for(int j = 0; j < dense.numCols; ++j) {
                if(i != j && dense.get(i, j) == 0) {
                    row = i;
                    col = j;
                    break;
                }
            }
        }
        Assertions.assertNotEquals(0, col);
        m.add(row, col, 0.25);
        m.markRefactorize();
        m.solve(b, x);
        Assertions.assertEquals(2, m.orderings(), "a changed pattern must recompute the ordering");
    }

    @Test
    void orderingCutsFillOnAMesh() {
        // 15 by 20 resistive mesh, every node loaded: the natural order is row by row, so
        // elimination fills in a band as wide as a row.
        int rows = 15, cols = 20, n = rows * cols;
        var dense = new DMatrixRMaj(n, n);
        for(int r = 0; r < rows; ++r) {
            for(int c = 0; c < cols; ++c) {
                int node = r * cols + c;
                if(c + 1 < cols)
                    stamp(dense, node, node + 1, 2);
                if(r + 1 < rows)
                    stamp(dense, node, node + cols, 2);
                dense.add(node, node, 0.005);
            }
        }
        var b = new DMatrixRMaj(n, 1);
        b.set(0, 0, 1);
        var xLegacy = new DMatrixRMaj(n, 1);
        var xFast = new DMatrixRMaj(n, 1);

        var fast = sparseCopy(dense);
        fast.solve(b, xFast);
        int fastNonzeros = fast.factorNonzeros();

        // EJML's own count on the natural ordering, from its factorisation of the same matrix.
        var natural = new org.ejml.sparse.csc.decomposition.lu.LuUpLooking_DSCC(null);
        natural.decompose(org.ejml.ops.DConvertMatrixStruct.convert(dense, (org.ejml.data.DMatrixSparseCSC) null, 0));
        int naturalNonzeros = natural.getL().nz_length + natural.getU().nz_length;

        System.out.printf("mesh %dx%d: natural order %d nonzeros in L+U, minimum degree %d%n", rows, cols, naturalNonzeros, fastNonzeros);
        Assertions.assertTrue(fastNonzeros < 0.6 * naturalNonzeros, "fill " + fastNonzeros + " vs natural " + naturalNonzeros);

        SolverSwitches.legacySparseLu = true;
        sparseCopy(dense).solve(b, xLegacy);
        for(int i = 0; i < n; ++i)
            Assertions.assertEquals(xLegacy.get(i, 0), xFast.get(i, 0), 1e-10 * Math.max(1, Math.abs(xLegacy.get(i, 0))));
    }

    @Test
    void minimumDegreeReturnsEveryNodeOnceAndIsDeterministic() {
        var random = new Random(99);
        for(int trial = 0; trial < 50; ++trial) {
            int nodes = 7 + random.nextInt(120);
            var dense = randomIsland(random, nodes, random.nextInt(nodes), random.nextInt(3));
            var m = sparseCopy(dense);
            var csc = DynamicallyTypedMatrixAccess.csc(m);
            int n = csc.numCols;
            var first = new int[n];
            var second = new int[n];
            MinimumDegree.order(n, csc.col_idx, csc.nz_rows, first);
            MinimumDegree.order(n, csc.col_idx, csc.nz_rows, second);
            var seen = new boolean[n];
            for(int k = 0; k < n; ++k) {
                Assertions.assertFalse(seen[first[k]], "node " + first[k] + " ordered twice");
                seen[first[k]] = true;
            }
            Assertions.assertArrayEquals(first, second, "the ordering must not depend on anything but the pattern");
        }
    }

    /** Peeks at the CSC storage of a sparse {@link DynamicallyTypedMatrix} through its public API only. */
    private static final class DynamicallyTypedMatrixAccess {
        static org.ejml.data.DMatrixSparseCSC csc(DynamicallyTypedMatrix m) {
            try {
                var field = DynamicallyTypedMatrix.class.getDeclaredField("matrix");
                field.setAccessible(true);
                return (org.ejml.data.DMatrixSparseCSC) field.get(m);
            } catch(ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
