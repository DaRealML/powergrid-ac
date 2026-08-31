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
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterChannel;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;
import org.patryk3211.powergrid.network.C2SPacket;

public class MultimeterDataC2SPacket implements C2SPacket {
    private final Vector3f point;
    private final int wire;

    public MultimeterDataC2SPacket(Vec3 point, BaseWireEntity wire) {
        this.point = point.toVector3f();
        this.wire = wire.getId();
    }

    public MultimeterDataC2SPacket(FriendlyByteBuf buf) {
        point = buf.readVector3f();
        wire = buf.readInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVector3f(point);
        buf.writeInt(wire);
    }

    /**
     * Push the server's copy of the held stack back to the client.
     * <p>
     * Every path out of this method that does not add the channel has to do this. The client adds
     * the channel optimistically in {@code MultimeterItem.useOnWire} for immediate feedback and has
     * no way to learn the server refused: the server's stack is unchanged, so
     * {@code broadcastChanges} — which only sends a slot whose contents stop matching
     * {@code remoteSlots} — sends nothing, and the two copies stay one channel apart forever. That
     * is not cosmetic. {@code MultimeterTrace.acceptSubTickSamples} drops every payload whose
     * channel count disagrees with the client's, so from that moment the graph is stuck on the
     * 20 Hz fallback for as long as the meter holds those probes.
     */
    private static void resync(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
    }

    @Override
    public void handle(ServerPlayer player) {
        var stack = player.getMainHandItem();
        // No resync here: if the server's main hand is not a multimeter the two copies of the slot
        // already differ, and broadcastChanges corrects it on its own.
        if(!(stack.getItem() instanceof MultimeterItem multimeter))
            return;
        var level = player.serverLevel();
        if(!(level.getEntity(wire) instanceof BaseWireEntity wireEntity)) {
            resync(player);
            return;
        }

        // Nothing above this line used to be checked: an entity id and a point, both chosen by the
        // client, went straight into a channel. Level.getEntity(int) resolves ANY entity loaded in
        // the dimension, tracked or not, so enumerating ids gave a live current readout of every
        // wire on the server from any distance — and the range check meant to stop that was
        // measured against the very point the client had just supplied.
        var maxDistance = ModdedConfigs.server().equipment.multimeterDistance.getF();
        if(!MultimeterChannel.inReachOf(player, wireEntity, maxDistance)) {
            resync(player);
            return;
        }

        // Keeps the existing channels rather than clearing the mode data.
        if(multimeter.getMode(stack) != 1)
            MultimeterItem.setModeKeepingData(stack, 1);
        MultimeterItem.addChannel(stack, MultimeterChannel.current(wireEntity,
                MultimeterChannel.clampToWire(wireEntity, new Vec3(point.x, point.y, point.z))));
    }
}
