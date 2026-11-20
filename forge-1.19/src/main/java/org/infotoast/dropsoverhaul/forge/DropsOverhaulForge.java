package org.infotoast.dropsoverhaul.forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
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
                    (player, uuid, context) -> name.equals("killdrops") || (player != null && player.createCommandSourceStack().hasPermission(2))));
        }
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void gather(PermissionGatherEvent.Nodes event) { permissions.values().forEach(event::addNodes); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent event) { remember(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onJoin(EntityJoinLevelEvent event) { if (transfer(event)) event.setCanceled(true); }
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) { if (event.phase == TickEvent.Phase.END) pending.clear(); }
    @SubscribeEvent
    public void start(ServerStartingEvent event) { org.slf4j.LoggerFactory.getLogger("DropsOverhaul").info("DropsOverhaul has been enabled! Native Forge."); }

    private BreakKey key(Object level, BlockPos position) { return new BreakKey(level, position.immutable()); }

    private void remember(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.isCreative() || player.isSpectator() || event.isCanceled()) return;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(event.getState().getBlock());
        String block = id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : id.toString();
        if (policy.selects(block)) pending.put(key(event.getLevel(), event.getPos()), event);
    }

    private boolean transfer(EntityJoinLevelEvent event) {
        if (transferring || pending.isEmpty() || !(event.getEntity() instanceof ItemEntity item) || event.loadedFromDisk()) return false;
        BlockEvent.BreakEvent broken = pending.get(key(event.getLevel(), item.blockPosition()));
        if (broken == null || broken.isCanceled() || !(broken.getPlayer() instanceof ServerPlayer player)) return false;
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
            }, drop -> player.drop(drop, false));
        } finally { transferring = false; }
        return true;
    }

    private boolean allowed(CommandSourceStack source, String node) {
        return source.getEntity() instanceof ServerPlayer player ? PermissionAPI.getPermission(player, permissions.get(node)) : source.hasPermission(2);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
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
        CommandFeedback.send(source, Component.literal(settings.message("Command.Help-Header", "Drops Overhaul Commands")));
        String[][] entries = {{"reload", "Reload-Command"}, {"addblock", "AddBlock-Command"},
                {"removeblock", "RemoveBlock-Command"}, {"list", "List-Command"}};
        for (String[] entry : entries) {
            if (allowed(source, entry[0])) {
                CommandFeedback.send(source, Component.literal(settings.message("Command." + entry[1], "/do " + entry[0])));
            }
        }
        return 1;
    }

    private int admin(CommandSourceStack source, String action, String name) {
        try {
            if (action.equals("reload")) settings.reload();
            else if (action.equals("list")) {
                CommandFeedback.send(source, Component.literal(settings.message("Command.List-Header", "Selected blocks:")));
                for (String block : settings.blocks()) CommandFeedback.send(source, Component.literal(settings.message("Command.List-Item", "- %block%").replace("%block%", block)));
                return 1;
            } else {
                List<String> blocks = settings.blocks();
                ResourceLocation id = ResourceLocation.tryParse(name.toLowerCase(Locale.ROOT));
                String resolved = id != null && id.getNamespace().equals("minecraft") ? id.getPath().toUpperCase(Locale.ROOT) : name;
                if (action.equals("addblock")) {
                    if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) { source.sendFailure(Component.literal("Unknown block: " + name)); return 0; }
                    if (!blocks.contains(resolved)) blocks.add(resolved);
                } else if (!blocks.remove(resolved)) { source.sendFailure(Component.literal("Block is not selected: " + name)); return 0; }
                settings.setBlocks(blocks);
            }
            policy = settings.policy();
            String key = action.equals("reload") ? "Reloaded" : action.equals("addblock") ? "Block-Added" : "Block-Removed";
            String message = settings.message("Command." + key, "DropsOverhaul " + action + " completed.");
            if (name != null) message = message.replace("%block%", name.toUpperCase(Locale.ROOT));
            Component response = Component.literal(message);
            CommandFeedback.send(source, response);
            return 1;
        } catch (IOException error) { source.sendFailure(Component.literal("Configuration operation failed: " + error.getMessage())); return 0; }
    }

    private int kill(CommandSourceStack source, int radius) {
        if (!(source.getEntity() instanceof ServerPlayer player)) { source.sendFailure(Component.literal(settings.message("Player-Only", "Only players can use this command."))); return 0; }
        if (radius < 0) { source.sendFailure(Component.literal("Configured radius must be non-negative")); return 0; }
        var items = source.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(player.position(), player.position()).inflate(radius));
        items.forEach(ItemEntity::discard);
        CommandFeedback.send(source, Component.literal(settings.message("Item-Delete", "Removed %amount% items.").replace("%amount%", Integer.toString(items.size()))));
        return items.size();
    }
}





