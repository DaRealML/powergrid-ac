package org.patryk3211.powergrid.electricity.wire;

/**
 * When a wire's temperature is worth telling clients about.
 * <p>
 * A wire entity keeps its temperature in {@code SynchedEntityData}, and vanilla treats every change
 * of that float as a reason to send a {@code ClientboundSetEntityDataPacket} to every player
 * tracking the wire, once per tick, for as long as the value keeps moving. A wire that carries a
 * current is heated by {@code I^2 R} and cooled in proportion to how far it is above ambient, so
 * its temperature approaches equilibrium exponentially and changes in the low bits for minutes
 * after any load change. That is one packet per wire per tick per viewer for a difference nobody
 * can see: the client only compares the value with {@code overheatTemperature - 50} to decide
 * whether to spawn smoke.
 * <p>
 * The server keeps integrating the exact value; this class only decides when the exact value has
 * drifted far enough from the last published one to be worth a packet. It holds no Minecraft types
 * so a test can drive it directly.
 */
public final class WireThermal {
    /**
     * How far, in kelvin, the exact temperature may differ from the published one.
     * <p>
     * The smoke chance on the client is {@code (T - overheat + 100) / 100}, so a quarter of a kelvin
     * moves it by a quarter of a percent.
     */
    public static final float PUBLISH_DEAD_BAND = 0.25f;

    private WireThermal() { }

    /**
     * Whether {@code exact} should replace {@code published} in the synced data.
     * <p>
     * True when the two differ by at least the dead band, when they lie on opposite sides of either
     * temperature the client acts on, and whenever the difference is not a finite number below the
     * dead band: a NaN or infinite temperature was always published, and still is.
     *
     * @param exact               the temperature the server integrates
     * @param published           the value currently in the synced data
     * @param overheatTemperature the wire's burn-out temperature
     */
    public static boolean shouldPublish(float exact, float published, float overheatTemperature) {
        // Written as a negated less-than so that NaN falls through to "publish".
        if(!(Math.abs(exact - published) < PUBLISH_DEAD_BAND))
            return true;
        return sides(exact, published, overheatTemperature - 50f) || sides(exact, published, overheatTemperature);
    }

    private static boolean sides(float a, float b, float threshold) {
        return (a >= threshold) != (b >= threshold);
    }
}
