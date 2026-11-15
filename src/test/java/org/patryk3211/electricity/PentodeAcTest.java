package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.ACVoltageSourceCoupling;
import org.patryk3211.powergrid.electricity.sim.special.ElectronTubeWire;
import org.patryk3211.powergrid.electricity.sim.special.PentodeWire;

import java.util.function.DoubleUnaryOperator;

/**
 * A tube amplifier under a 50 Hz grid drive, solved at several sub-tick rates and checked against
 * the Koren equations written out again here.
 *
 * <p>{@link PentodeTest} pins direct-current operating points, one solve per call. Under this fork's
 * solver an alternating island is stepped many times per world tick and the tube's
 * {@code startIteration()} limiter ({@code prevGrid} and friends) carries over from one sub-tick to
 * the next, so what these tests ask is whether the limiter, seeded at zero and rate-limited to a
 * fraction of a volt per call, keeps up with a moving grid and still lands on the real solution.
 *
 * <p>The circuit has no reactive element, so every sub-tick has an exact quasi-static answer. The
 * oracle solves it by bisection on the plate and screen node equations, with the tube's plate,
 * screen and grid currents written from the model's formulas rather than taken from the wire.
 *
 * <p>Tube limiter strength is set to what ships ({@code triodeLim* = 0.5} in {@code CSolver}); the
 * solver class defaults to 0.8, which is what every other test in the suite runs with and which
 * hides how slowly the limiter walks a node.
 */
public class PentodeAcTest extends TestHelper {
    private static final double FREQUENCY = 50;
    private static final double SHIPPED_LIMITER = 0.5;

    // Circuit: plate from B+ through the load, screen from its supply through a dropping resistor
    // (or held, for the stiff variant), cathode at ground.
    private static final double B_PLUS = 300, LOAD = 5000;
    private static final double SCREEN_SUPPLY = 250, SCREEN_RESISTOR = 10_000, STIFF_SCREEN = 150;

    // PentodeComponent defaults.
    private static final double MU = 8, KG1 = 6000, KG2 = 4500, KP = 48, KVB = 12, EX = 1.35, SATURATION = 0.1;
    // ElectronTubeComponent defaults.
    private static final double T_MU = 5, T_KG1 = 13_600, T_KP = 600, T_KVB = 300, T_EX = 1.5, T_SATURATION = 0.1;
    private static final double G_MIN = 1e-8;

    /**
     * Largest plate-current error allowed against the oracle, in amps.
     * <p>
     * The worst error measured over every case below was 7.6e-6 A (a held screen at 12 V of drive,
     * 32 sub-ticks); a dropping-resistor screen and the triode were at 1e-7 or better. This is 2.6
     * times the worst and 0.06 % of the smallest plate-current swing, 12.9 mA.
     */
    private static final double TOLERANCE = 2e-5;

    private static double softplus(double x) {
        return x > 20 ? x : x < -20 ? 0 : Math.log1p(Math.exp(x));
    }

    private static double pentodePlate(double va, double vg, double vs) {
        if(va <= 0 || vs <= 0)
            return va * G_MIN;
        var screen = Math.max(vs, 1e-3);
        var e1 = screen / KP * softplus(KP * (1 / MU + vg / screen));
        var raw = 2 * Math.pow(e1, EX) / KG1 * Math.atan(va / KVB);
        var ratio = raw / SATURATION;
        return raw / Math.sqrt(Math.sqrt(1 + ratio * ratio));
    }

    private static double pentodeScreen(double vg, double vs) {
        return Math.pow(softplus(vg + vs / MU), 1.5) / KG2;
    }

    private static double triodePlate(double va, double vg) {
        if(va <= 0)
            return va * G_MIN;
        var knee = Math.sqrt(T_KVB + va * va);
        var e1 = va / T_KP * softplus(T_KP * (1 / T_MU + vg / knee));
        var raw = 2 * Math.pow(e1, T_EX) / T_KG1;
        var ratio = raw / T_SATURATION;
        return raw / Math.sqrt(Math.sqrt(1 + ratio * ratio));
    }

    /** Root of a function that falls across the bracket. */
    private static double bisect(DoubleUnaryOperator f, double low, double high) {
        for(int i = 0; i < 200; ++i) {
            var mid = 0.5 * (low + high);
            if(f.applyAsDouble(mid) > 0)
                low = mid;
            else
                high = mid;
        }
        return 0.5 * (low + high);
    }

    private enum Kind { PENTODE_DROPPED_SCREEN, PENTODE_HELD_SCREEN, TRIODE }

    private static final class Rig {
        final Network net = new Network();
        final Kind kind;
        final double bias;
        final FloatingNode plate = net.N();
        final FloatingNode grid = net.N();
        FloatingNode screen;
        AbstractElectricWire tube;

        Rig(Kind kind, double amplitude, double bias) {
            this.kind = kind;
            this.bias = bias;
            net.network.triodeLimAnode = SHIPPED_LIMITER;
            net.network.triodeLimCathode = SHIPPED_LIMITER;
            net.network.triodeLimGrid = SHIPPED_LIMITER;

            var cathode = net.V(0);
            var supply = net.V((float) B_PLUS);
            var drive = new ACVoltageSourceCoupling(grid, null, 0.001f, amplitude, FREQUENCY);
            drive.setDcOffset(bias);
            net.network.addNode(drive);
            net.W((float) LOAD, supply, plate);

            if(kind == Kind.TRIODE) {
                tube = new ElectronTubeWire((float) T_MU, (float) T_KG1, (float) T_KP, (float) T_KVB, (float) T_EX,
                        (float) T_SATURATION, cathode, plate, grid);
            } else {
                screen = net.N();
                if(kind == Kind.PENTODE_HELD_SCREEN) {
                    net.W(0.001f, net.V((float) STIFF_SCREEN), screen);
                } else {
                    net.W((float) SCREEN_RESISTOR, net.V((float) SCREEN_SUPPLY), screen);
                }
                tube = new PentodeWire((float) MU, (float) KG1, (float) KG2, (float) KP, (float) KVB, (float) EX,
                        (float) SATURATION, cathode, plate, grid, screen);
            }
            net.network.addWire(tube);
        }

