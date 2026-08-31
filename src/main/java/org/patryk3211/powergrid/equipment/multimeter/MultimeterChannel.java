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
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.patryk3211.powergrid.electricity.wire.CircuitBoardEndpoint;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEntity;
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

    /** Set when refresh() moved the cached entity id, so the owner knows to persist it. */
    private boolean idChanged;

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
        if(entity.getId() != entityId) {
            entityId = entity.getId();
            idChanged = true;
        }
        return true;
    }

    /**
     * Whether the cached entity id moved since this was last asked, clearing the flag.
     * <p>
     * Entity network ids change on chunk reload, restart and relog. The refreshed id has to be
     * written back to the stack or the channel keeps the id it was created with and silently
     * reads zero from then on, while still appearing connected.
     */
    public boolean consumeIdChanged() {
        var changed = idChanged;
        idChanged = false;
        return changed;
    }

    /**
     * The wire whose signed current this channel should read.
     * <p>
     * Not {@code BaseWireEntity.current()}: {@link org.patryk3211.powergrid.electricity.wire.powercord.CordEntity}
     * implements it as {@code |i1| + |i2|}, which is a magnitude and would draw a rectified trace
     * on an alternating supply exactly as {@code measuredCurrent()} did. A cord's two halves are
     * in series and carry the same current, so one of them gives the signed value.
     */
    @Nullable
    private static AbstractElectricWire signedWire(@Nullable Entity entity) {
        if(entity instanceof WireEntity wire)
            return wire.getWire();
        if(entity instanceof CordEntity cord)
            return cord.getWire1();
        return null;
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

    /**
     * Build a sub-tick sampler for this channel and attach it to the island it is watching, or
     * return null if the target cannot be resolved right now.
     * <p>
     * Resolved fresh every tick rather than cached: the electrical network a probe belongs to is
     * not stable, since islands merge and split whenever a player edits the grid.
     */
    @Nullable
    public ProbeSampler attachSampler(Level level) {
        if(type == TYPE_VOLTAGE) {
            if(positive == null || negative == null)
                return null;
            if(!positive.isValid(level) || !negative.isValid(level))
                return null;
            var positiveNode = resolveNode(level, positive);
            var negativeNode = resolveNode(level, negative);
            if(positiveNode == null || negativeNode == null)
                return null;
            // The two ends may sit in different islands; sample in the one holding the positive
            // lead, which is where the waveform of interest is.
            var network = positiveNode.getNetwork();
            if(network == null)
                return null;
            var sampler = ProbeSampler.voltage(positiveNode, negativeNode);
            network.addObserver(sampler);
            return sampler;
        }

        var wire = signedWire(level.getEntity(entityId));
        if(wire == null)
            return null;
        // residentNetwork(), not getNetwork(). Server-side this wire is a TransmissionLinePart —
        // WireEntity.makeWire takes it from GlobalElectricNetworks.makeConnection — and that class
        // refuses to hold a network at all, so getNetwork() on it is permanently null. Asking the
        // wrong question rejected every current probe in every configuration: the server wrote an
        // empty array, the client fell back to its own 20 Hz reading, and beside a voltage channel
        // sampled 128 times a tick that drew the current as a staircase.
        var network = wire.residentNetwork();
        if(network == null)
            return null;
        var sampler = ProbeSampler.current(wire);
        network.addObserver(sampler);
        return sampler;
    }

    @Nullable
    private static IElectricNode resolveNode(Level level, IWireEndpoint endpoint) {
        // A circuit-board endpoint does not implement getNode and would throw.
        return endpoint instanceof CircuitBoardEndpoint e ? e.getGenericNode(level) : endpoint.getNode(level);
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
        // Signed. Neither measuredCurrent() nor CordEntity.current() will do: the first is
        // Math.abs(current()) and the second is |i1| + |i2|, and either would fold the negative
        // half of an alternating cycle upwards into a rectified trace.
        var wire = signedWire(level.getEntity(entityId));
        return wire == null ? 0 : (float) wire.current();
    }
}
