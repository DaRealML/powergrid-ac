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
package org.patryk3211.powergrid.electricity.sim.special;

import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.ITimeAwareWire;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.patryk3211.powergrid.electricity.sim.solver.IResidualAdder;
import org.patryk3211.powergrid.electricity.sim.solver.IStaticResidual;

public class CapacitorWire extends AbstractElectricWire implements IStaticResidual, IOuterHook, ITimeAwareWire {
    private double capacitance;
    private double Ieq;

    private double V;
    private double Iprev;

    public CapacitorWire(double capacitance, IElectricNode node1, IElectricNode node2) {
        super(node1, node2);
        this.capacitance = capacitance;
    }

    @Override
    public boolean isSource() {
        return true;
    }

    @Override
    public double conductance() {
        // dt = 50ms (1 tick)
        return capacitance / (getTheta() * getDeltaTime());
    }

    public void setVoltage(float voltage) {
        valueChange(voltage, V);
        if(Float.isFinite(voltage)) {
            Iprev = 0;
            V = voltage;
        }
    }

    @Override
    public double potentialDifference() {
        if(network == null)
            return V;
        return super.potentialDifference();
    }

    @Override
    public double current() {
        return super.current() + Ieq;
    }

    @Override
    public void postUpperSolve() {
        if(isConverged()) {
            // Trapezoid needs this step's endpoint current, which current() already is:
            // G*pd + Ieq, with Ieq computed for this step. The previous expression evaluated
            // C*(v_n - v_prev)/dt, which the trapezoid rule makes the step *average*
            // (i_n + i_prev)/2 — half the required factor and missing the -i_prev term.
            Iprev = thetaRatio() * current();
            // Save voltage with a bit of leakage, at a rate independent of the sub-tick count.
            V = potentialDifference() * ITimeAwareWire.leakageFactor(getDeltaTime());
        }
    }

    @Override
    public void addStaticResidual(IResidualAdder residual) {
        var G = conductance();
        Ieq = -G * V - Iprev;
        if(node1 != null)
            residual.add(node1.getIndex(), -Ieq);
        if(node2 != null)
            residual.add(node2.getIndex(),  Ieq);
    }

    @Override
    public String toString() {
        return String.format("Capacitor(C=%g)", capacitance);
    }
}
