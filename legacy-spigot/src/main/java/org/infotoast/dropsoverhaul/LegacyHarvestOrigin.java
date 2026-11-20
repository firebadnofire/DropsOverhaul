package org.infotoast.dropsoverhaul;

/** Narrow, pinned CraftBukkit v1_12_R1 integration. Never used by shared code.
 * The native Block.a harvest path must be inside PlayerInteractManager.breakBlock.
 * Plugin-created entities without that exact origin retain normal spawn handling.
 */
final class LegacyHarvestOrigin {
    private LegacyHarvestOrigin() { }

    static boolean isNativeHarvest() {
        boolean nativeBlock = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals("net.minecraft.server.v1_12_R1.Block")
                    && frame.getMethodName().equals("a")) nativeBlock = true;
            if (frame.getClassName().equals("net.minecraft.server.v1_12_R1.PlayerInteractManager")
                    && frame.getMethodName().equals("breakBlock")) return nativeBlock;
        }
        return false;
    }
}
