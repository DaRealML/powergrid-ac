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
package org.patryk3211.powergrid.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterWatchers;
import org.patryk3211.powergrid.network.C2SPacket;

/**
 * Tells the server whether this player has the multimeter graph open.
 * <p>
 * Sub-tick samples are only streamed while someone is actually looking at them, so the steady
 * cost of the whole feature is zero bytes.
 */
public class MultimeterWatchC2SPacket implements C2SPacket {
    private final boolean watching;

    public MultimeterWatchC2SPacket(boolean watching) {
        this.watching = watching;
    }

    public MultimeterWatchC2SPacket(FriendlyByteBuf buf) {
        this.watching = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(watching);
    }

    @Override
    public void handle(ServerPlayer player) {
        MultimeterWatchers.setWatching(player, watching);
    }
}
