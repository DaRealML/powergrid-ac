package org.patryk3211.powergrid.utility;

/**
 * How often a per-tick-changing value's chunk-dirty mark is allowed to fire.
 * <p>
 * A value that changes on (almost) every tick under load -- a generator winding's EmfState, a
 * cooling/warming temperature, accumulated energy -- would mark its chunk dirty every single tick
 * forever if the mark were gated on a value comparison (the way a rotor at rest can skip it: see
 * {@code RotorBehaviour.shouldMarkDirty}), so it is gated on a tick count instead. One second of
 * drift in a value that is recomputed from the live simulation every tick anyway is not a loss
 * anyone can observe, and it is well inside the game's own autosave interval.
 * <p>
 * Used by {@code CommutatorBlockEntity}, {@code ThermalBehaviour} and {@code EnergyMeterBlockEntity},
 * which used to each define their own copy of the same interval. Holds no Minecraft types, unlike
 * those three, so a plain JUnit test can drive it directly even though none of them can run headless.
 */
public final class DirtyMarkThrottle {
    public static final int INTERVAL = 20;

    private DirtyMarkThrottle() { }

    /** Whether a throttle keyed on an absolute tick count (e.g. a shaft's own tick) is due now. */
    public static boolean isDueOnTick(long tick) {
        return tick % INTERVAL == 0;
    }

    /** Whether a throttle counting ticks since it last fired is due now. */
    public static boolean isDueAfter(int ticksSinceLastMark) {
        return ticksSinceLastMark >= INTERVAL;
    }
}
