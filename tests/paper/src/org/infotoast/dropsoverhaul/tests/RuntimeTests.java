package org.infotoast.dropsoverhaul.tests;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.infotoast.dropsoverhaul.DropsOverhaul;

import java.util.*;

/** Test-only plugin: installed exclusively in isolated runtime test servers. */
public final class RuntimeTests extends JavaPlugin implements Listener {
    private String scenario;
    private Location target;
    private DropsOverhaul subject;
    private int dropEvents;
    private int nativeAmount;

    @Override
    public void onEnable() {
        subject = (DropsOverhaul) getServer().getPluginManager().getPlugin("DropsOverhaul");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new NativeDrops(this), this);
        getCommand("dofixture").setExecutor(this);
    }

    @EventHandler
    public void preventPickup(EntityPickupItemEvent event) {
        // Ground-drop assertions must not depend on random vanilla item velocity.
        if (scenario != null && event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void cancel(BlockBreakEvent event) {
        if ("cancelled".equals(scenario) && event.getBlock().getLocation().equals(target)) {
            event.setCancelled(true);
        }
    }

    void trackDrop(Location position, org.bukkit.entity.Item item) {
        if (!position.equals(target)) return;
        dropEvents++;
        if ("custom".equals(scenario)) item.setItemStack(new ItemStack(Material.EMERALD, 3));
        nativeAmount += item.getItemStack().getAmount();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || args.length != 2) return false;
        Player player = (Player) sender;
        try {
            if (args[0].equals("prepare")) {
                // Leave Brigadier's command queue before asserting nested dispatch results.
                getServer().getScheduler().runTask(this, () -> {
                    try { prepare(player, args[1]); }
                    catch (Exception error) {
                        getLogger().log(java.util.logging.Level.SEVERE, "Fixture failure", error);
                        player.sendMessage("DO_TEST_FAIL " + args[1] + " " + error.getMessage());
                    }
                });
            }
            else if (args[0].equals("verify")) verify(player, args[1]);
            else throw new IllegalArgumentException("Unknown fixture action");
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Fixture failure", error);
            player.sendMessage("DO_TEST_FAIL " + args[1] + " " + error.getMessage());
        }
        return true;
    }

    private void prepare(Player player, String name) {
        scenario = name;
        dropEvents = 0;
        nativeAmount = 0;
        if (name.equals("commands")) testCommands(player);
        World world = player.getWorld();
        world.setTime(6000);
        world.setStorm(false);
        world.getEntitiesByClass(Item.class).forEach(Item::remove);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            world.getBlockAt(x, 99, z).setType(Material.BEDROCK);
            for (int y = 100; y <= 104; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
        }
        player.teleport(new Location(world, 0.5, 100, 0.5, 0, 0));
        player.setGameMode(name.equals("creative") ? GameMode.CREATIVE : GameMode.SURVIVAL);
        player.setFoodLevel(20);
        player.getInventory().clear();
        ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
        if (name.equals("silk")) tool.addEnchantment(Enchantment.SILK_TOUCH, 1);
        if (name.equals("fortune")) tool.addEnchantment(Enchantment.getByName("FORTUNE") != null ? Enchantment.getByName("FORTUNE") : Enchantment.getByName("LOOT_BONUS_BLOCKS"), 3);
        player.getInventory().setItem(0, tool);
        player.getInventory().setHeldItemSlot(0);
        if (name.startsWith("full") || name.equals("partial")) {
            for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.BEDROCK, 64));
            if (name.equals("partial")) player.getInventory().setItem(1, new ItemStack(Material.COBBLESTONE, 63));
        }
        subject.getConfig().set("Settings.Drops-To-Remove", name.equals("metadata") ? Arrays.asList("GRANITE") : Arrays.asList("STONE", "DIAMOND_ORE"));
        subject.getConfig().set("Settings.Full-Inventory-Delete", name.equals("full-delete"));
        subject.saveConfig();
        target = new Location(world, 0, 100, 2);
        world.getBlockAt(target).setType(name.equals("unselected") ? Material.GOLD_BLOCK :
                name.equals("fortune") ? Material.DIAMOND_ORE : Material.STONE);
        if (name.equals("metadata")) NativeDrops.prepareMetadata(world.getBlockAt(target));
        player.sendMessage("DO_TEST_READY " + name);
    }

    private void verify(Player player, String name) {
        if (!name.equals(scenario)) throw new IllegalStateException("Scenario mismatch");
        boolean untouched = name.equals("cancelled");
        require(target.getBlock().getType() == (untouched ? Material.STONE : Material.AIR), "block state");
        Material expected = name.equals("metadata") ? NativeDrops.metadataMaterial() : name.equals("unselected") ? Material.GOLD_BLOCK : name.equals("silk") ? Material.STONE :
                name.equals("fortune") ? Material.DIAMOND : name.equals("custom") ? Material.EMERALD : Material.COBBLESTONE;
        int inventory = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == expected) {
                if (name.equals("metadata")) require(NativeDrops.metadataMatches(stack), "granite item metadata");
                inventory += stack.getAmount();
            }
        }
        int ground = 0;
        for (Item item : player.getWorld().getEntitiesByClass(Item.class)) {
            require(item.getItemStack().getType() == expected, "unexpected ground drop");
            ground += item.getItemStack().getAmount();
        }
        if (name.equals("creative") || untouched || name.equals("full-delete")) {
            require(inventory == 0 && ground == 0, "unexpected drops");
        } else if (name.equals("full-keep") || name.equals("unselected")) {
            require(inventory == 0 && ground == 1, "native ground drop conservation: " + inventory + "/" + ground);
        } else if (name.equals("fortune")) {
            require(inventory >= 1 && inventory <= 4 && inventory == nativeAmount && ground == 0, "fortune native drop conservation");
        } else {
            int amount = name.equals("partial") ? 64 : name.equals("custom") ? 3 : 1;
            require(inventory == amount && ground == 0, "inventory/ground conservation: " + inventory + "/" + ground);
        }
        if (!untouched && !name.equals("creative")) {
            require(name.equals("fortune") ? dropEvents >= 1 : dropEvents == 1, "native drop event count");
        }
        if (!untouched && !name.equals("creative")) {
            require(player.getInventory().getItem(0).getDurability() == 1, "native tool durability");
        }
        getLogger().info("DO_TEST_PASS " + name + " inventory=" + inventory + " ground=" + ground);
        player.sendMessage("DO_TEST_PASS " + name);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void testCommands(Player player) {
        subject.getConfig().set("Settings.Drops-To-Remove", new ArrayList<String>());
        subject.saveConfig();
        getServer().dispatchCommand(player, "dropsoverhaul addblock STONE");
        getServer().dispatchCommand(player, "dropsoverhaul addblock STONE");
        require(subject.getConfig().getStringList("Settings.Drops-To-Remove").equals(Arrays.asList("STONE")),
                "idempotent add command: " + subject.getConfig().getStringList("Settings.Drops-To-Remove")
                        + " op=" + player.isOp() + " permission=" + player.hasPermission("dropsoverhaul.addblock"));
        subject.getConfig().set("Settings.Drops-To-Remove", new ArrayList<String>());
        getServer().dispatchCommand(player, "dropsoverhaul reload");
        require(subject.getConfig().getStringList("Settings.Drops-To-Remove").equals(Arrays.asList("STONE")), "configuration persistence/reload");
        boolean op = player.isOp();
        try {
            player.setOp(false);
            getServer().dispatchCommand(player, "dropsoverhaul addblock GOLD_BLOCK");
            require(!subject.getConfig().getStringList("Settings.Drops-To-Remove").contains("GOLD_BLOCK"), "permission enforcement");
        } finally { player.setOp(op); }
        getServer().dispatchCommand(player, "dropsoverhaul removeblock");
        getServer().dispatchCommand(player, "dropsoverhaul list");
        getServer().dispatchCommand(player, "killdrops invalid");
        getServer().dispatchCommand(player, "dropsoverhaul removeblock STONE");
        require(subject.getConfig().getStringList("Settings.Drops-To-Remove").isEmpty(), "remove command");
        Item near = player.getWorld().dropItem(player.getLocation().add(0, 0, 1), new ItemStack(Material.DIRT));
        Item aboveRadius = player.getWorld().dropItem(player.getLocation().add(0, 3, 0), new ItemStack(Material.DIRT));
        try {
            getServer().dispatchCommand(player, "killdrops 2");
            require(!near.isValid() && aboveRadius.isValid(), "location-centered cleanup radius");
        } finally { near.remove(); aboveRadius.remove(); }
    }
}


