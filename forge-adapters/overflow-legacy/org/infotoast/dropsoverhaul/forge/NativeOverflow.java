package org.infotoast.dropsoverhaul.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class NativeOverflow {
    private NativeOverflow() { }

    static void drop(ServerPlayer player, ItemStack stack) {
        player.drop(stack, false);
    }
}
