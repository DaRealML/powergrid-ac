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
package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;

/**
 * Banks of three single-phase transformers wired star and delta, which is how the mod does
 * three-phase transmission: there is no three-phase transformer block, because three ordinary
 * ones already are one.
 *
 * <h2>What these establish</h2>
 * That the transformer block's circuit, wired into the four standard banks, reproduces the phase
 * shifts and ratios in every textbook: star-star and delta-delta shift nothing, delta-star steps
 * the phase voltage up by root three and moves it thirty degrees forward, star-delta moves the line
 * voltage thirty degrees back. None of that needed any change to the solver -- a probe before this
 * file was written measured 414.3 V at +30.0 degrees against an ideal 415.7 V -- so these pin it.
 * <p>
 * And the reason distribution banks carry a neutral: on an unbalanced load a star with its
 * neutral connected holds every phase at its voltage, while the same load with the neutral left
 * off floats its star point to wherever Millman's theorem puts it.
 *
 * <h2>Why each transformer is stamped here rather than built</h2>
 * The block entity needs a world. {@link #transformer} reproduces
 * {@code TransformerBlockEntity.buildCircuit} line for line for a small core at the shipped
 * config -- including its habit of swapping the two coils when the primary has more turns -- so
 * what is tested is the circuit a player's transformer actually builds.
 */
public class ThreePhaseTransmissionTest extends TestHelper {
    // The shipped smallCoreAl, smallCoreK and transformerMutualInductanceMultiplier.
    private static final double CORE_AL = 1.5;
    private static final double CORE_K = 0.9999f;
    private static final float MUTUAL_MULTIPLIER = 10;

    private static final double PHASE_PEAK = 240;
    // Eight sub-ticks are 160 samples a second, so 4 Hz is exactly 40 per cycle. Nothing here is
    // reactive, so the frequency affects no answer -- only whether the fitting window is whole.
    private static final double FREQUENCY = 4;
    private static final int SUB_TICKS = 8;
    private static final int SAMPLES_PER_CYCLE = 40;
    private static final double ROOT3 = Math.sqrt(3);

    /** Mirrors {@code TransformerBlockEntity.buildCircuit} for two defined coils, not splitting. */
    private static void transformer(Network net, int primaryTurns, int secondaryTurns,
                                    IElectricNode p1, IElectricNode p2, IElectricNode s1, IElectricNode s2) {
        double primaryInductance = primaryTurns * primaryTurns * CORE_AL;
        double secondaryInductance = secondaryTurns * secondaryTurns * CORE_AL;
        if(primaryTurns > secondaryTurns) {
            var turns = primaryTurns;
            primaryTurns = secondaryTurns;
            secondaryTurns = turns;
            var inductance = primaryInductance;
            primaryInductance = secondaryInductance;
            secondaryInductance = inductance;
            var n1 = p1;
            var n2 = p2;
            p1 = s1;
            p2 = s2;
            s1 = n1;
            s2 = n2;
        }
        float ratio = (float) secondaryTurns / primaryTurns;
        double mutualInductance = CORE_K * primaryInductance;
        float primaryStray = (float) (primaryInductance - mutualInductance);
        float secondaryStray = (float) (secondaryInductance - ratio * ratio * mutualInductance);

        var t = net.N();
        net.W(primaryStray, p1, t);
        net.W((float) mutualInductance * MUTUAL_MULTIPLIER, t, p2);
        net.TR(ratio, secondaryStray, t, p2, s1, s2);
    }

    /** A star-connected supply with its neutral grounded, in the L1-L2-L3 sequence. */
    private record Supply(Network net, IElectricNode neutral, IElectricNode[] lines) { }

