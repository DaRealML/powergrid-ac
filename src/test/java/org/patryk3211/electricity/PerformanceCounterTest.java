package org.patryk3211.electricity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;

import java.text.ParseException;

/**
 * PerformanceCounter.getTimestamp() had no test (commit ab10ead8, docs/perf/audit.md item 9). A
 * review pass's mutation PC-M1 dropped the {@code measured} fallback guard, so a counter that had
 * never been end()-ed computed its age from {@code lastMeasurementNanos == 0} against the current
 * System.nanoTime() -- i.e. the JVM's uptime in nanoseconds, not zero -- instead of reporting the
 * present. Reproduced below: mutating that line back makes the first test fail.
 */
public class PerformanceCounterTest {
    @Test
    void aFreshCountersTimestampIsNowNotABogusAgeFromNanoTimesOrigin() throws ParseException {
        var counter = new PerformanceCounter("test-fresh-" + System.nanoTime());
        var before = System.currentTimeMillis();
        var text = counter.getTimestamp();
        var after = System.currentTimeMillis();
        var parsed = PerformanceCounter.FORMAT.parse(text).getTime();
        // Generous window: PC-M1's bug reads lastMeasurementNanos (0, never set) as if it were a
        // nanoTime() sample, so the "age" it subtracts is actually the JVM's uptime in nanoseconds --
        // seconds to hours depending on how long the process has been running, never zero. Verified
        // (see docs/perf/audit.md's review-response section): under this test's own JVM it failed by
        // reporting a timestamp seconds in the past instead of the present, well outside this window.
        Assertions.assertTrue(parsed >= before - 5000 && parsed <= after + 5000,
                "fresh counter reported " + text + ", expected close to now");
    }

    @Test
    void afterEndTheTimestampReflectsTheLastMeasurement() {
        var counter = new PerformanceCounter("test-measured-" + System.nanoTime());
        var before = System.currentTimeMillis();
        counter.start();
        counter.end();
        var after = System.currentTimeMillis();
        var text = counter.getTimestamp();
        Assertions.assertDoesNotThrow(() -> PerformanceCounter.FORMAT.parse(text));
        // Coarse: FORMAT's own precision is whole seconds, so only bound it doesn't throw and lands
        // in a sane window around the measurement.
        long parsed;
        try {
            parsed = PerformanceCounter.FORMAT.parse(text).getTime();
        } catch(ParseException e) {
            throw new AssertionError(e);
        }
        Assertions.assertTrue(parsed >= before - 5000 && parsed <= after + 5000,
                "measured counter reported " + text);
    }
}
