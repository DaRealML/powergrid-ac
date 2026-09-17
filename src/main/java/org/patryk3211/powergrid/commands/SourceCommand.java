package org.patryk3211.powergrid.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity;

import static net.minecraft.commands.Commands.literal;

public class SourceCommand {
    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return literal("source")
                .then(literal("set")
                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                        .executes(SourceCommand::setSource)
                                        .then(Commands.argument("frequency", FloatArgumentType.floatArg())
                                                .executes(SourceCommand::setSourceWithFrequency)
                                                .then(Commands.argument("dc_offset", FloatArgumentType.floatArg())
                                                        .executes(SourceCommand::setSourceWithDCOffset)
                                                        .then(Commands.argument("phase", FloatArgumentType.floatArg())
                                                                .executes(SourceCommand::setSourceWithPhase)))))));
    }

    private static int setSource(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayerOrException();

        BlockPos pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(source);
        float value = ctx.getArgument("value", Float.class);

        if(!(player.level().getBlockEntity(pos) instanceof CreativeSourceBlockEntity be)) {
            source.sendFailure(Component.literal("Block is not a creative source"));
            return 0;
        }

        be.setValue(value);
        be.notifyUpdate();
        source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s", value, be.isCurrentSource() ? "amps" : "volts")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setSourceWithFrequency(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayerOrException();

        BlockPos pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(source);
        float value = ctx.getArgument("value", Float.class);
        float freq = ctx.getArgument("frequency", Float.class);

        if(!(player.level().getBlockEntity(pos) instanceof CreativeSourceBlockEntity be)) {
            source.sendFailure(Component.literal("Block is not a creative source"));
            return 0;
        }

        be.setValue(value, freq, 0);
        be.notifyUpdate();
        source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s at %f Hz", value, be.isCurrentSource() ? "amps" : "volts", freq)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setSourceWithDCOffset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayerOrException();

        BlockPos pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(source);
        float value = ctx.getArgument("value", Float.class);
        float freq = ctx.getArgument("frequency", Float.class);
        float dc = ctx.getArgument("dc_offset", Float.class);

        if(!(player.level().getBlockEntity(pos) instanceof CreativeSourceBlockEntity be)) {
            source.sendFailure(Component.literal("Block is not a creative source"));
            return 0;
        }

        be.setValue(value, freq, dc);
        be.notifyUpdate();
        source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s at %f Hz with %f DC offset", value, be.isCurrentSource() ? "amps" : "volts", freq, dc)), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Phase in degrees. Sources at the same frequency keep exactly this relationship to each other
     * wherever and whenever they were set, so three of these at 0, -120 and -240 are a three-phase
     * supply.
     */
    private static int setSourceWithPhase(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var player = source.getPlayerOrException();

        BlockPos pos = ctx.getArgument("position", WorldCoordinates.class).getBlockPos(source);
        float value = ctx.getArgument("value", Float.class);
        float freq = ctx.getArgument("frequency", Float.class);
        float dc = ctx.getArgument("dc_offset", Float.class);
        float phase = ctx.getArgument("phase", Float.class);

        if(!(player.level().getBlockEntity(pos) instanceof CreativeSourceBlockEntity be)) {
            source.sendFailure(Component.literal("Block is not a creative source"));
            return 0;
        }

        be.setValue(value, freq, dc, phase);
        be.notifyUpdate();
        source.sendSuccess(() -> Component.literal(String.format("Set source to %f %s at %f Hz with %f DC offset and %f degrees phase", value, be.isCurrentSource() ? "amps" : "volts", freq, dc, phase)), true);
        return Command.SINGLE_SUCCESS;
    }
}
