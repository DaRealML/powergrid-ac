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
package org.patryk3211.powergrid.electricity.creative;

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

import java.util.function.Consumer;

/**
 * Click-and-hold slider setting the AC frequency of a creative source directly in-world, instead
 * of only through {@code /powergrid source}.
 * <p>
 * Zero is a real, meaningful value here -- it means DC/off, exactly like the amplitude slider's
 * own zero already does -- so unlike {@link org.patryk3211.powergrid.kinetics.generator.inductionrotor.AlternatorPolePairsBehaviour},
 * there is no minimum to shift away from. The shape is the plain one used by
 * {@link org.patryk3211.powergrid.kinetics.generator.inductionrotor.AlternatorWindingAngleBehaviour}:
 * no offset, no wraparound, the stored value is the displayed one.
 */
public class CreativeSourceFrequencyBehaviour extends ScrollValueBehaviour {
    /**
     * A dedicated type, for the same reason as {@code AlternatorPolePairsBehaviour#TYPE}: this
     * block entity now carries two {@link ScrollValueBehaviour}s, and {@code SmartBlockEntity}
     * keys them by type in a plain map -- two behaviours sharing a type means the second one
     * registered silently evicts the first, which then never ticks, reads, writes or renders
     * again. The amplitude slider keeps the inherited default type; this one needs its own so the
     * two do not collide.
     */
    public static final BehaviourType<CreativeSourceFrequencyBehaviour> TYPE = new BehaviourType<>();

    public static final int MIN_FREQUENCY = 0;
    public static final int MAX_FREQUENCY = 500;

    private Consumer<Integer> callback;

    public CreativeSourceFrequencyBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(Lang.translateDirect("gui.creative_source.frequency"), be, slot);
        between(MIN_FREQUENCY, MAX_FREQUENCY);
        setValue(MIN_FREQUENCY);
        withFormatter(CreativeSourceFrequencyBehaviour::describe);
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /**
     * Which slider on this block a click belongs to.
     * <p>
     * {@code ValueSettingsBehaviour.netId()} defaults to 0 for everything, and the server routes an
     * incoming setting to the FIRST behaviour whose id matches the packet
     * ({@code ValueSettingsPacket.applySettings}). Two sliders on one block therefore both answer
     * to 0, and every adjustment of either lands on whichever was registered first. The amplitude
     * slider keeps id 0 (its previous, implicit default); this one is 1 so the two do not collide.
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
        return "Frequency";
    }

    private static final String KEY = "Frequency";

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
    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(nbt, registries, clientPacket);
        nbt.putInt(KEY, value);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        // Unlike the amplitude slider, this one never inherits the shared key: a world without our
        // key predates this slider entirely, and 0 (DC/off) is exactly the behaviour that source
        // already had before the frequency slider existed.
        value = nbt.contains(KEY) ? nbt.getInt(KEY) : 0;
    }

    /** The selected frequency in Hz, 0 meaning DC/off. */
    public int getFrequency() {
        return getValue();
    }

    /**
     * Named {@code withFrequencyCallback}, not {@code withCallback}: {@code ScrollValueBehaviour}
     * already declares a {@code withCallback(Consumer<Integer>)} that returns {@code
     * ScrollValueBehaviour} for chaining, so a same-named {@code void} method here would be an
     * invalid @Override (incompatible return type), not a distinct method. Same reason {@link
     * CreativeSourceValueBehaviour} calls its own setter {@code withMultipliedCallback} instead of
     * {@code withCallback}.
     */
    public void withFrequencyCallback(Consumer<Integer> callback) {
        this.callback = callback;
    }

    @Override
    public void setValue(int value) {
        super.setValue(value);
        if(callback != null)
            callback.accept(getFrequency());
    }

    private static String describe(int hz) {
        return hz == 0 ? "DC" : hz + " Hz";
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        // The board sweeps 0..maxValue, which here is the stored value directly -- no offset, same
        // as the winding angle. A milestone every 50 Hz marks off mains-frequency multiples.
        return new ValueSettingsBoard(label, MAX_FREQUENCY, 50,
                ImmutableList.of(Lang.translateDirect("gui.creative_source.frequency")),
                new ValueSettingsFormatter(settings -> Component.literal(describe(settings.value()))));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        var freq = valueSetting.value();
        if(freq != value)
            playFeedbackSound(this);
        setValue(freq);
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, value);
    }
}
