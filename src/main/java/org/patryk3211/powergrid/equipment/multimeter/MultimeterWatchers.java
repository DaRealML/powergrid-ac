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
package org.patryk3211.powergrid.equipment.multimeter;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which players currently have the multimeter graph open.
 * <p>
 * Sub-tick samples are the only part of the meter that costs real bandwidth, and the only thing
 * that can display them is the graph screen — the needle and the HUD line physically cannot show
 * a waveform. Gating the stream on an open screen therefore makes the feature cost <em>nothing</em>
 * when nobody is looking, which is almost always.
 * <p>
 * A stale entry is harmless: the send loop iterates the players actually online and holding a
 * meter, so a player who logged out while watching is simply never matched.
 */
public class MultimeterWatchers {
    private static final Set<UUID> watching = new HashSet<>();

    public static void setWatching(ServerPlayer player, boolean watch) {
        if(watch)
            watching.add(player.getUUID());
        else
            watching.remove(player.getUUID());
    }

    public static boolean isWatching(ServerPlayer player) {
        return watching.contains(player.getUUID());
    }

    public static boolean isEmpty() {
        return watching.isEmpty();
    }
}
