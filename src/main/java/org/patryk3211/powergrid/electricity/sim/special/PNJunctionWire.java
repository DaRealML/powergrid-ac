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
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.solver.IResidualAdder;
import org.patryk3211.powergrid.electricity.sim.solver.ISolverHook;

public class PNJunctionWire extends AbstractElectricWire implements ISolverHook {
    private double temperatureCelsius;
    private final double reverseSaturationCurrent;
    private final double seriesResistance;
    private double idealityFactor;
    private final double breakdownVoltage;
    private final double breakdownSaturationCurrent;

    private double G = ElectricalNetwork.G_MIN;
    private double Ieq = 0;
    private double prevV;
    // Junction voltage (terminal voltage minus the drop across the series resistance) of the
    // previous evaluation, which is what the limiter compares against unless legacyLimiter is set.
    private double prevJunctionV;

    public int iterationLimit = -1;

    /**
     * Limit the change of the terminal voltage between Newton evaluations, as this class always
     * did, instead of the change of the junction voltage.
     * <p>
     * The limiter is only right for a bare exponential. Here the series resistance is folded into
     * the current law, so the terminal voltage of a conducting diode runs to volts and tens of
     * volts while the junction sits near a volt, and limiting that terminal voltage compresses every
     * step to a fraction of a volt: a rectifier bridge needed 80 to 150 Newton iterations per
     * sub-tick to climb from cut-off to conduction. The converged answer does not depend on the
     * limiter, only the path to it does, but that is only so where it converges: with this limiter
     * one solve in six (a grounded alternator bridge) and one in three (the same bridge floating)
     * ran to the 200 iteration cap and returned a state that satisfies no circuit equation. Kept so
     * a test can run both against the same circuit, and reproduce the original answers.
     */
    public static boolean legacyLimiter = false;

    // Terms that depend only on the temperature, the ideality factor and the constants of the
    // device, which change rarely and used to be rebuilt (with a pow and an exp) on every Newton
    // evaluation.
    private boolean termsValid;
    private double termsTemperature, termsIdeality;
    private double thermalVoltage, nVt, vtN, vCrit, satCurrent, isRs, omegaLog, breakdownOmegaLog;

    public PNJunctionWire(double reverseSaturationCurrent, double seriesResistance, double temperatureCelsius, double idealityFactor, IElectricNode node1, IElectricNode node2) {
        super(node1, node2);
        this.reverseSaturationCurrent = reverseSaturationCurrent;
        this.seriesResistance = seriesResistance;
        this.temperatureCelsius = temperatureCelsius;
        this.idealityFactor = idealityFactor;
        this.breakdownVoltage = 0;
        this.breakdownSaturationCurrent = 0;
    }

    public PNJunctionWire(double reverseSaturationCurrent, double seriesResistance, double temperatureCelsius, double idealityFactor, double breakdownVoltage, double breakdownSaturationCurrent, IElectricNode node1, IElectricNode node2) {
        super(node1, node2);
        this.reverseSaturationCurrent = reverseSaturationCurrent;
        this.seriesResistance = seriesResistance;
        this.temperatureCelsius = temperatureCelsius;
        this.idealityFactor = idealityFactor;
        this.breakdownVoltage = breakdownVoltage;
        this.breakdownSaturationCurrent = breakdownSaturationCurrent;
    }

    public static double WrightOmega(double z) {
        // D'Angelo, Gabrielli and Turchet (2019) approximation
        double x1 = -3.341459552768620;
        double x2 = 8;
        double alpha = -1.314293149877800e-3;
        double beta = 4.775931364975583e-2;
        double gamma = 3.631952663804445e-1;
        double zeta = 6.313183464296682e-1;
        if (z <= x1) {
            return 0;
        } else if (z < x2) {
            return alpha * z * z * z + beta * z * z + gamma * z + zeta;
        } else {
            return z - Math.log(z);
        }
    }

    public static double WrightOmega4(double z) {
        // D'Angelo, Gabrielli and Turchet (2019) approximation
        double w3 = WrightOmega(z);
        return w3 - (w3 - Math.exp(z - w3)) / (w3 + 1);
    }

    public double pnLim(double V1, double V0, double Vcrit, double V_T) {
        if(V0 < 0 && V1 > Vcrit)
            return Vcrit;
        if(V0 >= 0 && V0 < Vcrit && V1 > Vcrit)
            return Vcrit;
        double dV = V1 - V0;
        if(V1 > Vcrit && Math.abs(dV) > V_T * 2) {
            double arg = dV / (idealityFactor * V_T);
            if(arg + 1 < 0)
                return Vcrit;
            return V0 + idealityFactor * V_T * Math.log1p(arg);
        }
        return V1;
    }

