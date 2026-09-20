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
package org.patryk3211.powergrid.electricity.sim.solver;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.ejml.data.DMatrixRMaj;

import java.util.Arrays;

/**
 * Solves {@code A x = b} for a matrix that differs from a factored one in a handful of entries,
 * without factoring it again (the Sherman-Morrison-Woodbury identity).
 *
 * <h2>Why</h2>
 * A Newton iteration on a circuit with a few diodes changes only the entries those diodes stamp,
 * yet {@code JavaMNA} used to refactor the whole sparse matrix after every change. Here the matrix
 * that was last factored is kept as the <em>base</em> {@code A0}, and everything the nonlinear
 * elements have done to it since is a difference {@code D}, recorded entry by entry as it is
 * stamped. With {@code M} the set of nodes those entries touch (rows or columns), {@code E} the
 * columns of the identity that pick them out, and {@code D} the {@code m x m} matrix of
 * differences among them:
 * <pre>
 *   A = A0 + E D E^T
 *   A x = b   =>   x = y - W D x_M,   y = A0^-1 b,   W = A0^-1 E
 *   rows M of that:  (I + Z D) x_M = y_M,   Z = E^T W  (the m x m block of A0^-1)
 * </pre>
 * So an iteration costs one triangular solve against the existing factors, an {@code m x m}
 * dense solve, and {@code m} vector updates, where a refactorisation of a 300 node island cost a
 * sparse LU. {@code W} takes {@code m} triangular solves and is only rebuilt when the base is.
 *
 * <h2>When it gives up</h2>
 * The base is refactored (a "rebase": the current matrix becomes the base and {@code D} is zero)
 * when anything other than a nonlinear element's stamp changed the matrix, when the small system
 * {@code I + Z D} is close to singular (which means the base is a poor description of the present
 * matrix, and costs digits), and never mid-solve otherwise. {@link #solve} answers {@code false}
 * when even a freshly rebased system cannot be solved, or when more nodes are touched than
 * {@link JavaMNA.Tuning#maxTouchedNodes}; the caller then does what it always did.
 *
 * <p>Values are in the scaled system {@code JavaMNA} factors (row and column equilibration
 * included), because that is the matrix whose factors are kept.
 */
final class LowRankUpdate {
    /** Rebase when the smallest pivot of {@code I + Z D} is below this fraction of the larger of its largest pivot and 1. */
    static final double SOFT_PIVOT_RATIO = 1e-4;
    /** Treat the small system as singular below this fraction (or on NaN). */
    static final double HARD_PIVOT_RATIO = 1e-13;

    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private int size;

    // Differences since the base was factored, one slot per distinct (row, column).
    private int entries;
    private int[] entryRow = new int[16];
    private int[] entryCol = new int[16];
    private int[] entryRowPos = new int[16];
    private int[] entryColPos = new int[16];
    private double[] entryValue = new double[16];

    // Nodes touched by any entry, in order of first appearance, and where each one sits.
    private int touched;
    private int[] node = new int[8];
    private int[] position = new int[0];
    // w[b] = A0^-1 e_node[b], valid for b < columnsReady against the current base.
    private double[][] w = new double[8][];
    private int columnsReady;

    private boolean baseValid;
    private int maxTouched = Integer.MAX_VALUE;

    private DMatrixRMaj unit = new DMatrixRMaj(0, 1);
    private DMatrixRMaj column = new DMatrixRMaj(0, 1);
    private double[] k = new double[64];
    private double[] rhs = new double[8];
    private double[] step = new double[8];
    private int[] pivot = new int[8];

    /** Refactorisations this object has asked for, for the statistics. */
    long rebases;

    LowRankUpdate() {
        slots.defaultReturnValue(-1);
    }

    /** Forget everything: the matrix has been reallocated. */
    void reset(int size) {
        this.size = size;
        slots.clear();
        entries = 0;
        touched = 0;
        position = new int[size];
        Arrays.fill(position, -1);
        columnsReady = 0;
        baseValid = false;
        unit = new DMatrixRMaj(size, 1);
        column = new DMatrixRMaj(size, 1);
    }

