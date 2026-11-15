/*
 * Copyright 2026 patryk3211
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
package org.patryk3211.powergrid.electricity.sim.special;

import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

public class ThyratronWire extends ColdCathodeRegulatorTubeWire {
    private static final float EMISSION_FIRE_THRESHOLD = 0.05f;

    private final IElectricNode grid;
    private final float controlRatio;
    private float emission = 1f;
    // Set whenever a sub-tick starts with the tube lit, cleared by pollLit(); see there.
    private boolean litSincePolled;

    public ThyratronWire(float controlRatio, float breakdownVoltage, float holdingVoltage, float holdingCurrent, float dischargeConductance,
                         IElectricNode cathode, IElectricNode anode, IElectricNode grid) {
        super(breakdownVoltage, holdingVoltage, holdingCurrent, dischargeConductance, anode, cathode);
        this.grid = grid;
        this.controlRatio = controlRatio;
    }

    public void setEmission(float emission) {
        this.emission = emission;
    }

    public float getEmission() {
        return emission;
    }

    /**
     * Whether the tube was lit at any point since this was last called, which is what a block
     * entity ticking once per world tick wants to know.
     * <p>
     * {@link #isLit()} is one sample, taken wherever the last sub-tick ended, and an alternating
     * island is stepped many times per world tick with the tube striking and going out inside it.
     * Read once per tick it lands on the same point of the cycle every time at 20, 40 and 60 Hz (a
     * tube that conducts 41 percent of the time reads unlit on every one of 360 ticks) and flips
     * every other tick at 50 Hz, so the glow, the hum and the strike sound followed the sampling
     * and not the tube. An island stepped once per world tick has one sub-tick, so there this is
     * exactly {@link #isLit()}.
     * <p>
     * Deliberately not "the RMS current is above the holding current": an unlit tube still leaks
     * {@code OFF_CONDUCTANCE} times its anode voltage, which at the top of the voltage range is
     * more than the holding current.
     */
    public boolean pollLit() {
        var lit = litSincePolled || isLit();
        litSincePolled = false;
        return lit;
    }

    public double gridVoltage() {
        return grid.getVoltage() - node2.getVoltage();
    }

    public double strikeVoltage() {
        return strikeVoltage(gridVoltage());
    }

    public double strikeVoltage(double vg) {
        return Math.max(holdingVoltage, breakdownVoltage - controlRatio * vg);
    }

    /**
     * Consecutive calls to {@link #preSolve()} below the holding current that put the tube out.
     * <p>
     * preSolve runs once per sub-tick, so the two calls that hold a direct-current tube for a
     * world tick or two hold a sub-stepped one for two sub-ticks: the extinction then lags the
     * current zero by two sub-ticks while the strike lags its threshold by one, which both widens
     * the conduction angle by a sub-tick and leaves the lit tube, a source behind a conductance,
     * pushing current backwards for the extra sub-tick. One call makes the two delays equal and
     * the angle independent of the sub-tick count. An island stepped once per world tick keeps the
     * original two, which is every direct-current island at the default settings. The test is
     * whether the island is sub-stepped, not whether it alternates, so a direct-current island that
     * is sub-stepped anyway (a lockstep island, or {@code multiTicks} raised) gets one as well.
     */
    private int holdCalls() {
        return network != null && network.getMultiTick() > 1 ? 1 : 2;
    }

    @Override
    public void preSolve() {
        advance();
        // After the update, so it records the state this sub-tick is solved in. For an island
        // stepped once per world tick that is the state isLit() reports at the end of the tick.
        if (isLit())
            litSincePolled = true;
    }

    private void advance() {
        if (!isConverged())
            return;

        double V = potentialDifference();
        double I = current();
        if (litTicks <= 0) {
            if (emission > EMISSION_FIRE_THRESHOLD && V > strikeVoltage()) {
                updateConductance(dischargeConductance);
                litTicks = holdCalls();
            }
        } else if (I < holdingCurrent || V <= 0) {
            if (--litTicks <= 0) {
                updateConductance(OFF_CONDUCTANCE);
                litTicks = 0;
            }
        } else {
            litTicks = holdCalls();
        }
    }

    @Override
    public String toString() {
        return String.format("Thyratron(μ=%g Vb=%g Vh=%g Ih=%g G=%g)",
                controlRatio, breakdownVoltage, holdingVoltage, holdingCurrent, dischargeConductance);
    }
}
