package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ITimeAwareWire;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.AcSampling;
import org.patryk3211.powergrid.electricity.sim.special.LRSeriesWire;

/**
 * Candidate 1 of docs/perf/scheduling.md: a non-power-of-two rate must be the same physics, only a
 * finer or coarser sample of it, or the rounding it replaces would have been hiding a real accuracy
 * requirement rather than wasting solves.
 * <p>
 * At mains frequency and the shipped 32 samples/cycle, {@code AcSampling.subTicksFor} rounds 80 up
 * to 128 (docs/AC.md 3.6). This drives the same L-R branch {@link IntegrationSchemeTest} uses -
 * separately, since that file's {@code fit} and {@code measure} are private and the least-squares
 * fit does not need a whole number of samples per cycle the way {@link PhasorFit} does - at both
 * rates and checks that 80 is not a different answer, only a slightly coarser one, by the same
 * theta-method leading-order bound {@link IntegrationSchemeTest#thePhaseErrorScalesWithTheSampleInterval()}
 * already established for the power-of-two ceiling.
 */
public class NonPowerOfTwoEquivalenceTest extends TestHelper {
    private static final double R = 25.6;
    private static final double TAU = 0.01;
    private static final double L = R * TAU;

    private static final double AMPLITUDE = 240;
    private static final float SOURCE_RESISTANCE = 0.001f;

    /** Mains frequency: the case the rounding actually costs, per docs/perf/scheduling.md. */
    private static final double FREQUENCY = 50;

    private record Response(double magnitude, double phase) { }

    /** Same measurement {@link IntegrationSchemeTest#measure} makes, duplicated rather than shared
     * because that method is private and this file must not touch another stream's test. */
    private static Response measure(int subTicks) {
        var net = new Network();
        var terminal = new FloatingNode();
        net.network.addNode(terminal);
        net.network.addNode(new ACVoltageSourceCoupling(terminal, null, SOURCE_RESISTANCE,
                (float) AMPLITUDE, (float) FREQUENCY));
        var ground = net.N();
        net.network.addNode(new VoltageSourceCoupling(ground, null, 0f, 0f));
        var coil = new LRSeriesWire(L, R, terminal, ground);
        net.network.addWire(coil);

        var dt = AcSampling.TICK_SECONDS / subTicks;
        var settle = (int) Math.max(10, Math.ceil(20 / (FREQUENCY * AcSampling.TICK_SECONDS)));
        for(int t = 0; t < settle; ++t)
            net.network.calculate(subTicks);

        var n = (int) Math.ceil(40 / (FREQUENCY * dt));
        var v = new double[n];
        var i = new double[n];
        var theta = new double[n];
        var k = 0;
        while(k < n) {
            net.network.prepare(subTicks);
            for(int s = 0; s < subTicks && k < n; ++s, ++k) {
                net.network.singleTick();
                v[k] = coil.potentialDifference();
                i[k] = coil.current();
                theta[k] = AcSampling.TWO_PI * FREQUENCY * (k * dt);
            }
        }

        var V = fit(v, theta);
        var I = fit(i, theta);
        var d = I[0] * I[0] + I[1] * I[1];
        var re = (V[0] * I[0] + V[1] * I[1]) / d;
        var im = (V[1] * I[0] - V[0] * I[1]) / d;
        return new Response(Math.hypot(re, im), Math.atan2(im, re));
    }

