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

import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.gui.ScreenOpener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlock;
import org.patryk3211.powergrid.circuits.schematic.CircuitSchematic;
import org.patryk3211.powergrid.collections.ModdedBlockEntities;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.collections.ModdedPackets;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.info.Current;
import org.patryk3211.powergrid.electricity.info.IHaveElectricProperties;
import org.patryk3211.powergrid.electricity.info.Voltage;
import org.patryk3211.powergrid.electricity.wire.*;
import org.patryk3211.powergrid.network.packets.MultimeterDataC2SPacket;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

import java.util.ArrayList;
import java.util.List;

public class MultimeterItem extends Item implements IHaveElectricProperties {
    public MultimeterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if(context.getPlayer() != null && context.getPlayer().isShiftKeyDown())
            return super.useOn(context);
        if(context.getHand() != InteractionHand.MAIN_HAND)
            return super.useOn(context);

        var electric = IElectric.getAt(context.getLevel(), context.getClickedPos());
        var blockState = context.getLevel().getBlockState(context.getClickedPos());
        if(electric != null) {
            var pos = context.getClickedPos();
            var terminal = electric.terminalIndexAt(blockState, context.getClickLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
            if(terminal >= 0) {
                var endpoint = new BlockWireEndpoint(pos, terminal);
                GlobalElectricNetworks.getWorldNetworks(context.getLevel())
                        .putInNetwork(endpoint);
                return onTerminal(context.getLevel(), endpoint, context.getItemInHand());
            }
        }
        return context.getLevel().getBlockEntity(context.getClickedPos(), ModdedBlockEntities.CIRCUIT_BOARD.get())
                .map(be -> {
                    var pos = context.getClickedPos();
                    var state = context.getLevel().getBlockState(context.getClickedPos());
                    var hitLocalPos = context.getClickLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
                    hitLocalPos = VecHelper.rotateCentered(hitLocalPos, -CircuitBoardBlock.getAngleY(state), Direction.Axis.Y);
                    hitLocalPos = VecHelper.rotateCentered(hitLocalPos, -CircuitBoardBlock.getAngleX(state), Direction.Axis.X);
                    if(hitLocalPos.y >= 2 / 16f && hitLocalPos.y <= 3 / 16f) {
                        int x = (int) (hitLocalPos.x * 16);
                        int y = (int) (hitLocalPos.z * 16);
                        if(!be.getSchematic().hasTrace(CircuitSchematic.Layer.FRONT, x, y))
                            return InteractionResult.FAIL;
                        return onTerminal(context.getLevel(), new CircuitBoardEndpoint(pos, x, y), context.getItemInHand());
                    }
                    return InteractionResult.PASS;
                }).orElse(InteractionResult.PASS);
    }

    @Environment(EnvType.CLIENT)
    private static Vec3 getAttachmentPoint() {
        var hit = Minecraft.getInstance().hitResult;
        if(hit == null || hit.getType() != HitResult.Type.ENTITY)
            return null;
        var entityHit = (EntityHitResult) hit;
        return entityHit.getLocation();
    }

    public InteractionResult useOnWire(Player player, ItemStack stack, InteractionHand hand, BaseWireEntity wireEntity) {
        if(hand != InteractionHand.MAIN_HAND)
            return InteractionResult.PASS;
        if(getMode(stack) != 1)
            setModeKeepingData(stack, 1);
        if(player.level().isClientSide) {
            // The attachment point comes from the client's hit result, so the client adds the
            // channel for immediate feedback and the packet makes the server authoritative.
            var point = getAttachmentPoint();
            if(point == null)
                return InteractionResult.PASS;
            addChannel(stack, MultimeterChannel.current(wireEntity, point));
            ModdedPackets.sendToServer(new MultimeterDataC2SPacket(point, wireEntity));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        var data = getModeData(stack);
        float maxDistance = ModdedConfigs.server().equipment.multimeterDistance.getF();

        // Every channel is checked, not just the most recent one: walking away from one probe
        // should drop that probe and leave the others measuring.
        var channels = getChannels(stack);
        if(!channels.isEmpty()) {
            var kept = new ArrayList<MultimeterChannel>(channels.size());
            for(var channel : channels) {
                if(level instanceof ServerLevel serverLevel && !channel.refresh(serverLevel))
                    continue;
                if(channel.isValid(level, entity, maxDistance))
                    kept.add(channel);
            }
            if(kept.size() != channels.size()) {
                if(entity instanceof Player player)
                    player.displayClientMessage(Lang.translate("message.multimeter_disconnected")
                            .style(ChatFormatting.GRAY)
                            .component(), true);
                saveChannels(stack, kept);
            }
        }

        // Only a half-assembled voltage pair lives in the loose keys now; a completed probe is
        // a channel and was validated above.
        var pending = data.contains("Pos") ? WireEndpointType.deserialize(data.getCompound("Pos")) : null;
        if(pending != null) {
            var at = pending.getExactPosition(level);
            if(entity.distanceToSqr(at) > maxDistance * maxDistance || !pending.isValid(level)) {
                data.remove("Pos");
                saveModeData(stack, data);
            }
        }
    }

    // 0 = Voltage, 1 = Current
    public int getMode(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null)
            return -1;

        CompoundTag tag = customData.copyTag();
        return tag.contains("Mode") ? tag.getInt("Mode") : -1;
    }


