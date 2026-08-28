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
package org.patryk3211.powergrid.electricity.sim.node;

public interface ITimeAwareWire extends INetworkElement {
    /**
     * Selects trapezoidal instead of backward-Euler companion models.
     * <p>
     * <b>Leave this false.</b> The trapezoidal branches guarded by it are not a correct
     * trapezoidal companion model: each one stores the step-averaged state variable in
     * {@code postUpperSolve} where the model requires the endpoint value. The result is a
     * first-order two-step scheme carrying a parasitic root, which on an alternating supply is
     * substantially <em>less</em> accurate than the backward Euler it would replace — a 100 uF
     * capacitor at 20 Hz and 16 sub-ticks reads 12% low against backward Euler's 0.7% high, and
     * its phase error has the wrong sign, so it reports sourcing real power rather than
     * absorbing it. The expressions have since been corrected so the branch is right if anyone
     * does enable it, but plain trapezoid is also not L-stable: it settles to a persistent
     * point-to-point oscillation on stiff branches, and this mod's capacitor and inductor
     * components carry 0.01 ohm parasitics that put them far into that regime. Enabling it
     * would need damping, an L-stable scheme such as TR-BDF2, or a forced Euler step after
     * every switching event.
     */
    boolean TRAPEZOID_APPROX = false;

    /** Fraction of stored energy state retained per world tick. */
    double LEAKAGE_PER_TICK = 0.99999;

    default double getDeltaTime() {
        if(getNetwork() == null)
            return 0.05f;
        return getNetwork().getDeltaTime();
    }

    /**
     * Leakage factor for one sub-tick of the given length.
     * <p>
     * The reactive components bleed a little stored state each step so a floating charge decays
     * instead of persisting forever. Applying a fixed factor per <em>sub-tick</em> made that
     * decay depend on how finely the island was being stepped: at sixteen sub-ticks a capacitor
     * lost its charge sixteen times faster per second of world time than the same capacitor on a
     * direct-current island, purely because an alternator elsewhere on the grid had spun up.
     * Raising the factor to the timestep ratio makes the loss a rate per unit time instead.
     * <p>
     * At one sub-tick the exponent is exactly 1.0 and {@link Math#pow} is specified to return
     * the base unchanged, so existing direct-current behaviour is bit-for-bit identical.
     */
    static double leakageFactor(double deltaTime) {
        return Math.pow(LEAKAGE_PER_TICK, deltaTime / 0.05);
    }
}
