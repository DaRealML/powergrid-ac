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

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.csc.decomposition.lu.LuUpLooking_DSCC;

import java.util.Arrays;

/**
 * Sparse LU of one island's matrix: a fill-reducing ordering, EJML's factorisation, and triangular
 * solves written for the one thing an island does with them, which is solving the same matrix for
 * a new right-hand side 128 times per world tick.
 *
 * <h2>Why not EJML's solver as it is</h2>
 * Three things, each measured (docs/perf/kernel.md):
 * <ol>
 *   <li>EJML's sparse LU is offered only with the natural ordering here. The natural order is the
 *       order the player placed things in, and it can cost a factor of ten to fifty in nonzeros
 *       ({@link MinimumDegree}). Fewer nonzeros make the factorisation and every solve cheaper.</li>
 *   <li>Its solve divides by the diagonal of L in every column, although the diagonal of L is
 *       exactly one, and divides by the diagonal of U in every column of a serial chain of
 *       dependent operations where a division takes several times as long as a multiply.</li>
 *   <li>It copies the right-hand side twice and permutes twice; here the two permutations are
 *       composed once, when the matrix is factorised.</li>
 * </ol>
 *
 * <h2>What is unchanged</h2>
 * The factorisation itself is EJML's {@link LuUpLooking_DSCC}: left-looking, partial pivoting on
 * the largest entry, and it reports a singular matrix by returning false, as before. The ordering
 * is applied symmetrically to rows and columns ({@code B = P A P^T}), which keeps the diagonal
 * where it was, and the pivoting then does whatever it does to {@code B}. A singular {@code A} is
 * a singular {@code B}, so the "singular systems collapse to the zero state" behaviour of the
 * caller is untouched.
 *
 * <h2>What it costs</h2>
 * One extra copy of the matrix, an ordering computed whenever the sparsity pattern changes (the
 * pattern is compared on every factorisation; the ordering is cached), and a few int arrays of
 * the matrix size. The answer differs from the natural-order answer by rounding: a different
 * elimination order and a multiply by a reciprocal instead of a divide, so about one part in
 * 10^16 times the condition number, which is the same size as what the hash-set iteration order of
 * the stamps already moves it by.
 */
final class OrderedSparseLu {
    private final LuUpLooking_DSCC lu = new LuUpLooking_DSCC(null);

    // The sparsity pattern the ordering and B's structure were built for.
    private int patternN = -1;
    private int patternNnz = -1;
    private int[] patternCols = new int[0];
    private int[] patternRows = new int[0];

    /** {@code order[k]} = node of A eliminated k-th; row/column k of B is row/column order[k] of A. */
    private int[] order = new int[0];
    /** Where each stored entry of A lands in B's value array. */
    private int[] map = new int[0];
    private DMatrixSparseCSC b = new DMatrixSparseCSC(1, 1);

    // Composed permutation and reciprocal pivots, rebuilt after every factorisation.
    /** {@code scatter[k]}: position in the working vector that entry k of the input goes to (pivot row of B row k). */
    private int[] scatter = new int[0];
    private double[] invDiag = new double[0];
    private double[] work = new double[0];
    private int n;

    /** Times the ordering was recomputed, for tests and the benchmark. */
    long orderings;
    /** Nonzeros in L and U after the last factorisation, for tests and the benchmark. */
    int factorNonzeros() {
        return lu.getL().nz_length + lu.getU().nz_length;
    }

    /**
     * Factorise {@code a}. Returns false if it is singular, in which case the solve must not be used.
     */
    boolean factor(DMatrixSparseCSC a) {
        if(a.numRows != a.numCols)
            throw new IllegalArgumentException("Matrix must be square");
        n = a.numCols;
        if(!samePattern(a))
            analyse(a);

        // B takes A's values through the precomputed map: no searching, no sorting.
        var bv = b.nz_values;
        var av = a.nz_values;
        var m = map;
        for(int p = 0, nnz = a.nz_length; p < nnz; ++p)
            bv[m[p]] = av[p];

        if(!lu.decompose(b))
            return false;

        // Compose the permutations and the reciprocals so a solve does not have to.
        var pinv = lu.getPinv();
        var u = lu.getU();
        if(scatter.length < n) {
            scatter = new int[n];
            invDiag = new double[n];
            work = new double[n];
        }
        for(int k = 0; k < n; ++k) {
            scatter[k] = pinv[k];
            // Diagonal of U is the last entry of each column, which is where the factorisation
            // puts the pivot.
            var inv = 1.0 / u.nz_values[u.col_idx[k + 1] - 1];
            // A pivot so small that its reciprocal overflows is singular in every way that
            // matters: the divide the old solve did would have overflowed the answer.
            if(!Double.isFinite(inv))
                return false;
            invDiag[k] = inv;
        }
        return true;
    }

