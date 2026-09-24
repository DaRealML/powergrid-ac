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
package org.patryk3211.powergrid.electricity.sim;

import org.patryk3211.powergrid.PowerGrid;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class PerformanceCounter {
    public static final DateFormat FORMAT = DateFormat.getDateTimeInstance();
    public static final List<PerformanceCounter> COUNTERS = new ArrayList<>();

    private String name;

    private int measurementTime = 5000;
    private long microsTotal;
    private long minTime;
    private long maxTime;
    private long epochCount;

    // One island is solved by one thread at a time, but several islands can call start()/end()
    // on this SAME shared instance concurrently (JavaMNA and ElectricalNetwork each keep one
    // static PERF for every island of that class). A plain `start` field would let one island's
    // end() read another island's start timestamp; a ThreadLocal gives every thread its own.
    private final ThreadLocal<Long> start = new ThreadLocal<>();

    // Guards every read/write of the accumulators below, so a concurrent start()/end() from two
    // islands can never interleave a read-modify-write and lose an update. Contention is
    // negligible: this only wraps updating a handful of longs, not the solve itself.
    private final Object lock = new Object();

    private double prevAvg;

    private long stamp = new Date().getTime();
    private Date lastMeasurement;

    public PerformanceCounter(String name) {
        this.name = name;
        COUNTERS.add(this);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void setMeasurementTime(int measurementTime) {
        this.measurementTime = measurementTime;
    }

    public void start() {
        start.set(System.nanoTime());
    }

    public void end() {
        var startedAt = start.get();
        if(startedAt == null) {
            // end() without a matching start() on this thread: nothing to measure, and better to
            // skip the sample than to invent a bogus duration.
            return;
        }
        var duration = System.nanoTime() - startedAt;
        synchronized(lock) {
            if(minTime == 0) {
                minTime = duration;
            } else if(minTime > duration) {
                minTime = duration;
            }
            if(maxTime < duration) {
                maxTime = duration;
            }
            ++epochCount;
            microsTotal += duration / 1000;

            var currentTime = new Date();
            var stampDuration = currentTime.getTime() - stamp;
            if(stampDuration >= measurementTime) {
                prevAvg = (double) microsTotal / epochCount;
                stamp = currentTime.getTime();
                reset();
            }
            lastMeasurement = currentTime;
        }
    }

    public void reset() {
        synchronized(lock) {
            microsTotal = 0;
            epochCount = 0;
            maxTime = 0;
            minTime = 0;
        }
    }

    public void log() {
        // Snapshot every field under the lock so the three numbers logged are from one instant,
        // not min/max/avg each read at whatever point a concurrent end() happened to be at.
        long min, max, total, count;
        synchronized(lock) {
            min = minTime;
            max = maxTime;
            total = microsTotal;
            count = epochCount;
        }
        PowerGrid.LOGGER.info("Performance counter '{}':", name);
        PowerGrid.LOGGER.info("  Min / Max / Avg");
        PowerGrid.LOGGER.info("  {}µs / {}µs / {}µs", min / 1000f, max / 1000f, (float) total / count);
    }

    public String getName() {
        return name;
    }

    public double getMin() {
        synchronized(lock) {
            return minTime / 1000.0;
        }
    }

    public double getMax() {
        synchronized(lock) {
            return maxTime / 1000.0;
        }
    }

    /**
     * Epochs accumulated since the last {@link #reset()} (which {@link #end()} also triggers once
     * {@code measurementTime} has elapsed). Exposed so a concurrency test can prove no update was
     * lost to a race, not just that {@link #getMin()}/{@link #getMax()} stayed in bounds — a lost
     * update cannot push either of those out of range, but it does under-count this.
     */
    public long getEpochCount() {
        synchronized(lock) {
            return epochCount;
        }
    }

    public double getAvg() {
        synchronized(lock) {
            if(epochCount == 0)
                return prevAvg;
            var weight = epochCount / 1000.0;
            return prevAvg * 1 / (1 + weight) + ((double) microsTotal / epochCount) * weight / (1 + weight);
        }
    }

    public String getTimestamp() {
        return FORMAT.format(lastMeasurement);
    }
}
