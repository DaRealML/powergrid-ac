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
package org.patryk3211.powergrid.kinetics.generator.inductionrotor;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.patryk3211.powergrid.utility.Lang;

/**
 * Click-and-hold slider placing an alternator's winding around the stator.
 * <p>
 * Every alternator block on a shaft is its own winding with its own two terminals, and this is
 * where round the rotor that winding sits, in electrical degrees. Three alternators on one shaft
 * at 0, 120 and 240 degrees are a three-phase machine; wiring one terminal of each together makes
 * it a star with that junction as the neutral, and chaining them end to start makes it a delta.
 * Nothing about three-phase is a block of its own -- it is an arrangement of these.
 * <p>
 * Fifteen-degree steps. That lands exactly on every spacing a real machine uses -- 180 for
 * split-phase, 120 for three-phase, 90 for two-phase, 60 for six-phase -- and the notches every
 * 60 degrees make the three-phase positions a count of two notches apart. Finer would only give
 * a player more ways to be slightly wrong.
 */
public class AlternatorWindingAngleBehaviour extends ScrollValueBehaviour {
    /**
     * A dedicated type for the same reason as {@link AlternatorPolePairsBehaviour#TYPE}: the two
     * sliders live on the same block entity, and behaviours are a map keyed by type.
     */
    public static final BehaviourType<AlternatorWindingAngleBehaviour> TYPE = new BehaviourType<>();

    public static final int STEP_DEGREES = 15;
    public static final int STEPS = 360 / STEP_DEGREES;

    public AlternatorWindingAngleBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(Lang.translateDirect("gui.alternator.winding_angle"), be, slot);
        between(0, STEPS - 1);
        setValue(0);
        withFormatter(AlternatorWindingAngleBehaviour::describe);
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /**
     * Its own key in the block entity's NBT.
     * <p>
     * {@code ScrollValueBehaviour.write} puts its value under the fixed key {@code "ScrollValue"},
     * and every behaviour on a block entity shares one compound -- so two sliders on one block
     * overwrite each other on write and then both read the survivor back. The two sliders end up
     * shadowing a single number, live, because the same compound is what gets synced to the
     * client. Reading and writing our own key on top of the inherited one settles both directions;
     * the inherited key is still written, and simply ignored.
     */
    /**
     * Which slider on this block a click belongs to.
     * <p>
     * {@code ValueSettingsBehaviour.netId()} defaults to 0 for everything, and the server routes an
     * incoming setting to the FIRST behaviour whose id matches the packet
     * ({@code ValueSettingsPacket.applySettings}). Two sliders on one block therefore both answer
     * to 0, and every adjustment of either lands on whichever was registered first -- so the
     * winding angle never moved and the pole pairs took its numbers instead. The ids have to be
     * distinct, and only within this block entity.
     */
    @Override
    public int netId() {
        return 1;
    }

    /**
     * And the same again for Create's clipboard, which keys copied settings by
     * {@code getClipboardKey()} -- "Settings" for every value behaviour unless it says otherwise.
     */
    @Override
    public String getClipboardKey() {
        return "WindingAngle";
    }

    private static final String KEY = "WindingAngle";

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(nbt, registries, clientPacket);
        nbt.putInt(KEY, value);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        // Unlike the pole pairs, this one never inherits the shared key: a world without our key
        // predates this slider, and the number in there is somebody else's.
        value = nbt.contains(KEY) ? nbt.getInt(KEY) : 0;
    }

    /**
     * The selected angle in degrees, always in {@code [0, 360)}.
     * <p>
     * Clamped on the way out rather than trusted, because {@code ScrollValueBehaviour.read} assigns
     * whatever integer the save holds without passing it through {@code between}.
     */
    public int getDegrees() {
        return Math.floorMod(value, STEPS) * STEP_DEGREES;
    }

    public double getRadians() {
        return Math.toRadians(getDegrees());
    }

    private static String describe(int step) {
        return (Math.floorMod(step, STEPS) * STEP_DEGREES) + "°";
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        // The board sweeps 0..maxValue, which here is the stored value directly -- no offset, unlike
        // the pole pairs. A milestone every four steps puts a notch on every 60 degrees.
        return new ValueSettingsBoard(label, STEPS - 1, 4,
                ImmutableList.of(Lang.translateDirect("gui.alternator.winding_angle")),
                new ValueSettingsFormatter(settings -> Component.literal(describe(settings.value()))));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        var step = Math.floorMod(valueSetting.value(), STEPS);
        if(step != value)
            playFeedbackSound(this);
        setValue(step);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, Math.floorMod(value, STEPS));
    }
}
