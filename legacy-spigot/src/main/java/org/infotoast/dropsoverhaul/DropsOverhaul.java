package org.infotoast.dropsoverhaul;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.infotoast.dropsoverhaul.command.DropsOverhaulCommand;
import org.infotoast.dropsoverhaul.command.KillDropsCommand;
import org.infotoast.dropsoverhaul.common.DropPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.ArrayList;

public final class DropsOverhaul extends JavaPlugin implements Listener {
    private final Map<Location, BlockBreakEvent> pending = new HashMap<Location, BlockBreakEvent>();
    private DropPolicy policy;
    private boolean transferring;

    @Override
    public void onEnable() {
        if (!getServer().getClass().getPackage().getName().equals("org.bukkit.craftbukkit.v1_12_R1")
                || !getServer().getBukkitVersion().startsWith("1.12.2-")) {
            throw new IllegalStateException("This artifact requires CraftBukkit/Spigot 1.12.2 v1_12_R1");
        }
        saveDefaultConfig();
        if (getConfig().getInt("Config-Version") != 2) {
            throw new IllegalStateException("Unsupported configuration schema; preserve config.yml and migrate to Config-Version: 2");
        }
        refreshPolicy();
        getCommand("dropsoverhaul").setExecutor(new DropsOverhaulCommand(this));
        getCommand("killdrops").setExecutor(new KillDropsCommand(this));
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DropsOverhaul has been enabled! Native harvest origin pinned to v1_12_R1.");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        refreshPolicy();
    }

    @Override
    public void saveConfig() {
        super.saveConfig();
        refreshPolicy();
    }

    private void refreshPolicy() {
        List<String> blocks = new ArrayList<String>();
        for (String configured : getConfig().getStringList("Settings.Drops-To-Remove")) {
            String resolved = resolveBlockName(configured);
            if (resolved == null) {
                getLogger().warning("Configured block unavailable on Minecraft 1.12.2; left unchanged and inactive: " + configured);
            } else blocks.add(resolved);
        }
        policy = new DropPolicy(blocks, getConfig().getBoolean("Settings.Full-Inventory-Delete"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberBreak(final BlockBreakEvent event) {
        if (!event.isDropItems() || event.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        String name = canonicalName(event.getBlock().getType(), event.getBlock().getData());
        if (!policy.selects(name)) return;
        final Location position = event.getBlock().getLocation();
        pending.put(position, event);
        getServer().getScheduler().runTask(this, new Runnable() {
            @Override public void run() { pending.remove(position, event); }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transferNativeDrop(ItemSpawnEvent event) {
        if (transferring || pending.isEmpty()) return;
        Location position = event.getLocation().getBlock().getLocation();
        BlockBreakEvent broken = pending.get(position);
        if (broken == null || broken.isCancelled() || !broken.isDropItems() || !LegacyHarvestOrigin.isNativeHarvest()) return;
        Player player = broken.getPlayer();
        if (!player.isOnline() || !player.getWorld().equals(event.getLocation().getWorld())) return;
        // No vanilla suppression until the actual native, plugin-adjusted stack exists.
        ItemStack nativeStack = event.getEntity().getItemStack().clone();
        if (nativeStack.getType() == Material.AIR || nativeStack.getAmount() <= 0) return;
        transferring = true;
        try {
            event.setCancelled(true);
            policy.transfer(Collections.singletonList(nativeStack), stack -> player.getInventory().addItem(stack).values(),
                    stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        } finally {
            transferring = false;
        }
    }

    private String canonicalName(Material material, byte metadata) {
        return org.infotoast.dropsoverhaul.common.LegacyBlocks.canonical(material.name(), metadata);
    }

    public static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String resolveBlockName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("GRASS") || upper.equals("GRASS_BLOCK")) return "GRASS_BLOCK";
        for (String alias : new String[]{"GRANITE", "POLISHED_GRANITE", "DIORITE", "POLISHED_DIORITE",
                "ANDESITE", "POLISHED_ANDESITE", "COARSE_DIRT", "PODZOL", "RED_SAND"}) {
            if (upper.equals(alias)) return alias;
        }
        Material material = Material.getMaterial(upper);
        return material != null && material.isBlock() ? material.name() : null;
    }
}
