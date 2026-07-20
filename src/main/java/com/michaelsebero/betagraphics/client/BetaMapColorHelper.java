package com.michaelsebero.betagraphics.client;

import net.minecraft.block.material.MapColor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Restores Beta 1.7.3b's 14 map-item colours.
 *
 * SCOPE: only the 14 colours that existed in Beta (air/grass/sand/cloth/tnt/
 * ice/iron/foliage/snow/clay/dirt/stone/water/wood -- confirmed against your
 * MapColor.java reference) are touched. Every colour 1.12.2 has added since
 * (stained clay, concrete, the expanded ore/wood palette, etc.) is left at
 * its 1.12.2 default -- Beta has no opinion on blocks that didn't exist yet,
 * and deciding how ~40 newer colours should reduce onto Beta's 14 is a
 * design call, not a technical one; not attempted here.
 *
 * MECHANISM: rather than guess individual 1.12.2 field names (MapColor.GRASS
 * vs grassColor vs something else -- genuinely unknown without your
 * decompile), this resolves the backing MapColor[] registry by type scan and
 * indexes into it using Beta's own numeric indices. Beta's field order
 * (0=air..13=wood) matches the same order confirmed in an early MCP mapping
 * of this exact class, suggesting new colours are appended at higher indices
 * rather than inserted in the middle -- if that assumption doesn't hold for
 * your build, this patches the wrong slots. The boot log lists exactly what
 * got written so a wrong guess is easy to spot.
 *
 * colorValue's mutability is unconfirmed: some Forge-era snapshots of this
 * class show it as a plain public int, the 1.12.2 javadoc I checked shows it
 * as final. This handles both -- tries a direct set first, and only falls
 * back to clearing the FINAL bit (standard pre-JDK9 reflection, consistent
 * with this project's Java 8 runtime target) if the direct set is rejected.
 *
 * Called once from BetaGraphicsMod.init() -- unlike the grass/foliage
 * colormap, colorValue isn't resource-pack dependent, so this doesn't need
 * to re-apply on texture reload.
 */
public final class BetaMapColorHelper {

    private BetaMapColorHelper() {}

    // index -> Beta's colorValue. Index 0 (air) is intentionally skipped --
    // Beta's own value there is 0 (fully transparent), which is also
    // 1.12.2's convention for AIR, so there's nothing to change.
    private static final int[] BETA_COLOR_BY_INDEX = new int[14];
    static {
        BETA_COLOR_BY_INDEX[1]  = 8368696;   // grass
        BETA_COLOR_BY_INDEX[2]  = 16247203;  // sand
        BETA_COLOR_BY_INDEX[3]  = 10987431;  // cloth
        BETA_COLOR_BY_INDEX[4]  = 16711680;  // tnt
        BETA_COLOR_BY_INDEX[5]  = 10526975;  // ice
        BETA_COLOR_BY_INDEX[6]  = 10987431;  // iron -- yes, identical to cloth's value in Beta's own source
        BETA_COLOR_BY_INDEX[7]  = 31744;     // foliage
        BETA_COLOR_BY_INDEX[8]  = 16777215;  // snow
        BETA_COLOR_BY_INDEX[9]  = 10791096;  // clay
        BETA_COLOR_BY_INDEX[10] = 12020271;  // dirt
        BETA_COLOR_BY_INDEX[11] = 7368816;   // stone
        BETA_COLOR_BY_INDEX[12] = 4210943;   // water
        BETA_COLOR_BY_INDEX[13] = 6837042;   // wood
    }

    public static void patchMapColors() {
        MapColor[] registry = findColorRegistry();
        if (registry == null) {
            System.err.println("[BetaGraphics] Could not locate MapColor's backing array "
                + "(expected a static MapColor[] field) -- map colours left at 1.12.2 defaults.");
            return;
        }

        Field colorValueField = resolveColorValueField();
        if (colorValueField == null) {
            System.err.println("[BetaGraphics] Could not locate MapColor.colorValue -- "
                + "map colours left at 1.12.2 defaults.");
            return;
        }

        int patched = 0;
        for (int index = 1; index < BETA_COLOR_BY_INDEX.length; index++) {
            if (index >= registry.length || registry[index] == null) continue;
            if (setColorValue(colorValueField, registry[index], BETA_COLOR_BY_INDEX[index])) {
                patched++;
            }
        }
        System.out.println("[BetaGraphics] Patched " + patched + "/13 legacy map colours to Beta 1.7.3b values.");
    }

    private static MapColor[] findColorRegistry() {
        MapColor[] best = null;
        for (Field f : MapColor.class.getDeclaredFields()) {
            if (f.getType() != MapColor[].class) continue;
            f.setAccessible(true);
            try {
                MapColor[] candidate = (MapColor[]) f.get(null);
                if (candidate != null && (best == null || candidate.length > best.length)) {
                    best = candidate;
                }
            } catch (IllegalAccessException ignored) { }
        }
        return best;
    }

    private static Field resolveColorValueField() {
        try {
            Field f = MapColor.class.getDeclaredField("colorValue");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            System.err.println("[BetaGraphics] MapColor has no field named 'colorValue' -- "
                + "check the actual field name in your decompile and update BetaMapColorHelper.");
            return null;
        }
    }

    private static boolean setColorValue(Field colorValueField, MapColor color, int value) {
        try {
            colorValueField.setInt(color, value);
            return true;
        } catch (IllegalAccessException finalField) {
            try {
                Field modifiers = Field.class.getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(colorValueField, colorValueField.getModifiers() & ~Modifier.FINAL);
                colorValueField.setInt(color, value);
                return true;
            } catch (ReflectiveOperationException e) {
                System.err.println("[BetaGraphics] Could not set colorValue on " + color + ": " + e);
                return false;
            }
        }
    }
}