    private static Supply supply() {
        var net = new Network();
        var neutral = net.N();
        net.network.addNode(new VoltageSourceCoupling(neutral, null, 0f, 0f));
        var lines = new IElectricNode[3];
        for(int k = 0; k < 3; ++k) {
            var line = new FloatingNode();
            var source = new ACVoltageSourceCoupling(line, null, 0.001f, PHASE_PEAK, FREQUENCY);
            source.setPhaseOffset(Math.toRadians(-120 * k));
            net.network.addNode(line);
            net.network.addNode(source);
            lines[k] = line;
        }
        return new Supply(net, neutral, lines);
    }

    private static IElectricNode[] nodes(Network net, int count) {
        var nodes = new IElectricNode[count];
        for(int k = 0; k < count; ++k)
            nodes[k] = net.N();
        return nodes;
    }

    private enum Winding { STAR, DELTA }

    /** A bank's secondary terminals: three lines, and the star point when there is one. */
    private record Bank(IElectricNode[] lines, IElectricNode neutral) { }

    /**
     * Three transformers between the supply and a new set of lines.
     * <p>
     * Star: winding k from line k to a common point. Delta: winding k from line k to line k+1. The
     * primary star point is the supply's neutral, which is how a star primary is fed in practice.
     */
    private static Bank bank(Supply supply, Winding primary, Winding secondary, int primaryTurns, int secondaryTurns) {
        var net = supply.net;
        var out = nodes(net, 3);
        var star = secondary == Winding.STAR ? net.N() : null;
        for(int k = 0; k < 3; ++k) {
            var p1 = supply.lines[k];
            var p2 = primary == Winding.STAR ? supply.neutral : supply.lines[(k + 1) % 3];
            var s1 = out[k];
            var s2 = secondary == Winding.STAR ? star : out[(k + 1) % 3];
            transformer(net, primaryTurns, secondaryTurns, p1, p2, s1, s2);
        }
        return new Bank(out, star);
    }

    /** Samples of V(a) - V(b) for each pair, over two cycles, after the supply has settled. */
    private static double[][] record(Network net, IElectricNode[][] pairs) {
        for(int t = 0; t < 4; ++t)
            net.network.calculate(SUB_TICKS);
        var samples = new double[pairs.length][2 * SAMPLES_PER_CYCLE];
        var at = 0;
        while(at < samples[0].length) {
            net.network.prepare(SUB_TICKS);
            for(int s = 0; s < SUB_TICKS; ++s, ++at) {
                net.network.singleTick();
                for(int p = 0; p < pairs.length; ++p)
                    samples[p][at] = pairs[p][0].getVoltage() - pairs[p][1].getVoltage();
            }
        }
        return samples;
    }

    private static PhasorFit.Phasor[] measure(Network net, IElectricNode[]... pairs) {
        var samples = record(net, pairs);
        var out = new PhasorFit.Phasor[pairs.length];
        for(int p = 0; p < pairs.length; ++p)
            out[p] = PhasorFit.fit(samples[p], SAMPLES_PER_CYCLE);
        return out;
    }

    /** Balanced 10 ohm loads from each line to {@code point}. */
    private static void starLoad(Supply supply, IElectricNode[] lines, IElectricNode point, float... ohms) {
        for(int k = 0; k < 3; ++k)
            supply.net.W(ohms.length == 0 ? 10f : ohms[k], lines[k], point);
    }

    // A tolerance on magnitude covering the winding and source resistances the load current flows
    // through -- about 0.3 % at 10 ohms -- and on angle, the float precision of the stamps. Both
    // are far tighter than any mistake in the wiring would be: the smallest wrong answer here is
    // thirty degrees or a factor of root three.
    private static final double MAGNITUDE = 0.01;
    private static final double DEGREES = 0.05;

