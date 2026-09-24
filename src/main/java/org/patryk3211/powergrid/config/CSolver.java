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
    public final ConfigInt acMaxSubTicks = i(64, 1, "acMaxSubTicks", Comments.acMaxSubTicks);
    public final ConfigFloat acArmatureInductance = f(0.02f, 0, "acArmatureInductance", Comments.acArmatureInductance);

    public final ConfigBool acFineRates = b(false, "acFineRates", Comments.acFineRates);
    public final ConfigInt acMinSamplesPerCycle = i(8, 1, "acMinSamplesPerCycle", Comments.acMinSamplesPerCycle);

    public final ConfigBool solveBudgetGovernor = b(true, "solveBudgetGovernor", Comments.solveBudgetGovernor);
    public final ConfigFloat solveBudgetMs = f(20f, 0, "solveBudgetMs", Comments.solveBudgetMs);

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
        public static final String splittingTransmissionLines = "Experimental! Allows transmission lines to split large grid into smaller networks. This option should improve performance for large grids but it will result in transmission lines having a propagation delay and capacitance. On alternating current the delay is one solver sub-tick on every split line, and the error that causes depends on the whole circuit, not on the line alone. Measured on a three-phase supply at 4 Hz and 8 sub-ticks into a balanced wye load, the current is 7.3 degrees out and 4.9 % high with 1 ohm conductors into 2 ohm legs, 33.6 degrees out and 60.8 % high at 0.25 ohm (just above the default threshold), and 43.8 degrees out and 50.3 % high with 5 ohm conductors into 50 ohm legs. Conductors of different resistance are shifted by different angles, and an unbalanced load is far worse: 1 ohm conductors into 10, 100 and 100 ohm legs carry 4.6 times the unsplit current in the heavy phase. Leave this off on an AC grid.";
        public static final String splittingTransformers = "Allows transformers to split the grid. This option should improve performance but it will result in transformers having some capacitance and delay. On alternating current the delay is about 1.4 solver sub-ticks, which is a phase lag of 12 degrees at 8 sub-ticks per tick and 3 degrees at 32, and turns a delta-star bank's 30 degree shift into about 18. Leave this off on an AC grid.";

        public static final String solverAbsolutePrecision = "Absolute stopping criterion";
        public static final String solverRelativePrecision = "Relative stopping criterion";
        public static final String solverAbsoluteMinimumPrecision = "Minimum accepted precision";
        public static final String solverMaxSearchAlpha = "Maximum alpha value when performing iteration solution fitting (x1 = x0 * alpha + x1 * (1 - alpha))";

        public static final String solverSimpleMaxIterations = "Maximum solver iterations for networks without dynamic residuals";
        public static final String solverComplexMaxIterations = "Maximum solver iterations for networks with dynamic residuals";
        public static final String integrationTheta = "Weighting of the theta-method used for capacitors, inductors and coils. 1.0 is backward Euler, 0.5 is the trapezoidal rule, and the default 0.55 sits deliberately close to trapezoid. Backward Euler is heavily damped: at the top of an alternator's range a motor coil lags 40 degrees where 78 is correct, and the grid delivers 3.4 times the real power the coil turns into heat, the rest being absorbed by the integration itself. The trapezoidal rule fixes that but is not L-stable, so a capacitor across a supply rings for thousands of steps. 0.55 keeps almost all of trapezoid's accuracy while damping that ring by 18% per step. Set to 1.0 to restore the old backward Euler behaviour exactly.";
        public static final String multiTicks = "Experimental! This option enables all electrical networks to tick multiple times per world tick. This allows for better simulation precision when reactive components are involved but can have a significant impact on performance.";

        public static final String acSamplesPerCycle = "Solver samples taken per electrical cycle of an alternating source. Higher values track the waveform more accurately at a proportional cost. Only networks containing an AC source are affected; DC networks ignore this entirely.";
        public static final String acMaxSubTicks = "Upper bound on sub-ticks per world tick that an alternating source may request. This is the real cost ceiling for AC: a network containing an alternator is solved at most this many times per tick. A machine only asks for what its frequency needs, so raising this costs nothing until one runs fast enough to want it: at the default 64 a machine up to 36 Hz (8 pole pairs at full speed) gets the full 32 samples per cycle, while 50 Hz gets 26 and 72 Hz gets 18 - enough to measure, and the multimeter draws a curve through the samples it is sent. Raise to 128 for a finer solve at mains frequency, at twice the solver cost on those networks; the multimeter itself is capped by multimeterSubTickSamples (32 by default), so beyond that a higher value does not sharpen its picture. Rounded DOWN to a power of two in use, because a rate that does not divide the world tick evenly would space its sub-ticks unequally - so 100 behaves as 64. Prefer powers of two: 16, 32, 64, 128.";
        public static final String acArmatureInductance = "Armature (synchronous) inductance of an alternator winding, in henries. This is what limits circulating current when two alternators are paralleled out of phase; setting it to zero makes them ideal voltage sources that fight each other. Raising it softens the machine's response to load and increases the phase angle between voltage and current.";

        public static final String acFineRates = "Choose an island's AC sub-tick rate from a finer ladder (up to four rungs per octave) instead of only powers of two. The old rule rounds every demand up to the next power of two: at the default acSamplesPerCycle a 50Hz source wants 80 sub-ticks and is rounded up to 128, paying for waveform resolution nothing asked for. The finer ladder rounds up by at most 25% instead of up to 100%. The only visible cost is to a multimeter probe with one node on this island and one on a different island running a rate that does not divide this one - the two readings can be up to one sub-tick's worth of a world tick out of step, which a power-of-two rate never has.";
        public static final String acMinSamplesPerCycle = "The coarsest resolution the tick-budget governor (solveBudgetGovernor) may cut an over-budget island down to, in samples per electrical cycle. The governor never goes below this even under a budget it cannot meet, so a waveform never degrades past being recognisably one. Only takes effect above multiTicks and below acSamplesPerCycle.";

        public static final String solveBudgetGovernor = "Reduce the sub-tick cap of the most expensive AC islands when solving every island together is taking too much of a world tick, so one runaway network cannot alone destroy the server's TPS. Only ever lowers a rate below what acSamplesPerCycle/acMaxSubTicks would otherwise grant, never raises one above it, and never goes below acMinSamplesPerCycle or multiTicks. Disable to restore the uncapped original behaviour.";
        public static final String solveBudgetMs = "Wall-clock time per world tick that solving every AC island together may take before solveBudgetGovernor starts cutting rates. A world tick is 50ms for the whole server, shared with vanilla and every other mod, so this should leave that mostly free; see docs/perf/scheduling.md for the measurements the default was chosen from. Zero disables the governor regardless of solveBudgetGovernor.";

        public static final String bjtLimAlpha = "BJT inter-iteration voltage change smoothing multiplier";
        public static final String diodeLimAlpha = "Diode inter-iteration voltage change smoothing multiplier";
        public static final String triodeLim = "Triode inter-iteration voltage change smoothing multiplier";

        public static final String solverBackend = "Solver MNA backend. The native backend can provide platform-specific acceleration which usually improves performance, however it isn't portable and needs a special binary which might not be available for all platforms. Java backend is portable and always available as fallback.";
    }
}
