package org.infotoast.dropsoverhaul.tests;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.common.Mod;

/** Test-only native event hooks, never included in a distribution. */
@Mod("dropsoverhaul_tests")
public final class NativeTestHooks {
    private String scenario = "";
    private final BlockPos target = new BlockPos(0, 100, 3);

    public NativeTestHooks() {
        RegisterCommandsEvent.BUS.addListener(event -> event.getDispatcher().register(
                Commands.literal("dofixtureitems").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> cleanupItems(context.getSource()))));
        RegisterCommandsEvent.BUS.addListener(event -> event.getDispatcher().register(
                Commands.literal("dofixturemode").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("scenario", StringArgumentType.word()).executes(context -> {
                            scenario = StringArgumentType.getString(context, "scenario");
                            return 1;
                        }))));
        BlockEvent.BreakEvent.BUS.addListener(Priority.HIGH, event -> {
            if (!scenario.equals("cancelled") || !event.getPos().equals(target)) return false;
            event.setResult(Result.DENY);
            return true;
        });
        EntityJoinLevelEvent.BUS.addListener(Priority.HIGH, event -> {
            if (!(event.getEntity() instanceof ItemEntity item) || !item.blockPosition().equals(target)) return;
            if (scenario.equals("custom")) item.setItem(new ItemStack(Items.EMERALD, 3));
            for (var player : event.getLevel().players()) {
                if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(Component.literal("DO_NATIVE_LOOT " + item.getItem().getCount()));
            }
        });
    }

    private int cleanupItems(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var level = source.getLevel();
        ItemEntity near = new ItemEntity(level, player.getX(), player.getY(), player.getZ() + 1, new ItemStack(Items.DIRT));
        ItemEntity above = new ItemEntity(level, player.getX(), player.getY() + 3, player.getZ(), new ItemStack(Items.DIRT));
        for (ItemEntity item : new ItemEntity[]{near, above}) {
            item.setNoGravity(true);
            item.setPickUpDelay(32767);
            level.addFreshEntity(item);
        }
        player.sendSystemMessage(Component.literal("DO_CLEANUP_READY " + near.getId() + " " + above.getId()));
        return 1;
    }
}
