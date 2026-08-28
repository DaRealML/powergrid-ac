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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.CircuitBoardEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;

import java.util.UUID;

/**
 * One thing the multimeter is watching.
 * <p>
 * The meter used to hold a single probe, stored as loose keys in the item's mode data. Watching
 * several points at once needs each probe to be a value that can live in a list, which is what
 * this is — plus the measurement and validity logic that used to be spread across
 * {@code MultimeterItem}.
 *
 * <h2>Two kinds</h2>
 * A <b>voltage</b> channel spans two endpoints and reads the difference between their node
 * potentials. A <b>current</b> channel names a single wire entity and reads the current through
 * it. They are different enough that a common "probe" abstraction would be a fiction, so the
 * type is explicit and the unused fields are simply null.
 */
public class MultimeterChannel {
    public static final int MAX_CHANNELS = 4;

    public static final int TYPE_VOLTAGE = 0;
    public static final int TYPE_CURRENT = 1;

    private final int type;

    // Voltage channel.
    @Nullable
    private IWireEndpoint positive;
    @Nullable
    private IWireEndpoint negative;

    // Current channel. The UUID is the durable reference; the entity id is a per-session cache
    // refreshed server-side, because looking an entity up by UUID every tick is not free.
    @Nullable
    private UUID wireId;
    private int entityId = -1;
    private Vec3 attachment = Vec3.ZERO;

    private MultimeterChannel(int type) {
        this.type = type;
    }

    public static MultimeterChannel voltage(IWireEndpoint positive, IWireEndpoint negative) {
        var channel = new MultimeterChannel(TYPE_VOLTAGE);
        channel.positive = positive;
        channel.negative = negative;
        return channel;
    }

    public static MultimeterChannel current(BaseWireEntity wire, Vec3 attachment) {
        var channel = new MultimeterChannel(TYPE_CURRENT);
        channel.wireId = wire.getUUID();
        channel.entityId = wire.getId();
        channel.attachment = attachment;
        return channel;
    }

    public int getType() {
        return type;
    }

    public boolean isCurrent() {
        return type == TYPE_CURRENT;
    }

    @Nullable
    public IWireEndpoint getPositive() {
        return positive;
    }

    @Nullable
    public IWireEndpoint getNegative() {
        return negative;
    }

    public Vec3 getAttachment() {
        return attachment;
    }

    public int getEntityId() {
        return entityId;
    }

    public CompoundTag serialize() {
        var tag = new CompoundTag();
        tag.putInt("T", type);
        if(type == TYPE_VOLTAGE) {
            if(positive != null)
                tag.put("Pos", positive.serialize());
            if(negative != null)
                tag.put("Neg", negative.serialize());
        } else {
            if(wireId != null)
                tag.putUUID("UUID", wireId);
            tag.putInt("EID", entityId);
            tag.putDouble("X", attachment.x);
            tag.putDouble("Y", attachment.y);
            tag.putDouble("Z", attachment.z);
        }
        return tag;
    }

    @Nullable
    public static MultimeterChannel deserialize(CompoundTag tag) {
        var type = tag.getInt("T");
        var channel = new MultimeterChannel(type);
        if(type == TYPE_VOLTAGE) {
            channel.positive = tag.contains("Pos") ? WireEndpointType.deserialize(tag.getCompound("Pos")) : null;
            channel.negative = tag.contains("Neg") ? WireEndpointType.deserialize(tag.getCompound("Neg")) : null;
            if(channel.positive == null || channel.negative == null)
                return null;
        } else {
            if(tag.hasUUID("UUID"))
                channel.wireId = tag.getUUID("UUID");
            channel.entityId = tag.getInt("EID");
            channel.attachment = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        }
        return channel;
    }

    /** Where this channel physically sits, for the distance check and the probe wires. */
    public Vec3 anchor(Level level) {
        if(type == TYPE_CURRENT)
            return attachment;
        if(positive != null)
            return positive.getExactPosition(level);
        if(negative != null)
            return negative.getExactPosition(level);
        return Vec3.ZERO;
    }

    /**
     * Refresh the cached entity id from the durable UUID. Server side only, since only the
     * server can look an entity up that way.
     */
    public boolean refresh(ServerLevel level) {
        if(type != TYPE_CURRENT || wireId == null)
            return true;
        var entity = level.getEntity(wireId);
        if(entity == null)
            return false;
        entityId = entity.getId();
        return true;
    }

    /** Whether this channel still refers to something that exists and is close enough. */
    public boolean isValid(Level level, Entity holder, float maxDistance) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return false;
            if(!positive.isValid(level) || !negative.isValid(level))
                return false;
        } else if(level.getEntity(entityId) == null) {
            return false;
        }
        return holder.distanceToSqr(anchor(level)) <= maxDistance * maxDistance;
    }

    /** The present reading: volts for a voltage channel, amperes for a current one. */
    public float measure(Level level) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return 0;
            if(!positive.isValid(level) || !negative.isValid(level))
                return 0;
            var positiveNode = positive instanceof CircuitBoardEndpoint e
                    ? e.getGenericNode(level) : positive.getNode(level);
            var negativeNode = negative instanceof CircuitBoardEndpoint e
                    ? e.getGenericNode(level) : negative.getNode(level);
            if(positiveNode == null || negativeNode == null)
                return 0;
            return (float) (positiveNode.getVoltage() - negativeNode.getVoltage());
        }
        if(level.getEntity(entityId) instanceof BaseWireEntity wire)
            return wire.measuredCurrent();
        return 0;
    }
}
