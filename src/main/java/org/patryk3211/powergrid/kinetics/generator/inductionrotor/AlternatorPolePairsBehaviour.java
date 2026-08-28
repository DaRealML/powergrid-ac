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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.utility.Lang;

/**
 * Click-and-hold slider selecting the pole-pair count of an alternator.
 * <p>
 * Pole pairs set the electrical frequency of the machine, {@code f = rpm * p / 60}. One pole
 * pair on a shaft at its 272 rpm ceiling gives about 4.5 Hz; reaching mains frequency needs
 * eleven or twelve, and costs proportionally more solver sub-ticks because the waveform has to
 * be sampled that much more finely. The slider shows the resulting frequency so the trade is
 * visible at the point the choice is made rather than buried in a config file.
 * <p>
 * The stored value is the pole-pair count itself, 1 upwards. Create's slider always sweeps from
 * zero, so {@link #getValueSettings()} and {@link #setValueSettings} shift by one to keep the
 * leftmost notch meaning "1 pair" instead of an unusable zero.
 */
public class AlternatorPolePairsBehaviour extends ScrollValueBehaviour {
    /**
     * A dedicated type, so looking this behaviour up cannot collide with any other
     * {@link ScrollValueBehaviour} that might later be added to the shared commutator block
     * entity. Create's renderer and input handler both match on {@code instanceof}, so the
     * slider still works normally.
     */
    public static final BehaviourType<AlternatorPolePairsBehaviour> TYPE = new BehaviourType<>();

    public static final int MIN_POLE_PAIRS = 1;
    public static final int MAX_POLE_PAIRS = 16;

    public AlternatorPolePairsBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(Lang.translateDirect("gui.alternator.pole_pairs"), be, slot);
        between(MIN_POLE_PAIRS, MAX_POLE_PAIRS);
        setValue(MIN_POLE_PAIRS);
        withFormatter(AlternatorPolePairsBehaviour::describe);
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    /**
     * The selected pole-pair count, floored at 1.
     * <p>
     * The floor matters on load: {@code ScrollValueBehaviour.read} assigns the stored value
     * directly, so a world saved before this slider existed deserialises a missing key as 0.
     */
    public int getPolePairs() {
        return Math.max(value, MIN_POLE_PAIRS);
    }

    /** Electrical frequency this pole-pair count produces at the configured maximum shaft speed. */
    public static double frequencyAtMaxSpeed(int polePairs) {
        var maxRpm = ModdedConfigs.server().kinetics.generatorControls.rotorRPMMax.get();
        return maxRpm * polePairs / 60.0;
    }

    private static String describe(int polePairs) {
        return String.format("%d  (%.1f Hz)", polePairs, frequencyAtMaxSpeed(polePairs));
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        // The board sweeps 0..maxValue, so it is offset by one against the stored value.
        // The row needs a real label: an empty component still reserves a small plate beside
        // the bar, which then renders blank. The milestone interval spaces the notch markers
        // and is also the step size for a shift-drag, so 1 would make shift do nothing.
        return new ValueSettingsBoard(label, MAX_POLE_PAIRS - MIN_POLE_PAIRS, 4,
                ImmutableList.of(Lang.translateDirect("gui.alternator.pole_pairs")),
                new ValueSettingsFormatter(settings -> Component.literal(describe(settings.value() + MIN_POLE_PAIRS))));
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        var polePairs = valueSetting.value() + MIN_POLE_PAIRS;
        if(polePairs != value)
            playFeedbackSound(this);
        setValue(polePairs);
    }

    @Override
    public ValueSettings getValueSettings() {
        // Through getPolePairs() rather than the raw field, so a value of 0 from an older save
        // cannot produce a negative column index.
        return new ValueSettings(0, getPolePairs() - MIN_POLE_PAIRS);
    }
}
