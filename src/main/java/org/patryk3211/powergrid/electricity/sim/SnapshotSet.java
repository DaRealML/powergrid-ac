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
package org.patryk3211.powergrid.electricity.sim;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import org.patryk3211.powergrid.electricity.sim.solver.SolverSwitches;

import java.util.function.IntFunction;

/**
 * An identity hash set that can hand out its members as a plain array, rebuilt only when
 * membership changes.
 * <p>
 * An island visits its hooks once per sub-tick, up to 128 times per world tick, and iterating an
 * open hash set is a walk over a sparse table with a fresh iterator each time. The membership
 * only changes when a player edits the grid, so the walk can be a scan over an array instead.
 * <p>
 * The array is built by iterating this set, so its order is exactly the order a live iteration
 * would have produced at that moment. That matters: elements are stamped and summed in iteration
 * order, floating-point addition is not associative, and a different order would be a different
 * (equally valid, but not identical) answer. A membership change bumps {@link #modifications()},
 * and the next {@link #array} call rebuilds, so the array can never be older than the set.
 * <p>
 * Every way of removing an element goes through {@link #remove}, {@link #clear} or an iterator's
 * {@code remove()}; all three are counted. Bulk operations inherited from fastutil are built on
 * those.
 */
public final class SnapshotSet<T> extends ReferenceOpenHashSet<T> {
    private int modifications;
    private Object[] cache;
    private int cacheModifications = -1;

    /** Counts changes of membership: never decreases, changes whenever an element is added or removed. */
    public int modifications() {
        return modifications;
    }

    @Override
    public boolean add(T k) {
        if(!super.add(k))
            return false;
        ++modifications;
        return true;
    }

    @Override
    public boolean remove(Object k) {
        if(!super.remove(k))
            return false;
        ++modifications;
        return true;
    }

    @Override
    public void clear() {
        // Counted even when already empty; clearing is rare and a spurious rebuild is harmless.
        super.clear();
        ++modifications;
    }

    @Override
    public ObjectIterator<T> iterator() {
        var inner = super.iterator();
        return new ObjectIterator<>() {
            @Override
            public boolean hasNext() {
                return inner.hasNext();
            }

            @Override
            public T next() {
                return inner.next();
            }

            @Override
            public void remove() {
                inner.remove();
                ++modifications;
            }
        };
    }

    /**
     * The members in iteration order. The returned array belongs to this set and must not be
     * written to; it stays valid (as a record of what the set held) if the set changes, and a
     * later call returns a new one.
     *
     * @param factory makes an array of the element type, e.g. {@code IOuterHook[]::new}. The
     *                first call after a change fixes the array type until the next change.
     */
    @SuppressWarnings("unchecked")
    public T[] array(IntFunction<T[]> factory) {
        var cached = cache;
        if(cached != null && cacheModifications == modifications && !SolverSwitches.legacyHookIteration)
            return (T[]) cached;
        var built = factory.apply(size());
        int i = 0;
        // Iterate the fastutil way directly, not through this class's counting wrapper: the
        // order is the same and there is nothing here that could remove.
        for(var it = super.iterator(); it.hasNext(); )
            built[i++] = it.next();
        cache = built;
        cacheModifications = modifications;
        return built;
    }
}