    @Test
    void deltaStarStepsThePhaseVoltageUpByRootThreeAndThirtyDegreesForward() {
        // Dyn11, the usual distribution transformer. Each secondary winding sees a primary LINE
        // voltage, so the phase voltage it produces is root three larger than the supply's phase
        // voltage, and leads it by the thirty degrees a line voltage leads its phase.
        var supply = supply();
        var bank = bank(supply, Winding.DELTA, Winding.STAR, 10, 10);
        starLoad(supply, bank.lines, bank.neutral);

        var m = measure(supply.net,
                new IElectricNode[]{ supply.lines[0], supply.neutral },
                new IElectricNode[]{ bank.lines[0], bank.neutral },
                new IElectricNode[]{ bank.lines[1], bank.neutral },
                new IElectricNode[]{ bank.lines[0], bank.lines[1] });
        var supplyPhase = m[0];

        Assertions.assertEquals(ROOT3 * PHASE_PEAK, m[1].magnitude(), ROOT3 * PHASE_PEAK * MAGNITUDE,
                "Delta-star should step the phase voltage up by root three");
        Assertions.assertEquals(30, m[1].degreesFrom(supplyPhase), DEGREES,
                "And shift it thirty degrees forward");
        Assertions.assertEquals(-120, m[2].degreesFrom(m[1]), DEGREES,
                "The secondary keeps the supply's phase sequence");
        Assertions.assertEquals(3 * PHASE_PEAK, m[3].magnitude(), 3 * PHASE_PEAK * MAGNITUDE,
                "Its line voltage is root three times its phase voltage again");
        Assertions.assertEquals(60, m[3].degreesFrom(supplyPhase), DEGREES);
    }

    @Test
    void starDeltaMovesTheLineVoltageThirtyDegreesBack() {
        // Yd1. Each delta winding sits across two secondary lines and is driven by one primary
        // PHASE voltage, so the secondary line voltage is the supply's phase voltage, in phase with
        // it -- thirty degrees behind the primary line voltage. With no star point the secondary
        // has no phase voltage to speak of, so the load goes across the lines.
        var supply = supply();
        var bank = bank(supply, Winding.STAR, Winding.DELTA, 10, 10);
        for(int k = 0; k < 3; ++k)
            supply.net.W(10f, bank.lines[k], bank.lines[(k + 1) % 3]);

        var m = measure(supply.net,
                new IElectricNode[]{ supply.lines[0], supply.lines[1] },
                new IElectricNode[]{ bank.lines[0], bank.lines[1] });

        Assertions.assertEquals(PHASE_PEAK, m[1].magnitude(), PHASE_PEAK * MAGNITUDE,
                "A star-delta bank's line voltage is the supply's phase voltage times the ratio");
        Assertions.assertEquals(-30, m[1].degreesFrom(m[0]), DEGREES,
                "And lags the supply's line voltage by thirty degrees");
    }

    @Test
    void starStarAndDeltaDeltaShiftNothing() {
        for(var winding : Winding.values()) {
            var supply = supply();
            var bank = bank(supply, winding, winding, 10, 10);
            for(int k = 0; k < 3; ++k)
                supply.net.W(10f, bank.lines[k], bank.lines[(k + 1) % 3]);

            var m = measure(supply.net,
                    new IElectricNode[]{ supply.lines[0], supply.lines[1] },
                    new IElectricNode[]{ bank.lines[0], bank.lines[1] });
            Assertions.assertEquals(ROOT3 * PHASE_PEAK, m[1].magnitude(), ROOT3 * PHASE_PEAK * MAGNITUDE,
                    winding + "-" + winding + " should pass the line voltage through at one to one");
            Assertions.assertEquals(0, m[1].degreesFrom(m[0]), DEGREES,
                    winding + "-" + winding + " should not shift the phase");
        }
    }

