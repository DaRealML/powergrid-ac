package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour;
import org.patryk3211.powergrid.utility.DirtyMarkThrottle;

/**
 * The dirty-mark / entity-query throttles added by the per-tick cost audit (docs/perf/audit.md
 * items 1-4) had no test at all: CommutatorBlockEntity.tick(), ThermalBehaviour.tick(),
 * EnergyMeterBlockEntity.electricalTick() and RotorBehaviour's tick()/damageCalc() all need a
 * Level, which no headless test in this repo can supply.
 * <p>
 * A first attempt extracted a {@code shouldMarkDirty}/{@code isDirtyMarkDue} static method directly
 * onto CommutatorBlockEntity and EnergyMeterBlockEntity, the same way WireThermal.shouldPublish is
 * tested -- but unlike WireThermal, which holds no Minecraft types at all, those two classes are
 * themselves BlockEntity subclasses: merely loading the class to call a static method pulls in
 * NeoForge's event bus and fails with NoClassDefFoundError under plain JUnit (verified: both tests
 * failed that way before this file was rewritten to test DirtyMarkThrottle instead). The three
 * classes' interval-comparison logic is now DirtyMarkThrottle, a standalone utility with no
 * Minecraft dependency of its own; the three call sites just call it and are not, themselves,
 * covered by anything here. RotorBehaviour's two predicates below load fine because RotorBehaviour
 * is a lighter Behaviour class, not a BlockEntity, confirmed by these tests actually running.
 */
public class PerTickThrottleTest {
    // --- DirtyMarkThrottle.isDueOnTick: what CommutatorBlockEntity.tick() calls (CM-M1) ---

    @Test
    void dueOnTickFiresOnlyEveryIntervalTicks() {
        Assertions.assertTrue(DirtyMarkThrottle.isDueOnTick(0));
        Assertions.assertTrue(DirtyMarkThrottle.isDueOnTick(DirtyMarkThrottle.INTERVAL));
        Assertions.assertTrue(DirtyMarkThrottle.isDueOnTick(2L * DirtyMarkThrottle.INTERVAL));
        Assertions.assertFalse(DirtyMarkThrottle.isDueOnTick(1));
        Assertions.assertFalse(DirtyMarkThrottle.isDueOnTick(DirtyMarkThrottle.INTERVAL - 1));
        Assertions.assertFalse(DirtyMarkThrottle.isDueOnTick(DirtyMarkThrottle.INTERVAL + 1));
    }

    // --- DirtyMarkThrottle.isDueAfter: what ThermalBehaviour.tick() and
    //     EnergyMeterBlockEntity.electricalTick() call (TB-M1: interval x1000) ---

    @Test
    void dueAfterIsFalseBelowTheIntervalAndTrueAtOrAboveIt() {
        Assertions.assertFalse(DirtyMarkThrottle.isDueAfter(DirtyMarkThrottle.INTERVAL - 1));
        Assertions.assertTrue(DirtyMarkThrottle.isDueAfter(DirtyMarkThrottle.INTERVAL));
        Assertions.assertTrue(DirtyMarkThrottle.isDueAfter(DirtyMarkThrottle.INTERVAL + 1));
    }

    @Test
    void theIntervalIsTwentyTicksNotSomeOtherOrderOfMagnitude() {
        // Pins the actual constant, the same way isDueAfter's own boundary is pinned above: an
        // "interval x1000" mutation on the constant's declaration fails this even though a test that
        // only compared isDueAfter(x) against DirtyMarkThrottle.INTERVAL symbolically would not.
        Assertions.assertEquals(20, DirtyMarkThrottle.INTERVAL);
    }

    // --- RotorBehaviour.shouldMarkDirty: RB-M1 (|| mutated to &&) ---

    @Test
    void rotorDirtyMarkFiresWhenEitherVelocityOrAngleMoved() {
        // Neither moved: no mark.
        Assertions.assertFalse(RotorBehaviour.shouldMarkDirty(1f, 1f, 2.0, 2.0));
        // Velocity only.
        Assertions.assertTrue(RotorBehaviour.shouldMarkDirty(1f, 0f, 2.0, 2.0));
        // Angle only -- this is the case an `&&` mutation drops.
        Assertions.assertTrue(RotorBehaviour.shouldMarkDirty(1f, 1f, 3.0, 2.0));
        // Both.
        Assertions.assertTrue(RotorBehaviour.shouldMarkDirty(1f, 0f, 3.0, 2.0));
    }

    @Test
    void rotorDirtyMarkFiresOnANewNaNVelocity() {
        // NaN compares unequal to everything, including itself; a rotor that just went NaN must
        // still be marked so the bad value gets saved and the reset path can pick it up.
        Assertions.assertTrue(RotorBehaviour.shouldMarkDirty(Float.NaN, 1f, 2.0, 2.0));
    }

    // --- RotorBehaviour.shouldRunDamageQuery: RB-M2 (== 0 mutated to != 0) ---

    @Test
    void damageQueryRunsOnlyWhileTheRotorIsActuallyTurning() {
        Assertions.assertFalse(RotorBehaviour.shouldRunDamageQuery(0f));
        Assertions.assertTrue(RotorBehaviour.shouldRunDamageQuery(0.01f));
        Assertions.assertTrue(RotorBehaviour.shouldRunDamageQuery(-0.01f));
    }
}
