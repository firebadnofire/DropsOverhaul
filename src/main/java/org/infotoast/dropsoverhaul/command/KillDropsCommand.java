package org.infotoast.dropsoverhaul.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.infotoast.dropsoverhaul.DropsOverhaul;

import java.util.List;
import java.util.stream.Collectors;

public class KillDropsCommand implements CommandExecutor {
    private final DropsOverhaul plugin;

    public KillDropsCommand(DropsOverhaul plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dropsoverhaul.killdrops")) {
            sender.sendMessage(DropsOverhaul.colorize(plugin.getConfig().getString("Messages.No-Permission", "&cAccess denied.")));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(DropsOverhaul.colorize(plugin.getConfig().getString("Messages.Player-Only", "&cOnly players can use this command.")));
            return true;
        }
        Player player = (Player) sender;

        int radius = plugin.getConfig().getInt("Settings.Kill-Drops-Radius");

        if (args.length > 1) return false;
        if (args.length == 1) {
            if (!sender.hasPermission("dropsoverhaul.killdrops.radius")) {
                sender.sendMessage(DropsOverhaul.colorize(plugin.getConfig().getString("Messages.No-Permission-Killdrops-Radius", "&cYou don't have permission to specify a radius.")));
                return true;
            }
            try {
                radius = Integer.parseInt(args[0]);
            } catch (NumberFormatException error) {
                sender.sendMessage("Radius must be a non-negative integer.");
                return true;
            }
        }
        if (radius < 0) {
            sender.sendMessage("Radius must be a non-negative integer.");
            return true;
        }

        List<Item> nearbyItems = player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius).stream()
                .filter(entity -> entity instanceof Item).map(entity -> (Item) entity).collect(Collectors.toList());
        int itemAmount = nearbyItems.size();
        nearbyItems.forEach(Item::remove);

        player.sendMessage(DropsOverhaul.colorize(plugin.getConfig().getString("Messages.Item-Delete", "&aSuccessfully removed %amount% items."))
                .replace("%amount%", itemAmount + ""));
        return true;
    }

}
