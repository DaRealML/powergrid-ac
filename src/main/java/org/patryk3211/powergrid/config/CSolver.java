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
package org.patryk3211.powergrid.config;

import net.createmod.catnip.config.ConfigBase;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.solver.IMNA;
import org.patryk3211.powergrid.electricity.sim.solver.JavaMNA;
import org.patryk3211.powergrid.electricity.sim.solver.NativeMNA;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public class CSolver extends ConfigBase {
    public final ConfigFloat transmissionLineThreshold = f(0.2f, 0, "transmissionLineThreshold", Comments.transmissionLineThreshold);
    public final ConfigBool splittingTransmissionLines = b(false, "splittingTransmissionLines", Comments.splittingTransmissionLines);
    public final ConfigBool splittingTransformers = b(false, "splittingsTransformers", Comments.splittingTransformers);

    public final ConfigInt solverSimpleMaxIterations = i(200, "solverSimpleMaxIterations", Comments.solverSimpleMaxIterations);
    public final ConfigInt solverComplexMaxIterations = i(200, "solverComplexMaxIterations", Comments.solverComplexMaxIterations);

    public final ConfigFloat solverAbsolutePrecision = f(1e-7f, 0, "solverAbsolutePrecision", Comments.solverAbsolutePrecision);
    public final ConfigFloat solverRelativePrecision = f(1e-14f, 0, "solverRelativePrecision", Comments.solverRelativePrecision);
    public final ConfigFloat solverAbsoluteMinimumPrecision = f(1e-6f, 0, "solverAbsoluteMinimumPrecision", Comments.solverAbsoluteMinimumPrecision);
    public final ConfigFloat solverMaxSearchAlpha = f(0.99f, 0, 1, "solverMaxSearchAlpha", Comments.solverMaxSearchAlpha);

    public final ConfigFloat bjtLimAlpha = f(0.5f, 0, 1, "bjtLimAlpha", Comments.bjtLimAlpha);
    public final ConfigFloat diodeLimAlpha = f(0.025f, 0, 1, "diodeLimAlpha", Comments.diodeLimAlpha);

    public final ConfigFloat triodeLimAnode = f(0.5f, 0, 1, "triodeLimAnode", Comments.triodeLim);
    public final ConfigFloat triodeLimCathode = f(0.5f, 0, 1, "triodeLimCathode", Comments.triodeLim);
    public final ConfigFloat triodeLimGrid = f(0.5f, 0, 1, "triodeLimGrid", Comments.triodeLim);

    public final ConfigBool seriesWireOptimization = b(false, "seriesWireOptimization");

    public final ConfigFloat integrationTheta = f(0.55f, 0.5f, 1.0f, "integrationTheta", Comments.integrationTheta);

    public final ConfigInt multiTicks = i(1, 1, "multiTicks", Comments.multiTicks);

    public final ConfigInt acSamplesPerCycle = i(32, 4, "acSamplesPerCycle", Comments.acSamplesPerCycle);
    public final ConfigInt acMaxSubTicks = i(32, 1, "acMaxSubTicks", Comments.acMaxSubTicks);
    public final ConfigFloat acArmatureInductance = f(0.02f, 0, "acArmatureInductance", Comments.acArmatureInductance);

    public final ConfigEnum<SolverBackend> solverBackend = e(SolverBackend.NATIVE, "solverBackend", Comments.solverBackend);

    @Override
    public String getName() {
        return "solver";
    }

    public enum SolverBackend {
        JAVA(() -> true, () -> JavaMNA::new),
        NATIVE(NativeMNA::isSupported, () -> NativeMNA::new);

        final BooleanSupplier checkSupport;
        final Supplier<Function<ElectricalNetwork, IMNA>> constructor;

        SolverBackend(BooleanSupplier checkSupport, Supplier<Function<ElectricalNetwork, IMNA>> constructor) {
            this.checkSupport = checkSupport;
            this.constructor = constructor;
        }

        public boolean isSupported() {
            return checkSupport.getAsBoolean();
        }

        public IMNA create(ElectricalNetwork network) {
            return constructor.get().apply(network);
        }
    }

    private static class Comments {
        public static final String transmissionLineThreshold = "Threshold resistance for a transmission line to be able to split the grid into island networks. Lines with resistance above this value have a propagation delay of roughly 1 tick, and can improve performance by simulating small segments of the grid separately.";
        public static final String splittingTransmissionLines = "Experimental! Allows transmission lines to split large grid into smaller networks. This option should improve performance for large grids but it will result in transmission lines having a propagation delay and capacitance.";
        public static final String splittingTransformers = "Allows transformers to split the grid. This option should improve performance but it will result in transformers having some capacitance and delay.";

        public static final String solverAbsolutePrecision = "Absolute stopping criterion";
        public static final String solverRelativePrecision = "Relative stopping criterion";
        public static final String solverAbsoluteMinimumPrecision = "Minimum accepted precision";
        public static final String solverMaxSearchAlpha = "Maximum alpha value when performing iteration solution fitting (x1 = x0 * alpha + x1 * (1 - alpha))";

        public static final String solverSimpleMaxIterations = "Maximum solver iterations for networks without dynamic residuals";
        public static final String solverComplexMaxIterations = "Maximum solver iterations for networks with dynamic residuals";
        public static final String integrationTheta = "Weighting of the theta-method used for capacitors, inductors and coils. 1.0 is backward Euler, 0.5 is the trapezoidal rule, and the default 0.55 sits deliberately close to trapezoid. Backward Euler is heavily damped: at the top of an alternator's range a motor coil lags 40 degrees where 78 is correct, and the grid delivers 3.4 times the real power the coil turns into heat, the rest being absorbed by the integration itself. The trapezoidal rule fixes that but is not L-stable, so a capacitor across a supply rings for thousands of steps. 0.55 keeps almost all of trapezoid's accuracy while damping that ring by 18% per step. Set to 1.0 to restore the old backward Euler behaviour exactly.";
        public static final String multiTicks = "Experimental! This option enables all electrical networks to tick multiple times per world tick. This allows for better simulation precision when reactive components are involved but can have a significant impact on performance.";

        public static final String acSamplesPerCycle = "Solver samples taken per electrical cycle of an alternating source. Higher values track the waveform more accurately at a proportional cost. Only networks containing an AC source are affected; DC networks ignore this entirely.";
        public static final String acMaxSubTicks = "Upper bound on sub-ticks per world tick that an alternating source may request. This is the real cost ceiling for AC: a network containing an alternator is solved at most this many times per tick. Rounded DOWN to a power of two in use, because a rate that does not divide the world tick evenly would space its sub-ticks unequally - so 100 behaves as 64. Prefer powers of two: 16, 32, 64, 128.";
        public static final String acArmatureInductance = "Armature (synchronous) inductance of an alternator winding, in henries. This is what limits circulating current when two alternators are paralleled out of phase; setting it to zero makes them ideal voltage sources that fight each other. Raising it softens the machine's response to load and increases the phase angle between voltage and current.";

        public static final String bjtLimAlpha = "BJT inter-iteration voltage change smoothing multiplier";
        public static final String diodeLimAlpha = "Diode inter-iteration voltage change smoothing multiplier";
        public static final String triodeLim = "Triode inter-iteration voltage change smoothing multiplier";

        public static final String solverBackend = "Solver MNA backend. The native backend can provide platform-specific acceleration which usually improves performance, however it isn't portable and needs a special binary which might not be available for all platforms. Java backend is portable and always available as fallback.";
    }
}