    private boolean samePattern(DMatrixSparseCSC a) {
        if(patternN != n || patternNnz != a.nz_length)
            return false;
        for(int j = 0; j <= n; ++j) {
            if(patternCols[j] != a.col_idx[j])
                return false;
        }
        var rows = a.nz_rows;
        var cached = patternRows;
        for(int p = 0, nnz = a.nz_length; p < nnz; ++p) {
            if(cached[p] != rows[p])
                return false;
        }
        return true;
    }

    /** New pattern: remember it, order it, and lay out B and the value map. */
    private void analyse(DMatrixSparseCSC a) {
        ++orderings;
        int nnz = a.nz_length;
        patternN = n;
        patternNnz = nnz;
        patternCols = Arrays.copyOf(a.col_idx, n + 1);
        patternRows = Arrays.copyOf(a.nz_rows, nnz);

        if(order.length != n)
            order = new int[n];
        MinimumDegree.order(n, patternCols, patternRows, order);
        var position = new int[n];
        for(int k = 0; k < n; ++k)
            position[order[k]] = k;

        // B[position[i], position[j]] = A[i, j]: count per new column, then place.
        var colIdx = new int[n + 1];
        for(int j = 0; j < n; ++j)
            colIdx[position[j] + 1] = patternCols[j + 1] - patternCols[j];
        for(int c = 0; c < n; ++c)
            colIdx[c + 1] += colIdx[c];
        var next = Arrays.copyOf(colIdx, n);
        var rows = new int[nnz];
        var mapping = new int[nnz];
        for(int j = 0; j < n; ++j) {
            int c = position[j];
            for(int p = patternCols[j]; p < patternCols[j + 1]; ++p) {
                int at = next[c]++;
                rows[at] = position[patternRows[p]];
                mapping[p] = at;
            }
        }
        map = mapping;

        var target = new DMatrixSparseCSC(n, n, Math.max(nnz, 1));
        System.arraycopy(colIdx, 0, target.col_idx, 0, n + 1);
        System.arraycopy(rows, 0, target.nz_rows, 0, nnz);
        target.nz_length = nnz;
        // Entries within a column are in the order of A's columns, not by row. The factorisation
        // does not need them sorted.
        target.indicesSorted = false;
        b = target;
    }

    /**
     * Solve {@code A x = rhs} for one right-hand side, in place of nothing: {@code x} is written
     * completely. {@code rhs} and {@code x} must not be the same array.
     */
    void solve(double[] rhs, double[] x) {
        var z = work;
        var to = scatter;
        var from = order;
        for(int k = 0; k < n; ++k)
            z[to[k]] = rhs[from[k]];

        // L z = z, unit diagonal so nothing to divide by; the first entry of each column is it.
        var l = lu.getL();
        var lc = l.col_idx;
        var lr = l.nz_rows;
        var lv = l.nz_values;
        for(int col = 0; col < n; ++col) {
            double zj = z[col];
            for(int p = lc[col] + 1, end = lc[col + 1]; p < end; ++p)
                z[lr[p]] -= lv[p] * zj;
        }

        // U y = z, the diagonal is the last entry of each column.
        var u = lu.getU();
        var uc = u.col_idx;
        var ur = u.nz_rows;
        var uv = u.nz_values;
        var inv = invDiag;
        for(int col = n - 1; col >= 0; --col) {
            int end = uc[col + 1] - 1;
            double zj = z[col] * inv[col];
            z[col] = zj;
            for(int p = uc[col]; p < end; ++p)
                z[ur[p]] -= uv[p] * zj;
        }

        for(int k = 0; k < n; ++k)
            x[from[k]] = z[k];
    }

    void solve(DMatrixRMaj rhs, DMatrixRMaj x) {
        if(rhs.numCols == 1 && x.numCols == 1) {
            solve(rhs.data, x.data);
            return;
        }
        // A matrix of right-hand sides: one column at a time.
        var column = new double[n];
        var result = new double[n];
        for(int c = 0; c < rhs.numCols; ++c) {
            for(int i = 0; i < n; ++i)
                column[i] = rhs.get(i, c);
            solve(column, result);
            for(int i = 0; i < n; ++i)
                x.set(i, c, result[i]);
        }
    }
}
