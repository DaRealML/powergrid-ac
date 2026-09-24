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

import java.util.Arrays;

/**
 * Minimum-degree elimination ordering of a sparse matrix's graph.
 * <p>
 * Eliminating a node of a circuit's admittance matrix connects all of its remaining neighbours to
 * each other, and every such new connection is a nonzero the factorisation has to store and
 * compute. Which node goes first decides how many appear. The natural order (the order nodes were
 * added to the island) is whatever the player built first, and on a mesh, a bus with taps or a
 * transformer bank it can produce ten to fifty times more nonzeros than the matrix started with.
 * Always eliminating a node with the fewest neighbours is the classic cheap answer.
 * <p>
 * This is the plain form of the algorithm: an explicit elimination graph, exact degrees, ties
 * broken by the lower index, so the result is deterministic. Islands are a few hundred nodes and
 * the ordering is only recomputed when the sparsity pattern changes, so the simple version is
 * fast enough (about a millisecond for a 300 node mesh) and easy to reason about; approximate
 * degrees and quotient graphs would pay off at sizes an island never reaches.
 */
final class MinimumDegree {
    private MinimumDegree() { }

    /**
     * Order the nodes of the graph of {@code A + A^T}. The diagonal and the values are ignored.
     *
     * @param n       matrix dimension
     * @param colIdx  CSC column pointers, {@code n + 1} entries
     * @param rows    CSC row indices; entries {@code [0, colIdx[n])} are read
     * @param order   receives the elimination order: {@code order[k]} is the node eliminated
     *                {@code k}-th, all of {@code 0..n-1} exactly once
     */
    static void order(int n, int[] colIdx, int[] rows, int[] order) {
        // Symmetric adjacency without duplicates or the diagonal: count, then fill.
        var degree = new int[n];
        for(int j = 0; j < n; ++j) {
            for(int p = colIdx[j]; p < colIdx[j + 1]; ++p) {
                int i = rows[p];
                if(i != j) {
                    ++degree[i];
                    ++degree[j];
                }
            }
        }
        var adj = new int[n][];
        for(int i = 0; i < n; ++i)
            adj[i] = new int[degree[i]];
        var fill = new int[n];
        for(int j = 0; j < n; ++j) {
            for(int p = colIdx[j]; p < colIdx[j + 1]; ++p) {
                int i = rows[p];
                if(i != j) {
                    adj[i][fill[i]++] = j;
                    adj[j][fill[j]++] = i;
                }
            }
        }
        // A pattern that is already symmetric listed every edge twice, so dedupe with a stamp.
        var seen = new int[n];
        Arrays.fill(seen, -1);
        for(int i = 0; i < n; ++i) {
            int kept = 0;
            var list = adj[i];
            seen[i] = i;
            for(int q = 0; q < list.length; ++q) {
                int w = list[q];
                if(seen[w] != i) {
                    seen[w] = i;
                    list[kept++] = w;
                }
            }
            adj[i] = kept == list.length ? list : Arrays.copyOf(list, kept);
            degree[i] = kept;
        }

        // Min-heap of (degree, node) packed in a long, with lazy deletion: a stale entry is one
        // whose degree no longer matches, or whose node has gone.
        var heap = new long[Math.max(4 * n, 16)];
        int heapSize = 0;
        for(int i = 0; i < n; ++i)
            heapSize = push(heap, heapSize, degree[i], i);

        var eliminated = new boolean[n];
        Arrays.fill(seen, -1);
        int stamp = 0;
        for(int k = 0; k < n; ++k) {
            int v;
            while(true) {
                long top = heap[0];
                heapSize = pop(heap, heapSize);
                v = (int) (top & 0xffffffffL);
                if(!eliminated[v] && (int) (top >>> 32) == degree[v])
                    break;
            }
            eliminated[v] = true;
            order[k] = v;

            // v's neighbours become a clique, and v leaves the graph.
            var nb = adj[v];
            for(int u : nb) {
                ++stamp;
                var old = adj[u];
                var merged = new int[old.length + nb.length];
                int count = 0;
                seen[u] = stamp;
                seen[v] = stamp;
                for(int w : old) {
                    if(w != v) {
                        merged[count++] = w;
                        seen[w] = stamp;
                    }
                }
                for(int w : nb) {
                    if(seen[w] != stamp)
                        merged[count++] = w;
                }
                adj[u] = Arrays.copyOf(merged, count);
                degree[u] = count;
                if(heapSize == heap.length)
                    heap = Arrays.copyOf(heap, heap.length * 2);
                heapSize = push(heap, heapSize, count, u);
            }
            adj[v] = null;
        }
    }

    private static int push(long[] heap, int size, int degree, int node) {
        long key = ((long) degree << 32) | node;
        int i = size;
        while(i > 0) {
            int parent = (i - 1) >>> 1;
            if(heap[parent] <= key)
                break;
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = key;
        return size + 1;
    }

    private static int pop(long[] heap, int size) {
        int last = size - 1;
        long key = heap[last];
        int i = 0;
        int half = last >>> 1;
        while(i < half) {
            int child = 2 * i + 1;
            if(child + 1 < last && heap[child + 1] < heap[child])
                ++child;
            if(heap[child] >= key)
                break;
            heap[i] = heap[child];
            i = child;
        }
        if(last > 0)
            heap[i] = key;
        return last;
    }
}
