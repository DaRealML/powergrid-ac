package org.patryk3211.powergrid.utility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies an action to every element of a collection once per interval, a share of them each tick,
 * instead of all of them on one tick.
 * <p>
 * A periodic "resend everything" pass costs the same however it is scheduled, but done at once it is
 * a single tick that is as long as the whole world's worth of work, which is what a player sees as a
 * lag spike every few seconds. Here the elements are snapshotted at the start of each round and the
 * round's remaining elements are divided over its remaining ticks, so no tick handles more than
 * {@code ceil(size / interval)} of them and the last one is handled on the last tick of the round.
 * <p>
 * Elements that appear after the snapshot wait for the next round; elements that vanish stay in the
 * snapshot, so an action has to tolerate being handed one that is gone. Holds no Minecraft types.
 *
 * @param <T> the element type
 */
public final class SpreadOverTicks<T> {
    private final List<T> round = new ArrayList<>();
    private int next;
    private int ticksLeft;

    /**
     * Runs one tick of the schedule.
     *
     * @param interval ticks per round; zero or less disables the schedule and drops the round in progress
     * @param snapshot lists the elements at the start of a round
     * @param action   applied to this tick's share
     */
    public void tick(int interval, Supplier<? extends Collection<? extends T>> snapshot, Consumer<? super T> action) {
        if(interval <= 0) {
            round.clear();
            next = 0;
            ticksLeft = 0;
            return;
        }
        if(ticksLeft <= 0) {
            round.clear();
            round.addAll(snapshot.get());
            next = 0;
            ticksLeft = interval;
        } else if(ticksLeft > interval) {
            // The interval was shortened while a round was running: finish within the new one.
            ticksLeft = interval;
        }
        // What is left, divided over the ticks left, rounded up so that none is stranded.
        var share = (round.size() - next + ticksLeft - 1) / ticksLeft;
        for(int i = 0; i < share; ++i)
            action.accept(round.get(next++));
        if(--ticksLeft <= 0) {
            // Release the snapshot with the round rather than holding it until the next one.
            round.clear();
            next = 0;
        }
    }
}
