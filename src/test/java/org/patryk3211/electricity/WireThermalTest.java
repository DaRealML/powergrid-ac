package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.wire.WireThermal;

import java.util.function.IntToDoubleFunction;

/**
 * {@link WireThermal} decides when a wire's temperature is worth a packet.
 * <p>
 * {@code BaseWireEntity} cannot be built without a Minecraft world, so the heat balance below is a
 * transcription of its {@code temperatureUpdate()} (same float operations in the same order) with
 * the constants of {@code wire_types/wire.json}. If that method changes, this transcription has to
 * change with it; what the tests pin is the publishing rule, and the size of the saving it makes
 * on trajectories that arithmetic really produces.
 */
public class WireThermalTest {
    private static final float RESISTANCE_PER_ITEM = 0.0015f;
    private static final float MAXIMUM_CURRENT = 80f;
    private static final float OVERHEAT = 175f;
    // 13.65 * 0.8 + 7.1: the ambient of a plains biome.
    private static final float AMBIENT = 13.65f * 0.8f + 7.1f;
    private static final int ITEMS = 12;

    /** Mean-square of a sine over one 50 ms tick, as the wire's per-tick RMS sees it. */
    private static double acTickRms(double peak, double hz, int tick) {
        var w = 2 * Math.PI * hz;
        var tick0 = tick * 0.05;
        var meanSquare = 0.5 - (Math.sin(2 * w * (tick0 + 0.05)) - Math.sin(2 * w * tick0)) / (4 * w * 0.05);
        return peak * Math.sqrt(meanSquare);
    }

    /** What one run did: how often the synced float would have changed, and how often it now does. */
    private record Outcome(int ticks, int unconditionalChanges, int publishes, double worstLag, boolean sidesAlwaysAgreed) { }

    private static Outcome simulate(int ticks, IntToDoubleFunction current) {
        var resistance = RESISTANCE_PER_ITEM * ITEMS;
        var dissipation = MAXIMUM_CURRENT * MAXIMUM_CURRENT * RESISTANCE_PER_ITEM / 150f * ITEMS;
        var mass = 1.0f * ITEMS;
        float temperature = 22f;
        float published = 22f;
        int changes = 0, publishes = 0;
        double worst = 0;
        boolean sides = true;
        for(int t = 0; t < ticks; ++t) {
            float I = (float) current.applyAsDouble(t);
            float energy = 0;
            energy += I * I * resistance / 20f;
            energy -= dissipation * (temperature - AMBIENT) / 20f;
            var next = temperature + energy / mass;
            // What the old unconditional entityData.set(...) treated as dirty.
            if(Float.floatToIntBits(next) != Float.floatToIntBits(temperature))
                ++changes;
            temperature = next;
            if(WireThermal.shouldPublish(temperature, published, OVERHEAT)) {
                published = temperature;
                ++publishes;
            }
            worst = Math.max(worst, Math.abs(temperature - published));
            sides &= (temperature >= OVERHEAT) == (published >= OVERHEAT)
                    && (temperature >= OVERHEAT - 50f) == (published >= OVERHEAT - 50f);
        }
        return new Outcome(ticks, changes, publishes, worst, sides);
    }

    @Test
    void aWireStaysWithinTheDeadBandOfTheExactTemperatureAtEveryTick() {
        var tenMinutes = 20 * 600;
        IntToDoubleFunction[] loads = {
                t -> 10,
                t -> 30,
                t -> t < 6000 ? 10 : 20,
                t -> (t / 600) % 2 == 0 ? 10 : 0,
                t -> acTickRms(14, 4.53, t),
                t -> acTickRms(14, 9.07, t),
                // Settles at 125.4 C, so it creeps across the temperature the client spawns smoke at
                // at about a millikelvin a tick, far below the dead band.
                t -> 67.7,
        };
        for(int k = 0; k < loads.length; ++k) {
            var outcome = simulate(tenMinutes, loads[k]);
            Assertions.assertTrue(outcome.worstLag() < WireThermal.PUBLISH_DEAD_BAND,
                    "load " + k + " lagged by " + outcome.worstLag() + " K");
            Assertions.assertTrue(outcome.sidesAlwaysAgreed(),
                    "load " + k + " showed a viewer the wrong side of a threshold");
        }
    }

