package com.michaelsebero.betagraphics.mixin;

import net.minecraft.world.ColorizerFoliage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Restores Beta 1.7.3b's hardcoded foliage-colour overrides.
 *
 * These bypass ColorizerFoliage's temperature/rainfall buffer entirely --
 * confirmed present, unchanged in structure, in your 1.7.3b reference
 * (ColorizerFoliage.java): getFoliageColorPine(), getFoliageColorBirch(), and
 * a third, unlabeled override (func_31073_c in the Beta MCP mapping -- its
 * caller isn't in the reference set, so which biome invokes it is
 * unconfirmed, and it's deliberately left out below rather than guessed at
 * with an unverified 1.12.2 method name/signature).
 *
 * getFoliageColorPine/getFoliageColorBirch are used here as the 1.12.2
 * method names based on these being long-stable, widely-referenced MCP
 * names across many MC versions -- high confidence, but not verified against
 * your actual decompile. If either name doesn't resolve, the Mixin AP will
 * fail the build with "Cannot find target for @Overwrite method", the same
 * failure mode already fixed elsewhere in this project (MixinWorld,
 * MixinEntityRenderer) -- same fix applies: find the real name in your
 * decompiled ColorizerFoliage and swap it in below.
 */
@Mixin(ColorizerFoliage.class)
public abstract class MixinColorizerFoliage {

    /**
     * @reason Beta's fixed pine-foliage colour (RGB 97,153,97 -- a muted, dull green).
     * @author michaelsebero
     */
    @Overwrite
    public static int getFoliageColorPine() {
        return 6396257;
    }

    /**
     * @reason Beta's fixed birch-foliage colour (RGB 128,167,85 -- lighter, yellow-green).
     * @author michaelsebero
     */
    @Overwrite
    public static int getFoliageColorBirch() {
        return 8431445;
    }
}
