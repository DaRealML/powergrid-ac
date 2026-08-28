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

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.collections.ModdedPartialModels;
import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;

@Environment(EnvType.CLIENT)
public class MultimeterItemRenderer extends CustomRenderedItemModelRenderer {
    private static final ResourceLocation TEXTURE = PowerGrid.texture("special/copper_wire");
    private static float mainPrevDial;
    private static float mainDial;
    private static float offPrevDial;
    private static float offDial;

    private static float getDialState(ItemStack stack) {
        var player = Minecraft.getInstance().player;
        var pt = AnimationTickHolder.getPartialTicks();
        if(player.getMainHandItem() == stack) {
            return Mth.lerp(pt, mainPrevDial, mainDial);
        } else if(player.getOffhandItem() == stack) {
            return Mth.lerp(pt, offPrevDial, offDial);
        } else {
            return 0;
        }
    }

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer, ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        ms.pushPose();
        renderer.render(model.getOriginalModel(), light);

        if(transformType.firstPerson()) {
            var angle = -Math.PI / 4 + Math.PI / 2 * getDialState(stack);
            ms.rotateAround(new Quaternionf().rotateZ((float) angle), 0, (5.75f - 8) / 16, 0);
        }
        renderer.render(ModdedPartialModels.MULTIMETER_NEEDLE.get(), light);
        ms.popPose();
    }

    public static void clientTick(Level level, Player player) {
        var stack1 = player.getMainHandItem();
        if(stack1.getItem() instanceof MultimeterItem multimeter) {
            mainPrevDial = mainDial;
            mainDial = multimeter.getDial(level, stack1);
            // Record the main-hand reading for the graph screen. One sample per client tick,
            // which is the rate the underlying value reaches the client at all.
            MultimeterTrace.sample(level, stack1, multimeter);
        } else {
            mainDial = 0;
            mainPrevDial = 0;
            // Putting the meter away ends the trace; a stale history would otherwise be
            // presented as if it belonged to whatever is probed next.
            MultimeterTrace.clear();
        }
        var stack2 = player.getOffhandItem();
        if(stack2.getItem() instanceof MultimeterItem multimeter) {
            offPrevDial = offDial;
            offDial = multimeter.getDial(level, stack2);
        } else {
            offDial = 0;
            offPrevDial = 0;
        }
    }

    /* -------=========   Probe Rendering   =========------- */
    /** Halves a colour's RGB, keeping alpha, to mark the negative lead of a pair. */
    private static int dim(int argb) {
        var alpha = argb & 0xFF000000;
        return alpha | ((argb >> 1) & 0x7F7F7F);
    }

    public static void renderProbe(Vec3 point, SuperRenderTypeBuffer buffer, PoseStack matrixStack, ClientLevel world, LocalPlayer player, int color) {
        HangingWireRenderer.renderFromPositions(matrixStack, buffer.getBuffer(RenderType.entitySolid(TEXTURE)),
                Vec3.ZERO,
                point, 1.01f, 1.01f, 1 / 16f, world, color);
    }

    public static void render(SuperRenderTypeBuffer buffer, PoseStack matrixStack, ClientLevel world, LocalPlayer player, ItemStack stack, Vec3 cameraPos) {
        if(!(stack.getItem() instanceof MultimeterItem multimeter))
            return;
        var origin = player.getRopeHoldPosition(AnimationTickHolder.getPartialTicks());
        matrixStack.pushPose();
        matrixStack.translate(origin.x - cameraPos.x, origin.y - cameraPos.y, origin.z - cameraPos.z);
        var data = multimeter.getModeData(stack);

        // A voltage pair waiting for its second click. Drawn white so it reads as provisional
        // rather than as a channel that is already measuring.
        if(data.contains("Pos")) {
            var pending = WireEndpointType.deserialize(data.getCompound("Pos"));
            if(pending != null && pending.isValid(world)) {
                var position = SableCompanion.INSTANCE.projectOutOfSubLevel(world, pending.getExactPosition(world));
                renderProbe(position.subtract(origin), buffer, matrixStack, world, player, 0xFFFFFFFF);
            }
        }

        // Every live channel, in the colour its trace is drawn in on the graph, so a lead in the
        // world can be matched to a curve on the screen.
        var channels = MultimeterItem.getChannels(stack);
        for(int c = 0; c < channels.size(); ++c) {
            var channel = channels.get(c);
            var colour = MultimeterTrace.colour(c);
            if(channel.isCurrent()) {
                var position = SableCompanion.INSTANCE.projectOutOfSubLevel(world, channel.getAttachment());
                renderProbe(position.subtract(origin), buffer, matrixStack, world, player, colour);
                continue;
            }
            var positive = channel.getPositive();
            if(positive != null && positive.isValid(world)) {
                var position = SableCompanion.INSTANCE.projectOutOfSubLevel(world, positive.getExactPosition(world));
                renderProbe(position.subtract(origin), buffer, matrixStack, world, player, colour);
            }
            var negative = channel.getNegative();
            if(negative != null && negative.isValid(world)) {
                var position = SableCompanion.INSTANCE.projectOutOfSubLevel(world, negative.getExactPosition(world));
                // Same channel, dimmed, so the two leads of a pair are distinguishable.
                renderProbe(position.subtract(origin), buffer, matrixStack, world, player, dim(colour));
            }
        }
        matrixStack.popPose();
    }

    public static void render(SuperRenderTypeBuffer buffer, PoseStack matrixStack, ClientLevel world, LocalPlayer player, Vec3 cameraPos) {
        render(buffer, matrixStack, world, player, player.getMainHandItem(), cameraPos);
        render(buffer, matrixStack, world, player, player.getOffhandItem(), cameraPos);
    }

    public static Component multimeterOverlayText(Player player) {
        Component right = null, left = null;
        var stack1 = player.getMainHandItem();
        if(stack1.getItem() instanceof MultimeterItem multimeter) {
            right = multimeter.getText(player.level(), player, stack1);
        }
        var stack2 = player.getOffhandItem();
        if(stack2.getItem() instanceof MultimeterItem multimeter) {
            left = multimeter.getText(player.level(), player, stack2);
        }
        if(right != null && left != null) {
            return Component.empty().append(left).append(" - ").append(right);
        } else if(right != null) {
            return right;
        } else {
            return left;
        }
    }
}