    /** The factors no longer describe the matrix for a reason this object was not told about. */
    void invalidateBase() {
        baseValid = false;
    }

    /** Nodes touched so far, which is the dimension of the small system. */
    int touchedNodes() {
        return touched;
    }

    void setMaxTouched(int maxTouched) {
        this.maxTouched = maxTouched;
    }

    /** A nonlinear element added {@code value} at ({@code row}, {@code column}) of the scaled matrix. */
    void record(int row, int column, double value) {
        long key = (long) row * size + column;
        int slot = slots.get(key);
        if(slot < 0)
            slot = newEntry(row, column, key);
        entryValue[slot] += value;
    }

    private int newEntry(int row, int column, long key) {
        if(entries == entryRow.length) {
            int grown = entries * 2;
            entryRow = Arrays.copyOf(entryRow, grown);
            entryCol = Arrays.copyOf(entryCol, grown);
            entryRowPos = Arrays.copyOf(entryRowPos, grown);
            entryColPos = Arrays.copyOf(entryColPos, grown);
            entryValue = Arrays.copyOf(entryValue, grown);
        }
        int slot = entries++;
        entryRow[slot] = row;
        entryCol[slot] = column;
        entryRowPos[slot] = touch(row);
        entryColPos[slot] = touch(column);
        entryValue[slot] = 0;
        slots.put(key, slot);
        return slot;
    }

    private int touch(int index) {
        int at = position[index];
        if(at >= 0)
            return at;
        if(touched == node.length) {
            node = Arrays.copyOf(node, touched * 2);
            w = Arrays.copyOf(w, touched * 2);
            rhs = new double[touched * 2];
            step = new double[touched * 2];
            pivot = new int[touched * 2];
        }
        position[index] = touched;
        node[touched] = index;
        return touched++;
    }

    /** Factor the current matrix as the new base. False if it cannot be factored. */
    private boolean rebase(DynamicallyTypedMatrix base) {
        ++rebases;
        base.refactorize();
        if(!base.factorizationValid()) {
            baseValid = false;
            return false;
        }
        Arrays.fill(entryValue, 0, entries, 0.0);
        columnsReady = 0;
        baseValid = true;
        return true;
    }

    private void ensureColumns(DynamicallyTypedMatrix base) {
        for(int b = columnsReady; b < touched; ++b) {
            unit.zero();
            unit.data[node[b]] = 1;
            base.solve(unit, column);
            if(w[b] == null)
                w[b] = new double[size];
            System.arraycopy(column.data, 0, w[b], 0, size);
        }
        columnsReady = touched;
    }

    /**
     * Solve {@code A x = r} where {@code A} is the factored matrix plus the recorded differences.
     *
     * @param base the matrix whose factors are kept; its own values are ignored except when it is
     *             marked for refactorisation, which means something changed it behind our back
     * @return false if the system could not be solved this way; {@code x} is then undefined
     */
    boolean solve(DynamicallyTypedMatrix base, DMatrixRMaj r, DMatrixRMaj x) {
        if(touched > maxTouched)
            return false;
        boolean rebased = false;
        if(!baseValid || base.isMarked()) {
            if(!rebase(base))
                return false;
            rebased = true;
        }
        ensureColumns(base);
        while(true) {
            var ratio = factorSmallSystem();
            if(ratio >= SOFT_PIVOT_RATIO || (ratio >= HARD_PIVOT_RATIO && rebased))
                break;
            if(rebased)
                return false;
            // The base has drifted too far from the matrix. Refactor: differences are zero again,
            // the small system is the identity and cannot fail.
            if(!rebase(base))
                return false;
            rebased = true;
            ensureColumns(base);
        }

        // y = A0^-1 r, into x.
        base.solve(r, x);
        var y = x.data;
        int m = touched;
        for(int a = 0; a < m; ++a)
            rhs[a] = y[node[a]];
        solveSmallSystem(rhs, m);
        // step = D x_M
        Arrays.fill(step, 0, m, 0.0);
        for(int e = 0; e < entries; ++e)
            step[entryRowPos[e]] += entryValue[e] * rhs[entryColPos[e]];
        for(int b = 0; b < m; ++b) {
            var s = step[b];
            if(s == 0)
                continue;
            var wb = w[b];
            for(int i = 0; i < size; ++i)
                y[i] -= wb[i] * s;
        }
        return true;
    }