    @Test
    void anAlternatingCurrentBelowTenHertzNoLongerPublishesEveryTick() {
        // At 4.53 Hz a 50 ms tick spans 0.23 of a cycle, so the tick RMS swings between 0.39 and 0.92
        // of the peak and the temperature never settles: the float changes on every tick for as long
        // as the wire carries current.
        var outcome = simulate(20 * 600, t -> acTickRms(14, 4.53, t));
        Assertions.assertTrue(outcome.unconditionalChanges() > 0.99 * outcome.ticks(),
                "premise: the synced float used to change on " + outcome.unconditionalChanges() + " of " + outcome.ticks() + " ticks");
        Assertions.assertTrue(outcome.publishes() <= outcome.ticks() / 100,
                "published " + outcome.publishes() + " times in " + outcome.ticks() + " ticks");
    }

    @Test
    void aSteadyDirectCurrentPublishesOnlyWhileItHeatsUpAndThenStops() {
        var outcome = simulate(20 * 600, t -> 30);
        // Approaching equilibrium with a time constant of about 312 ticks, the exact value changes for
        // some 2600 ticks. The published one moves at most once per quarter kelvin of the 17.2 K it
        // climbs (22 to 39.2), because each publication is a jump of at least the dead band. The
        // quarter is written out rather than read from the constant, so that a dead band of zero
        // (a bound of infinity) cannot satisfy it.
        Assertions.assertTrue(outcome.unconditionalChanges() > 2000, "premise: " + outcome.unconditionalChanges());
        Assertions.assertTrue(outcome.publishes() <= (39.2f - 22f) / 0.25f + 2,
                "published " + outcome.publishes() + " times");
    }

    @Test
    void aFastHeatingWireStillPublishesEveryTickItMovesAViewerCouldSee() {
        // 250 A through 12 items is 16x the rating: the wire heats by several kelvin a tick, so every
        // tick's change is above the dead band and none may be swallowed.
        var resistance = RESISTANCE_PER_ITEM * ITEMS;
        var perTick = 250f * 250f * resistance / 20f / (1.0f * ITEMS);
        Assertions.assertTrue(perTick > 4 * WireThermal.PUBLISH_DEAD_BAND, "premise: " + perTick + " K per tick");
        var published = 22f;
        var temperature = 22f;
        for(int t = 0; t < 10; ++t) {
            temperature += perTick;
            Assertions.assertTrue(WireThermal.shouldPublish(temperature, published, OVERHEAT), "tick " + t);
            published = temperature;
        }
    }

    @Test
    void theDeadBandIsStrictAndSymmetric() {
        Assertions.assertFalse(WireThermal.shouldPublish(40.0f, 40.2f, OVERHEAT));
        Assertions.assertFalse(WireThermal.shouldPublish(40.2f, 40.0f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(40.0f, 40.25f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(40.25f, 40.0f, OVERHEAT));
        Assertions.assertFalse(WireThermal.shouldPublish(40.0f, 40.0f, OVERHEAT));
    }

    @Test
    void crossingATemperatureTheClientActsOnPublishesEvenWithinTheDeadBand() {
        // The client spawns smoke from overheat - 50 = 125 and treats overheat itself as burn-out.
        Assertions.assertTrue(WireThermal.shouldPublish(125.05f, 124.95f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(124.95f, 125.05f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(175.05f, 174.95f, OVERHEAT));
        Assertions.assertFalse(WireThermal.shouldPublish(130.1f, 130.0f, OVERHEAT));
    }

    @Test
    void aTemperatureExactlyOnAThresholdCountsAsCrossed() {
        // sides() is written `(a >= threshold) != (b >= threshold)` specifically so landing exactly
        // ON a threshold counts as being past it; `>` instead of `>=` here would silently swallow
        // that crossing whenever the dead-band diff (0.1 below) hides it.
        Assertions.assertTrue(WireThermal.shouldPublish(125.0f, 124.9f, OVERHEAT), "at the smoke threshold");
        Assertions.assertTrue(WireThermal.shouldPublish(124.9f, 125.0f, OVERHEAT), "at the smoke threshold, reversed");
        Assertions.assertTrue(WireThermal.shouldPublish(175.0f, 174.9f, OVERHEAT), "at the overheat threshold");
        Assertions.assertTrue(WireThermal.shouldPublish(174.9f, 175.0f, OVERHEAT), "at the overheat threshold, reversed");
    }

    @Test
    void aNonFiniteTemperatureIsAlwaysPublished() {
        Assertions.assertTrue(WireThermal.shouldPublish(Float.NaN, 40f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(40f, Float.NaN, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(Float.POSITIVE_INFINITY, 40f, OVERHEAT));
        Assertions.assertTrue(WireThermal.shouldPublish(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, OVERHEAT));
    }
}