    public void setTemperatureCelsius(double temperatureCelsius) {
        this.temperatureCelsius = temperatureCelsius;
        termsValid = false;
    }

    @Override
    public double current() {
        return this.Ieq + this.G * potentialDifference();
    }

    @Override
    public double conductance() {
        return G;
    }

    @Override
    public void startIteration(int iteration) {
        if(iterationLimit > 0 && iteration > iterationLimit)
            return;
        if(!termsValid || termsTemperature != temperatureCelsius || termsIdeality != idealityFactor)
            computeTerms();
        double V_T = thermalVoltage;
        double R_s = seriesResistance;
        double I_s2 = satCurrent;
        double V = potentialDifference();
        double WTerm, I;
        if(legacyLimiter) {
            prevV = V = pnLim(V, prevV, vCrit, V_T);
            // Banwell and Jayakumar (2000)
            WTerm = WrightOmega4(omegaLog + (isRs + V) / nVt);
            I = vtN * WTerm / R_s - I_s2;
        } else {
            WTerm = WrightOmega4(omegaLog + (isRs + V) / nVt);
            I = vtN * WTerm / R_s - I_s2;
            // The junction is what the exponential acts on; V - I*Rs is its voltage.
            double junction = V - R_s * I;
            if(junction > vCrit) {
                double limited = pnLim(junction, prevJunctionV, vCrit, V_T);
                if(limited != junction) {
                    // Re-evaluate at the limited junction voltage. The exponential is inverted
                    // in closed form, so no second Wright omega is needed.
                    junction = limited;
                    double e = Math.exp(junction / nVt);
                    I = I_s2 * (e - 1);
                    V = junction + R_s * I;
                    WTerm = R_s * I_s2 * e / nVt;
                }
            }
            prevJunctionV = junction;
        }
        double G = Math.max(WTerm / (R_s * (1 + WTerm)), ElectricalNetwork.G_MIN);

        if(breakdownVoltage > 0) {
            // Reverse breakdown using a shifted diode current curve
            double V_over = -breakdownVoltage - V; //so only when in reverse bias
            double B_Omega_arg = breakdownOmegaLog + (breakdownSaturationCurrent * R_s + V_over) / nVt;
            // Far from breakdown the argument is so negative that exp() underflows to exactly zero,
            // which makes this term exactly zero: skip the evaluation, not the arithmetic below.
            double B_WTerm = B_Omega_arg < -746 ? 0 : WrightOmega4(B_Omega_arg);
            G += Math.max(B_WTerm / (R_s * (1 + B_WTerm)), ElectricalNetwork.G_MIN);
            I -= vtN * B_WTerm / R_s - breakdownSaturationCurrent;
        }

        // Adding a resistor across the diode helps with convergence in certain cases.
        double G_add = 1e-6;
        if(iteration > 100) {
            G_add = 1e-4;
        }
        G += G_add;
        network.updateConductance(this, G - this.G);
        this.G = G;

        // Compute Ieq
        this.Ieq = I - G * V;
    }

    @Override
    public void addResidual(IResidualAdder residual) {
        residual.add(node1.getIndex(), Ieq);
        residual.add(node2.getIndex(), -Ieq);
    }

    public void setIdealityFactor(double idealityFactor) {
        this.idealityFactor = idealityFactor;
        termsValid = false;
    }

    private void computeTerms() {
        double k = 1.380649e-23; // Boltzmann constant in J/K
        double q = 1.602176634e-19; // Elementary charge in C
        double n = idealityFactor;
        double V_T = (k * (temperatureCelsius + 273.15)) / q; // Thermal voltage in V
        thermalVoltage = V_T;
        nVt = n * V_T;
        vtN = V_T * n;
        vCrit = n * V_T * Math.log(V_T / (reverseSaturationCurrent * Math.sqrt(2)));
        double I_s1 = reverseSaturationCurrent;
        double E_g = 1.12; // Silicon bandgap energy in eV
        double T_1 = 22 + 273.15; // Reference temperature in K
        double T_2 = temperatureCelsius + 273.15; // Actual temperature in K
        double T_2_div_T_1 = T_2 / T_1;
        satCurrent = I_s1 * Math.pow(T_2_div_T_1, 3/n) * Math.exp(- (q * E_g / k / T_2 / n) * (1 - T_2_div_T_1));
        isRs = satCurrent * seriesResistance;
        omegaLog = Math.log(isRs / n / V_T);
        breakdownOmegaLog = Math.log(breakdownSaturationCurrent * seriesResistance / n / V_T);
        termsTemperature = temperatureCelsius;
        termsIdeality = idealityFactor;
        termsValid = true;
    }

    @Override
    public String toString() {
        return String.format("PNJunction(Is=%g)#%d", reverseSaturationCurrent, System.identityHashCode(this));
    }
}