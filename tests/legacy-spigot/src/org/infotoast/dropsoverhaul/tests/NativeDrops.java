package org.infotoast.dropsoverhaul.tests;

import org.bukkit.event.*;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

final class NativeDrops implements Listener {
    private final RuntimeTests tests;
    NativeDrops(RuntimeTests tests) { this.tests = tests; }
    static void prepareMetadata(Block block) { block.setType(Material.STONE); block.setData((byte) 1); }
    static Material metadataMaterial() { return Material.STONE; }
    static boolean metadataMatches(ItemStack stack) { return stack.getType() == Material.STONE && stack.getDurability() == 1; }

    @EventHandler(priority = EventPriority.LOW)
    public void customize(ItemSpawnEvent event) {
        tests.trackDrop(event.getLocation().getBlock().getLocation(), event.getEntity());
    }
}
