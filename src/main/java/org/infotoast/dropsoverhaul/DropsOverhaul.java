package org.infotoast.dropsoverhaul;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.infotoast.dropsoverhaul.command.DropsOverhaulCommand;
import org.infotoast.dropsoverhaul.command.KillDropsCommand;
import org.infotoast.dropsoverhaul.common.DropPolicy;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class DropsOverhaul extends JavaPlugin implements Listener {
    private DropPolicy policy;

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
        policy = new DropPolicy(getConfig().getStringList("Settings.Drops-To-Remove"),
                getConfig().getBoolean("Settings.Full-Inventory-Delete"));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if ((!getConfig().contains("Config-Version")) || getConfig().contains("Settings.Config-Version")) {
            saveOldConfigAndMakeNew();
        }
        // Handle older configurations
        if (getConfig().getInt("Config-Version") != 2) {
            saveOldConfigAndMakeNew();
        }
        refreshPolicy();

        getCommand("dropsoverhaul").setExecutor(new DropsOverhaulCommand(this));
        getCommand("killdrops").setExecutor(new KillDropsCommand(this));
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("DropsOverhaul has been enabled!");
    }

    private void saveOldConfigAndMakeNew() {
        File oldConfigFile = new File(this.getDataFolder(), "config.yml");
        oldConfigFile.renameTo(new File(getDataFolder(), "config-old.yml"));
        this.saveResource("config.yml", true);
        getLogger().warning("NOTICE: DropsOverhaul Config has been changed.");
        getLogger().warning("PREVIOUS UPDATE INCLUDES BREAKING CONFIG CHANGES.");
        getLogger().warning("Your old configuration has been saved to config-old.yml");
        getLogger().warning("If you have made changes, they will need to be remade in new config format.");
        getLogger().warning("The server will be started with default configuration values.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!policy.selects(event.getBlockState().getType().name())) {
            return;
        }

        event.setCancelled(true);

        List<ItemStack> items = event.getItems().stream().map(item -> item.getItemStack().clone()).toList();
        policy.transfer(items, stack -> event.getPlayer().getInventory().addItem(stack).values(),
                stack -> event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), stack));
    }

    public static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String resolveBlockName(String name) {
        Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        return material != null && material.isBlock() ? material.name() : null;
    }

}