    /** Least squares fit to {@code a*sin(t) + b*cos(t) + c}, returning {@code {a, b}}. */
    private static double[] fit(double[] signal, double[] theta) {
        double ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0, ys = 0, yc = 0, y1 = 0;
        for(int k = 0; k < signal.length; ++k) {
            var s = Math.sin(theta[k]);
            var c = Math.cos(theta[k]);
            ss += s * s; sc += s * c; s1 += s;
            cc += c * c; c1 += c;
            ys += signal[k] * s; yc += signal[k] * c; y1 += signal[k];
        }
        var A = new double[][] { { ss, sc, s1 }, { sc, cc, c1 }, { s1, c1, signal.length } };
        var b = new double[] { ys, yc, y1 };
        for(int col = 0; col < 3; ++col) {
            var pivot = col;
            for(int r = col + 1; r < 3; ++r)
                if(Math.abs(A[r][col]) > Math.abs(A[pivot][col]))
                    pivot = r;
            var swapRow = A[col]; A[col] = A[pivot]; A[pivot] = swapRow;
            var swapB = b[col]; b[col] = b[pivot]; b[pivot] = swapB;
            for(int r = col + 1; r < 3; ++r) {
                var f = A[r][col] / A[col][col];
                for(int c = col; c < 3; ++c)
                    A[r][c] -= f * A[col][c];
                b[r] -= f * b[col];
            }
        }
        var x = new double[3];
        for(int r = 2; r >= 0; --r) {
            var sum = b[r];
            for(int c = r + 1; c < 3; ++c)
                sum -= A[r][c] * x[c];
            x[r] = sum / A[r][r];
        }
        return new double[] { x[0], x[1] };
    }

    private static double exactPhase() {
        return Math.atan2(AcSampling.TWO_PI * FREQUENCY * L, R);
    }

    private static double exactMagnitude() {
        return Math.hypot(R, AcSampling.TWO_PI * FREQUENCY * L);
    }

    @Test
    void eightyGivesTheSameFundamentalAsOneTwentyEightAtFiftyHertz() {
        // 80 is what fine rates give a 50Hz source at 32 samples/cycle; 128 is what the power-of-two
        // rule rounds it up to (docs/AC.md 3.6, RateLadder's class comment).
        var coarse = measure(80);
        var fine = measure(128);

        Assertions.assertEquals(exactMagnitude(), coarse.magnitude(), exactMagnitude() * 0.01,
                "80 sub-ticks should already read the true impedance to within 1%");
        Assertions.assertEquals(coarse.magnitude(), fine.magnitude(), coarse.magnitude() * 0.005,
                "80 and 128 sub-ticks must agree on magnitude to within 0.5%, got "
                        + coarse.magnitude() + " against " + fine.magnitude());

        var exact = exactPhase();
        var coarseError = Math.abs(exact - coarse.phase());
        var fineError = Math.abs(exact - fine.phase());

        // Leading-order theta-method lag (IntegrationSchemeTest.thePhaseErrorScalesWithTheSampleInterval):
        // (theta - 1/2) * omega * dt. An upper bound, not a prediction - see that test for why.
        var omega = AcSampling.TWO_PI * FREQUENCY;
        var leadingOrderAt = (java.util.function.IntFunction<Double>) subTicks ->
                (ITimeAwareWire.DEFAULT_THETA - 0.5) * omega * (AcSampling.TICK_SECONDS / subTicks);
        Assertions.assertTrue(coarseError > 0 && coarseError < leadingOrderAt.apply(80) * 1.1,
                "80 sub-ticks' lag should sit near its first-order bound of "
                        + String.format("%.3f deg, got %.3f deg", Math.toDegrees(leadingOrderAt.apply(80)),
                                Math.toDegrees(coarseError)));
        Assertions.assertTrue(fineError > 0 && fineError < leadingOrderAt.apply(128) * 1.1,
                "128 sub-ticks' lag should sit near its first-order bound of "
                        + String.format("%.3f deg, got %.3f deg", Math.toDegrees(leadingOrderAt.apply(128)),
                                Math.toDegrees(fineError)));

        // The two rates differ by 1.6x in dt (80 vs 128), so to first order their errors should
        // differ by about the same factor, not by an order of magnitude or a sign flip - that is
        // what "the same physics, a finer sample" means here. Generous bounds because the fit and
        // the higher-order terms both add noise at this step size.
        var ratio = fineError / coarseError;
        Assertions.assertTrue(ratio > 0.3 && ratio < 0.85,
                "128's lag should be a fraction of 80's set by dt128/dt80 = 0.625, got ratio "
                        + String.format("%.3f (%.4f deg vs %.4f deg)", ratio, Math.toDegrees(fineError),
                                Math.toDegrees(coarseError)));
    }
}