    /**
     * Build {@code I + Z D} and factor it in place with partial pivoting.
     *
     * @return the smallest pivot over the larger of the largest pivot and 1, or 0 (or NaN) if it is
     *         singular; 1 for an empty system
     */
    private double factorSmallSystem() {
        int m = touched;
        if(m == 0)
            return 1;
        if(k.length < m * m)
            k = new double[m * m];
        Arrays.fill(k, 0, m * m, 0.0);
        for(int a = 0; a < m; ++a)
            k[a * m + a] = 1;
        // (Z D)[a][c] = sum over entries (b, c) of Z[a][b] * D[b][c], and Z[a][b] = w[b][node[a]].
        for(int e = 0; e < entries; ++e) {
            var v = entryValue[e];
            if(v == 0)
                continue;
            var wb = w[entryRowPos[e]];
            int c = entryColPos[e];
            for(int a = 0; a < m; ++a)
                k[a * m + c] += wb[node[a]] * v;
        }
        double smallest = Double.POSITIVE_INFINITY, largest = 0;
        for(int col = 0; col < m; ++col) {
            int best = col;
            double bestValue = Math.abs(k[col * m + col]);
            for(int row = col + 1; row < m; ++row) {
                var v = Math.abs(k[row * m + col]);
                if(v > bestValue) {
                    bestValue = v;
                    best = row;
                }
            }
            pivot[col] = best;
            if(!(bestValue > 0))
                return 0;
            if(best != col) {
                for(int j = 0; j < m; ++j) {
                    var t = k[col * m + j];
                    k[col * m + j] = k[best * m + j];
                    k[best * m + j] = t;
                }
            }
            smallest = Math.min(smallest, bestValue);
            largest = Math.max(largest, bestValue);
            var inverse = 1 / k[col * m + col];
            for(int row = col + 1; row < m; ++row) {
                var factor = k[row * m + col] * inverse;
                k[row * m + col] = factor;
                if(factor == 0)
                    continue;
                for(int j = col + 1; j < m; ++j)
                    k[row * m + j] -= factor * k[col * m + j];
            }
        }
        // Measured against the identity this matrix is a correction to, not only against its own
        // largest pivot: a single pivot has ratio 1 to itself, however close to zero it is.
        var ratio = smallest / Math.max(largest, 1.0);
        return Double.isNaN(ratio) ? 0 : ratio;
    }

    /** Solve with the factors {@link #factorSmallSystem()} left behind, in place. */
    private void solveSmallSystem(double[] b, int m) {
        // The factorisation swapped whole rows, multipliers included, so every interchange has to
        // be applied to b before the forward substitution rather than interleaved with it.
        for(int col = 0; col < m; ++col) {
            int p = pivot[col];
            if(p != col) {
                var t = b[col];
                b[col] = b[p];
                b[p] = t;
            }
        }
        for(int col = 0; col < m; ++col) {
            var bc = b[col];
            if(bc == 0)
                continue;
            for(int row = col + 1; row < m; ++row)
                b[row] -= k[row * m + col] * bc;
        }
        for(int row = m - 1; row >= 0; --row) {
            var sum = b[row];
            for(int j = row + 1; j < m; ++j)
                sum -= k[row * m + j] * b[j];
            b[row] = sum / k[row * m + row];
        }
    }
}
