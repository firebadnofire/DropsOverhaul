package org.infotoast.dropsoverhaul.common;

/** Meaningful pre-flattening mappings only. No mappings for absent blocks. */
public final class LegacyBlocks {
    private LegacyBlocks() { }
    public static String canonical(String name, int data) {
        if (name.equals("GRASS")) return "GRASS_BLOCK";
        if (name.equals("STONE") && data >= 0 && data <= 6) {
            return new String[]{"STONE", "GRANITE", "POLISHED_GRANITE", "DIORITE", "POLISHED_DIORITE", "ANDESITE", "POLISHED_ANDESITE"}[data];
        }
        if (name.equals("DIRT") && data == 1) return "COARSE_DIRT";
        if (name.equals("DIRT") && data == 2) return "PODZOL";
        if (name.equals("SAND") && data == 1) return "RED_SAND";
        return name;
    }
}