    public void setMode(ItemStack stack, int mode) {
        CompoundTag tag = stack
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag();

        tag.putInt("Mode", mode);
        tag.remove("ModeData");

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }


    public static CompoundTag getModeData(ItemStack stack) {
        // Get existing custom data or empty
        CompoundTag root = stack
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag();

        CompoundTag modeData;
        if (root.contains("ModeData", Tag.TAG_COMPOUND)) {
            modeData = root.getCompound("ModeData");
        } else {
            modeData = new CompoundTag();
            root.put("ModeData", modeData);

            // IMPORTANT: write back because we created data
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }

        return modeData;
    }

    public static void deleteModeData(ItemStack stack) {
        CompoundTag root = stack
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag();
        root.remove("ModeData");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /**
     * The channels this meter is watching, oldest first.
     * <p>
     * Stored as a list inside the existing mode data rather than as loose keys, so several
     * probes can be live at once. The loose {@code Pos}/{@code Neg} keys are still used, but
     * only to hold a voltage pair while it is being assembled — a channel is created once both
     * ends have been clicked.
     */
    public static List<MultimeterChannel> getChannels(ItemStack stack) {
        var data = getModeData(stack);
        var channels = new ArrayList<MultimeterChannel>();
        if(!data.contains("Channels"))
            return channels;
        var list = data.getList("Channels", Tag.TAG_COMPOUND);
        for(int i = 0; i < list.size(); ++i) {
            var channel = MultimeterChannel.deserialize(list.getCompound(i));
            if(channel != null)
                channels.add(channel);
        }
        return channels;
    }

    public static void saveChannels(ItemStack stack, List<MultimeterChannel> channels) {
        var data = getModeData(stack);
        var list = new ListTag();
        for(var channel : channels)
            list.add(channel.serialize());
        data.put("Channels", list);
        saveModeData(stack, data);
    }

    /**
     * Append a channel, dropping the oldest once the meter is full.
     * <p>
     * Dropping the oldest rather than refusing means probing a fifth point does something
     * sensible instead of silently nothing, which is hard to distinguish from a missed click.
     */
    public static void addChannel(ItemStack stack, MultimeterChannel channel) {
        var channels = getChannels(stack);
        channels.add(channel);
        while(channels.size() > MultimeterChannel.MAX_CHANNELS)
            channels.remove(0);
        saveChannels(stack, channels);
    }

    /** Sets the mode without wiping the mode data, which would take the channel list with it. */
    public static void setModeKeepingData(ItemStack stack, int mode) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt("Mode", mode);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void saveModeData(ItemStack stack, CompoundTag modeData) {
        CompoundTag root = stack
                .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag();
        root.put("ModeData", modeData);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        if(player.isShiftKeyDown() && usedHand == InteractionHand.MAIN_HAND) {
            player.getItemInHand(usedHand).remove(DataComponents.CUSTOM_DATA);
            player.displayClientMessage(Lang.translate("message.multimeter_disconnected")
                    .style(ChatFormatting.GRAY)
                    .component(), true);
            return InteractionResultHolder.success(player.getItemInHand(usedHand));
        }
        // Right-click in the air with a connected meter opens the trace graph. Client-only: the
        // history lives on the client and there is nothing for the server to arbitrate.
        //
        // The "in the air" test is load-bearing. useOn returns PASS for every block that is not
        // a circuit board, and for a terminal missed by a pixel, and vanilla falls through from
        // PASS to use() — so without it, right-clicking plain stone would open the screen.
        if(level.isClientSide && usedHand == InteractionHand.MAIN_HAND
                && getMode(player.getItemInHand(usedHand)) >= 0 && isLookingAtAir()) {
            openTraceScreen();
            return InteractionResultHolder.success(player.getItemInHand(usedHand));
        }
        return super.use(level, player, usedHand);
    }

    @Environment(EnvType.CLIENT)
    private static boolean isLookingAtAir() {
        var hit = Minecraft.getInstance().hitResult;
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    @Environment(EnvType.CLIENT)
    private static void openTraceScreen() {
        ScreenOpener.open(new MultimeterScreen());
    }

    private InteractionResult onTerminal(Level level, IWireEndpoint endpoint, ItemStack stack) {
        // Keep the existing channels: setMode() clears the whole mode data, which would discard
        // every probe just because the previous one happened to be a current measurement.
        if(getMode(stack) != 0)
            setModeKeepingData(stack, 0);
        var data = getModeData(stack);
        if(data.contains("Pos")) {
            var current = WireEndpointType.deserialize(data.getCompound("Pos"));
            if(endpoint.equals(current)) {
                // Clicking the pending endpoint again cancels it.
                data.remove("Pos");
                saveModeData(stack, data);
                return InteractionResult.SUCCESS;
            }
        }
        if(data.contains("Pos")) {
            // Second click completes the pair, which becomes a live channel. The loose keys are
            // only ever a half-assembled probe, never a measurement in their own right.
            var positive = WireEndpointType.deserialize(data.getCompound("Pos"));
            if(positive != null) {
                data.remove("Pos");
                data.remove("Neg");
                saveModeData(stack, data);
                addChannel(stack, MultimeterChannel.voltage(positive, endpoint));
                return InteractionResult.CONSUME;
            }
        }
        data.put("Pos", endpoint.serialize());
        saveModeData(stack, data);
        return InteractionResult.CONSUME;
    }

    /**
     * Present reading of the first channel, in volts or amperes depending on its type.
     * <p>
     * Kept as the single-value accessor the needle, the HUD line and the goggle text already
     * use; multi-channel consumers ask for a channel explicitly.
     */
    public float getMeasurement(Level level, ItemStack stack) {
        return getMeasurement(level, stack, 0);
    }

    /** Present reading of one channel, or zero if there is no such channel. */
    public float getMeasurement(Level level, ItemStack stack, int channel) {
        var channels = getChannels(stack);
        if(channel < 0 || channel >= channels.size())
            return 0;
        return channels.get(channel).measure(level);
    }

    public Component getText(Level level, Player user, ItemStack stack) {
        var measurement = getMeasurement(level, stack);
        return switch(getMode(stack)) {
            case 0 -> {
                var voltage = Unit.VOLTAGE.formatWithPrefixes(measurement);
                float maxVolt = ModdedConfigs.server().equipment.multimeterVoltage.getF();
                if(measurement > maxVolt) {
                    voltage = Lang.text(">" + maxVolt + " ").add(Unit.VOLTAGE.get());
                } else if(measurement < -maxVolt) {
                    voltage = Lang.text("<-" + maxVolt + " ").add(Unit.VOLTAGE.get());
                }
                yield Lang.translate("tooltip.multimeter.voltage")
                        .add(voltage.style(ChatFormatting.BLUE))
                        .style(ChatFormatting.GRAY)
                        .component();
            }
            case 1 -> {
                var current = Unit.CURRENT.formatWithPrefixes(measurement);
                float maxAmp = ModdedConfigs.server().equipment.multimeterCurrent.getF();
                if(measurement > maxAmp) {
                    current = Lang.text(">" + maxAmp + " ").add(Unit.CURRENT.get());
                } else if(measurement < -maxAmp) {
                    current = Lang.text("<-" + maxAmp + " ").add(Unit.CURRENT.get());
                }
                yield Lang.translate("tooltip.multimeter.current")
                        .add(current.style(ChatFormatting.YELLOW))
                        .style(ChatFormatting.GRAY)
                        .component();
            }
            default -> null;
        };
    }

    public float getDial(Level level, ItemStack stack) {
        float measurement = getMeasurement(level, stack);
        float value = Math.abs(switch(getMode(stack)) {
            case 0 -> measurement / ModdedConfigs.server().equipment.multimeterVoltage.getF();
            case 1 -> measurement / ModdedConfigs.server().equipment.multimeterCurrent.getF();
            default -> 0;
        });
        if(value > 1)
            return 1 + level.random.nextFloat() * 0.125f;
        return value;
    }

    @Override
    public void appendProperties(ItemStack stack, Player player, List<Component> tooltip) {
        Voltage.max(ModdedConfigs.server().equipment.multimeterVoltage.getF(), player, tooltip);
        Current.max(ModdedConfigs.server().equipment.multimeterCurrent.getF(), player, tooltip);
    }
}
