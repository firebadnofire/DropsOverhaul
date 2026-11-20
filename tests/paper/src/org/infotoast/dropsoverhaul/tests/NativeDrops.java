package org.infotoast.dropsoverhaul.tests;

import org.bukkit.event.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

final class NativeDrops implements Listener {
    private final RuntimeTests tests;
    NativeDrops(RuntimeTests tests) { this.tests = tests; }
    static void prepareMetadata(Block block) { block.setType(Material.GRANITE); }
    static Material metadataMaterial() { return Material.GRANITE; }
    static boolean metadataMatches(ItemStack stack) { return stack.getType() == Material.GRANITE; }

    @EventHandler(priority = EventPriority.LOW)
    public void customize(BlockDropItemEvent event) {
        if (!event.getItems().isEmpty()) tests.trackDrop(event.getBlock().getLocation(), event.getItems().get(0));
    }
}
