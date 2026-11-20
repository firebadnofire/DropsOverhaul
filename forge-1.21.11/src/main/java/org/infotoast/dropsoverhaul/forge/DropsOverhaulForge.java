package org.infotoast.dropsoverhaul.forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.*;
import org.infotoast.dropsoverhaul.common.DropPolicy;
import org.infotoast.dropsoverhaul.config.YamlSettings;

import java.io.IOException;
import java.util.*;

@Mod("dropsoverhaul")
public final class DropsOverhaulForge {
    private record BreakKey(Object level, BlockPos position) { }
    private final Map<BreakKey, BlockEvent.BreakEvent> pending = new HashMap<>();
    private final Map<String, PermissionNode<Boolean>> permissions = new HashMap<>();
    private YamlSettings settings;
    private DropPolicy policy;
    private boolean transferring;

    public DropsOverhaulForge() {
        try {
            settings = new YamlSettings(FMLPaths.CONFIGDIR.get().resolve("DropsOverhaul/config.yml"));
            policy = settings.policy();
        } catch (IOException error) { throw new IllegalStateException("Cannot load DropsOverhaul configuration", error); }
        for (String name : List.of("reload", "addblock", "removeblock", "list", "killdrops", "killdrops.radius")) {
            permissions.put(name, new PermissionNode<>("dropsoverhaul", name, PermissionTypes.BOOLEAN,
                    (player, uuid, context) -> name.equals("killdrops") || (player != null && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))));
        }
        PermissionGatherEvent.Nodes.BUS.addListener(event -> permissions.values().forEach(event::addNodes));
        RegisterCommandsEvent.BUS.addListener(this::commands);
        BlockEvent.BreakEvent.BUS.addListener(Priority.LOWEST, this::remember);
        EntityJoinLevelEvent.BUS.addListener(Priority.LOWEST, this::transfer);
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> pending.clear());
        ServerStartingEvent.BUS.addListener(event -> org.slf4j.LoggerFactory.getLogger("DropsOverhaul").info("DropsOverhaul has been enabled! Native Forge."));
    }

    private BreakKey key(Object level, BlockPos position) { return new BreakKey(level, position.immutable()); }

    private void remember(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.isCreative() || player.isSpectator() || event.getResult().isDenied()) return;
        Identifier id = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        String block = id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : id.toString();
        if (policy.selects(block)) pending.put(key(event.getLevel(), event.getPos()), event);
    }

    private boolean transfer(EntityJoinLevelEvent event) {
        if (transferring || pending.isEmpty() || !(event.getEntity() instanceof ItemEntity item) || event.loadedFromDisk()) return false;
        BlockEvent.BreakEvent broken = pending.get(key(event.getLevel(), item.blockPosition()));
        if (broken == null || broken.getResult().isDenied() || !(broken.getPlayer() instanceof ServerPlayer player)) return false;
        // Preserve the native loot path. No independently calculated replacement loot.
        boolean blockFrame = false;
        boolean nativeHarvest = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals("net.minecraft.world.level.block.Block")) blockFrame = true;
            if (frame.getClassName().equals("net.minecraft.server.level.ServerPlayerGameMode")) { nativeHarvest = blockFrame; break; }
        }
        if (!nativeHarvest || item.getItem().isEmpty()) return false;
        ItemStack stack = item.getItem().copy();
        transferring = true;
        try {
            policy.transfer(List.of(stack), drop -> {
                player.getInventory().add(drop);
                return drop.isEmpty() ? Collections.emptyList() : List.of(drop);
            }, drop -> NativeOverflow.drop(player, drop));
        } finally { transferring = false; }
        return true;
    }

    private boolean allowed(CommandSourceStack source, String node) {
        return source.getEntity() instanceof ServerPlayer player ? PermissionAPI.getPermission(player, permissions.get(node)) : source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("dropsoverhaul").executes(context -> help(context.getSource()));
        for (String action : List.of("reload", "list", "addblock", "removeblock")) {
            var child = Commands.literal(action).requires(source -> allowed(source, action));
            if (action.equals("addblock") || action.equals("removeblock")) child.then(Commands.argument("block", StringArgumentType.word())
                    .executes(context -> admin(context.getSource(), action, StringArgumentType.getString(context, "block"))));
            else child.executes(context -> admin(context.getSource(), action, null));
            root.then(child);
        }
        var command = event.getDispatcher().register(root);
        event.getDispatcher().register(Commands.literal("do").executes(context -> help(context.getSource())).redirect(command));
        var cleanup = event.getDispatcher().register(Commands.literal("killdrops").requires(source -> allowed(source, "killdrops"))
                .executes(context -> kill(context.getSource(), settings.radius()))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0)).requires(source -> allowed(source, "killdrops.radius"))
                        .executes(context -> kill(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))));
        event.getDispatcher().register(Commands.literal("kd").requires(source -> allowed(source, "killdrops")).executes(context -> kill(context.getSource(), settings.radius())).redirect(cleanup));
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(settings.message("Command.Help-Header", "Drops Overhaul Commands")), false);
        String[][] entries = {{"reload", "Reload-Command"}, {"addblock", "AddBlock-Command"},
                {"removeblock", "RemoveBlock-Command"}, {"list", "List-Command"}};
        for (String[] entry : entries) {
            if (allowed(source, entry[0])) {
                source.sendSuccess(() -> Component.literal(settings.message("Command." + entry[1], "/do " + entry[0])), false);
            }
        }
        return 1;
    }

    private int admin(CommandSourceStack source, String action, String name) {
        try {
            if (action.equals("reload")) settings.reload();
            else if (action.equals("list")) {
                source.sendSuccess(() -> Component.literal(settings.message("Command.List-Header", "Selected blocks:")), false);
                for (String block : settings.blocks()) source.sendSuccess(() -> Component.literal(settings.message("Command.List-Item", "- %block%").replace("%block%", block)), false);
                return 1;
            } else {
                List<String> blocks = settings.blocks();
                Identifier id = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
                String resolved = id != null && id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : name;
                if (action.equals("addblock")) {
                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) { source.sendFailure(Component.literal("Unknown block: " + name)); return 0; }
                    if (!blocks.contains(resolved)) blocks.add(resolved);
                } else if (!blocks.remove(resolved)) { source.sendFailure(Component.literal("Block is not selected: " + name)); return 0; }
                settings.setBlocks(blocks);
            }
            policy = settings.policy();
            String key = action.equals("reload") ? "Reloaded" : action.equals("addblock") ? "Block-Added" : "Block-Removed";
            String message = settings.message("Command." + key, "DropsOverhaul " + action + " completed.");
            if (name != null) message = message.replace("%block%", name.toUpperCase(Locale.ROOT));
            Component response = Component.literal(message);
            source.sendSuccess(() -> response, false);
            return 1;
        } catch (IOException error) { source.sendFailure(Component.literal("Configuration operation failed: " + error.getMessage())); return 0; }
    }

    private int kill(CommandSourceStack source, int radius) {
        if (!(source.getEntity() instanceof ServerPlayer player)) { source.sendFailure(Component.literal(settings.message("Player-Only", "Only players can use this command."))); return 0; }
        if (radius < 0) { source.sendFailure(Component.literal("Configured radius must be non-negative")); return 0; }
        var items = player.level().getEntitiesOfClass(ItemEntity.class, new AABB(player.position(), player.position()).inflate(radius));
        items.forEach(ItemEntity::discard);
        source.sendSuccess(() -> Component.literal(settings.message("Item-Delete", "Removed %amount% items.").replace("%amount%", Integer.toString(items.size()))), false);
        return items.size();
    }
}