    @Test
    void theTurnsRatioScalesTheWholeBank() {
        // 10:40 delta-star: four times the ratio, root three from the connection, twelve times
        // the bus voltage between secondary lines. And the same thirty degrees, since the ratio
        // does not enter the angle.
        var supply = supply();
        var bank = bank(supply, Winding.DELTA, Winding.STAR, 10, 40);
        starLoad(supply, bank.lines, bank.neutral, 160f, 160f, 160f);

        var m = measure(supply.net,
                new IElectricNode[]{ supply.lines[0], supply.neutral },
                new IElectricNode[]{ bank.lines[0], bank.neutral });
        Assertions.assertEquals(4 * ROOT3 * PHASE_PEAK, m[1].magnitude(), 4 * ROOT3 * PHASE_PEAK * MAGNITUDE);
        Assertions.assertEquals(30, m[1].degreesFrom(m[0]), DEGREES);

        // And down again: 40:10 exercises the coil swap in buildCircuit.
        var down = supply();
        var downBank = bank(down, Winding.DELTA, Winding.STAR, 40, 10);
        starLoad(down, downBank.lines, downBank.neutral);
        var d = measure(down.net,
                new IElectricNode[]{ down.lines[0], down.neutral },
                new IElectricNode[]{ downBank.lines[0], downBank.neutral });
        Assertions.assertEquals(ROOT3 * PHASE_PEAK / 4, d[1].magnitude(), ROOT3 * PHASE_PEAK / 4 * MAGNITUDE);
        Assertions.assertEquals(30, d[1].degreesFrom(d[0]), DEGREES);
    }

    /** Magnitudes of the three load phase voltages for loads of 10, 100 and 100 ohms. */
    private static double[] unbalancedPhaseVoltages(boolean neutralConnected) {
        var supply = supply();
        var bank = bank(supply, Winding.DELTA, Winding.STAR, 10, 10);
        var loadStar = neutralConnected ? bank.neutral : supply.net.N();
        starLoad(supply, bank.lines, loadStar, 10f, 100f, 100f);

        var m = measure(supply.net,
                new IElectricNode[]{ bank.lines[0], loadStar },
                new IElectricNode[]{ bank.lines[1], loadStar },
                new IElectricNode[]{ bank.lines[2], loadStar });
        return new double[]{ m[0].magnitude(), m[1].magnitude(), m[2].magnitude() };
    }

    @Test
    void anUnbalancedLoadOnAConnectedNeutralKeepsItsVoltages() {
        // The neutral carries the difference between the phase currents, so each load stays across
        // its own winding. Delta-star is what makes this work from a three-wire feed: the delta
        // circulates the unbalanced part on the primary side instead of needing a neutral there.
        var v = unbalancedPhaseVoltages(true);
        var nominal = ROOT3 * PHASE_PEAK;
        for(int k = 0; k < 3; ++k)
            Assertions.assertEquals(nominal, v[k], nominal * MAGNITUDE,
                    "With the neutral connected phase " + (k + 1) + " should hold its voltage, got " + v[k]);
    }

    @Test
    void anUnbalancedLoadWithoutItsNeutralFloatsTheStarPoint() {
        // Millman: the load star settles at the admittance-weighted mean of the phase voltages,
        //     V_m = sum(V_k / R_k) / sum(1 / R_k)
        // which for 10, 100, 100 ohms on a balanced set E is 0.75 E, towards the heavy phase. That
        // leaves the heavy phase with a quarter of its voltage and the light ones with about 1.52
        // times theirs -- the classic way a lost neutral burns out whatever was on the lightly
        // loaded phases.
        var v = unbalancedPhaseVoltages(false);
        var e = ROOT3 * PHASE_PEAK;
        var light = Math.hypot(-0.5 - 0.75, Math.sqrt(3) / 2) * e;
        Assertions.assertEquals(0.25 * e, v[0], 0.25 * e * 0.03,
                "The heavily loaded phase should collapse to a quarter of its voltage, got " + v[0] / e + " E");
        Assertions.assertEquals(light, v[1], light * 0.03,
                "The lightly loaded phases should rise to about 1.52 E, got " + v[1] / e + " E");
        Assertions.assertEquals(light, v[2], light * 0.03,
                "The lightly loaded phases should rise to about 1.52 E, got " + v[2] / e + " E");
    }
}
