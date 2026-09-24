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

/**
 * Switches that put the solver's original code path back, for tests and benchmarks.
 * <p>
 * Every fast path added to the solver keeps the code it replaced, and one of these decides which
 * runs. They exist so that a differential test can run the same circuit down both paths and
 * compare, and so that a benchmark can time both in one JVM. Nothing in the game reads or writes
 * them: they default to the fast paths, and a system property of the same name (for example
 * {@code -Dpowergrid.solver.legacySparseLu=true}) flips one for a whole run.
 * <p>
 * The fields are plain, not volatile: they are set before a network is built and solved, from the
 * one thread that then solves it, and a read on the hot path must cost nothing.
 */
public final class SolverSwitches {
    private SolverSwitches() { }

    private static boolean flag(String name) {
        return Boolean.getBoolean("powergrid.solver." + name);
    }

    /**
     * Read node values and stamp static residuals the way they were done before the island loop was
     * trimmed: through a hash lookup that boxes its result, and by asking every residual for the
     * nodes it touches on every sub-tick.
     */
    public static boolean legacyValueAccess = flag("legacyValueAccess");

    /**
     * Rebuild the array of hooks from the hash set on every use instead of caching it until the
     * membership changes. The order is the same either way; this exists to prove that.
     */
    public static boolean legacyHookIteration = flag("legacyHookIteration");

    /** Factorise sparse matrices with EJML's LU on the natural ordering and solve with EJML's triangular solves. */
    public static boolean legacySparseLu = flag("legacySparseLu");

    /** Run a linear island's sub-tick as three separate passes over the residual, as it was before they were fused. */
    public static boolean legacyLinearPath = flag("legacyLinearPath");
}