        /** Plate voltage the model says the circuit settles to for this grid voltage. */
        double oraclePlateVoltage() {
            var vg = grid.getVoltage();
            if(kind == Kind.TRIODE)
                return bisect(va -> (B_PLUS - va) / LOAD - triodePlate(va, vg), 0, B_PLUS);
            var vs = kind == Kind.PENTODE_HELD_SCREEN ? STIFF_SCREEN
                    : bisect(x -> (SCREEN_SUPPLY - x) / SCREEN_RESISTOR - pentodeScreen(vg, x), -3000, SCREEN_SUPPLY);
            return bisect(va -> (B_PLUS - va) / LOAD - pentodePlate(va, vg, vs), 0, B_PLUS);
        }

        /**
         * Run the measured window and fail on the first sub-tick that is not converged, not finite,
         * or further from the oracle than {@link #TOLERANCE}.
         *
         * @return the worst plate-current error seen
         */
        double check(int subTicks, int settleTicks, int measureTicks, String label) {
            var worst = 0.0;
            for(int t = 0; t < settleTicks + measureTicks; ++t) {
                net.network.prepare(subTicks);
                for(int s = 0; s < subTicks; ++s) {
                    net.network.singleTick();
                    if(t < settleTicks)
                        continue;
                    var where = String.format("%s, %d sub-ticks, world tick %d sub-tick %d, grid %.2f V",
                            label, subTicks, t, s, grid.getVoltage());
                    Assertions.assertTrue(Double.isFinite(plate.getVoltage()) && Double.isFinite(tube.current()),
                            "Not finite: " + where);
                    Assertions.assertTrue(net.network.isConverged(), "Solver did not converge: " + where);
                    var error = Math.abs((B_PLUS - plate.getVoltage()) / LOAD - (B_PLUS - oraclePlateVoltage()) / LOAD);
                    Assertions.assertTrue(error <= TOLERANCE,
                            String.format("Plate current is %.2e A from the Koren oracle (allowed %.0e A): %s",
                                    error, TOLERANCE, where));
                    worst = Math.max(worst, error);
                }
            }
            return worst;
        }
    }

    @Test
    void aPentodeWithADroppedScreenTracksTheOracleAtEverySubTickRate() {
        // Small drive at every rate, larger drive where the rate is fine enough for it. How far a
        // node moves in one sub-tick is what the limiter has to cover: it walks a node 0.25 V per
        // call and the solver gives up after 200 iterations, so a swing of more than about 50 V
        // between two sub-ticks does not converge. The triode shows the same limit.
        for(var subTicks : new int[]{8, 16, 32, 64, 128})
            new Rig(Kind.PENTODE_DROPPED_SCREEN, 4, -5).check(subTicks, 40, 20, "dropped screen, 4 V drive");
        for(var subTicks : new int[]{32, 64, 128})
            new Rig(Kind.PENTODE_DROPPED_SCREEN, 12, -5).check(subTicks, 40, 20, "dropped screen, 12 V drive");
    }

    @Test
    void aPentodeWithAHeldScreenTracksTheOracleAtEverySubTickRate() {
        for(var subTicks : new int[]{8, 16, 32, 64, 128})
            new Rig(Kind.PENTODE_HELD_SCREEN, 4, -5).check(subTicks, 40, 20, "held screen, 4 V drive");
        for(var subTicks : new int[]{64, 128})
            new Rig(Kind.PENTODE_HELD_SCREEN, 12, -5).check(subTicks, 40, 20, "held screen, 12 V drive");
    }

    @Test
    void theTriodeControlPassesTheSameHarness() {
        // If the harness itself were wrong, the triode that has shipped since before the AC work
        // would fail it too.
        for(var subTicks : new int[]{8, 16, 32, 64, 128})
            new Rig(Kind.TRIODE, 5, -2).check(subTicks, 40, 20, "triode, 5 V drive");
    }

    @Test
    void aPentodeStartedFromColdSettlesWithinOneWorldTick() {
        // The limiter starts every node at zero and moves it a fraction of a volt per call, so the
        // first solve after a circuit is built has a long way to walk: 215 V to the plate. With a
        // sub-stepped island there are enough iterations in the first world tick to do it.
        for(var subTicks : new int[]{8, 64}) {
            var rig = new Rig(Kind.PENTODE_DROPPED_SCREEN, 0, -5);
            rig.net.network.prepare(subTicks);
            for(int s = 0; s < subTicks; ++s)
                rig.net.network.singleTick();
            var expected = rig.oraclePlateVoltage();
            Assertions.assertEquals(expected, rig.plate.getVoltage(), 0.01 * expected,
                    "Plate voltage after one world tick at " + subTicks + " sub-ticks");
            Assertions.assertTrue(rig.net.network.isConverged(),
                    "The last sub-tick of the first world tick should have converged at " + subTicks + " sub-ticks");
        }
    }
}
