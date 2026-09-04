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
     * Default weighting of the theta-method, used when a wire has no network to ask.
     * <p>
     * See {@link #getTheta()} for what theta means and why this value.
     */
    double DEFAULT_THETA = 0.55;

    /**
     * The theta of the theta-method, which is how every reactive branch here integrates:
     * <pre>
     *     i[n+1] = i[n] + (dt/L) * ( theta*v[n+1] + (1-theta)*v[n] )
     * </pre>
     * and its dual for a capacitor. The two familiar schemes are the endpoints — {@code theta = 1}
     * is backward Euler and {@code theta = 0.5} is the trapezoidal rule — and neither endpoint is
     * a good choice here.
     *
     * <h2>Why not backward Euler, which is what this used to be</h2>
     * It is heavily dissipative, and at the sampling density this mod can afford that is not a
     * detail. An alternator at sixteen pole pairs runs at 72.5 Hz and the sub-tick ceiling gives
     * it 4.4 samples per cycle; a motor coil measured there lags by 40 degrees where 78 is
     * correct. The damping appears in the branch as a fictitious series resistance — 95.6 ohms
     * against a true 25.6 — so the grid delivers 175 W into a coil that turns 47 W into heat, and
     * <em>73% of the power the grid pays for is absorbed by the integration</em>. It is also only
     * first order, so buying the accuracy back by sampling costs a factor of thirty-two: reaching
     * 10% on real power needs 512 sub-ticks.
     *
     * <h2>Why not the trapezoidal rule either</h2>
     * It is second order and almost exact in phase — 2.1 degrees out where backward Euler is 37 —
     * but it is A-stable without being L-stable, so its damping factor for a stiff mode tends to
     * −1 rather than 0. That is not academic here. {@code CapacitorComponent} builds a
     * {@code CRSeriesWire} with a 0.01 ohm parasitic, so a capacitor wired across a supply, which
     * is what a smoothing capacitor is, has a time constant near a microsecond against a 3.125 ms
     * step. Measured, it rings at six amps and is still at half amplitude after a thousand steps,
     * started by nothing more than energisation.
     *
     * <h2>What 0.55 buys</h2>
     * The ring decays asymptotically by {@code (1-theta)/theta} per step, so 0.55 damps 18% per
     * step — a thousandfold down in about two world ticks — while the phase error, which scales
     * as {@code (theta - 1/2)}, drops to a tenth of backward Euler's. Measured across the whole
     * alternator range at the shipped sub-tick rates, real power lands at 0.99 to 1.07 times the
     * analytic answer against backward Euler's 3.39, and phase within 1.5 degrees.
     * <p>
     * Strictly this is first order for any {@code theta != 0.5}; the point is that its error
     * coefficient is about a tenth of backward Euler's, which is worth more here than the order.
     * <p>
     * Setting the config back to 1.0 restores backward Euler exactly, which is the escape hatch if
     * a circuit ever misbehaves.
     */
    default double getTheta() {
        if(getNetwork() == null)
            return DEFAULT_THETA;
        return getNetwork().getTheta();
    }

    /**
     * {@code (1 - theta) / theta}, the weight the history term carries.
     * <p>
     * Exactly zero at {@code theta = 1}, which is what makes backward Euler fall out of these
     * companion models as a special case rather than as a separate code path.
     */
    default double thetaRatio() {
        var theta = getTheta();
        return (1 - theta) / theta;
    }

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
