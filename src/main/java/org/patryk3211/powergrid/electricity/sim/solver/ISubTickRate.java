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
 * Implemented by network elements that need the solver to advance in sub-tick steps finer than
 * one world tick.
 * <p>
 * Sub-stepping used to be a single global config value applied to every island. That is the
 * right default for direct current, where nothing changes between sub-ticks and extra steps buy
 * nothing, but an alternating source must be sampled many times per cycle or it aliases. Letting
 * an element state its own requirement means an island containing an alternator can step finely
 * while every purely DC island in the world keeps costing exactly what it cost before.
 *
 * @see org.patryk3211.powergrid.electricity.sim.ElectricalNetwork#computeSubTicks(int)
 */
public interface ISubTickRate {
    /**
     * Minimum number of solver sub-ticks per world tick this element needs to behave correctly.
     * <p>
     * The network takes the maximum over all its elements, so returning 1 means "no opinion".
     */
    default int requiredSubTicks() {
        return 1;
    }

    /**
     * Whether this element exchanges state with a element in <em>another</em> network on every
     * sub-tick.
     * <p>
     * A transmission line does exactly that: its two ports hand each other voltage and current
     * once both have solved. If the networks at the two ends were stepped at different rates one
     * port would solve many times per exchange, so any network holding such an element has to be
     * stepped in lockstep with the fastest network in the world.
     */
    default boolean requiresLockstep() {
        return false;
    }
}
