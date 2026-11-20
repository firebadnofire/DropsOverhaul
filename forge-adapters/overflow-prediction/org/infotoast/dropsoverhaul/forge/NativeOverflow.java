package org.infotoast.dropsoverhaul.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

final class NativeOverflow {
    private NativeOverflow() { }

    static void drop(ServerPlayer player, ItemStack stack) {
        // Overflow originates on the server; there is no predicted client toss.
        player.drop(stack, false, Prediction.SERVER_ONLY);
    }
}
