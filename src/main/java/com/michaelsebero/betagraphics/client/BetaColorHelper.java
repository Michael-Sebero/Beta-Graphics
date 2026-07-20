package com.michaelsebero.betagraphics.client;

import net.minecraft.world.ColorizerFoliage;
import net.minecraft.world.ColorizerGrass;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Restores Beta 1.7.3b's grass/foliage colormap gradient.
 *
 * GAP: ColorizerGrass/ColorizerFoliage.getGrassColor(double,double) /
 * getFoliageColor(double,double) sample a 256x256 buffer populated from
 * grasscolor.png/foliagecolor.png at texture-stitch time. The *algorithm*
 * (buffer[v<<8|u], confirmed identical in 1.12.2 -- same method name, same
 * signature) isn't what differs between versions; the actual pixel *values*
 * baked into those two images are what would differ, and your reference set
 * only has the Java source that samples them, not the PNGs themselves.
 *
 * Rather than fabricate plausible-looking colours and present them as a
 * verified restoration, this loads an optional override if you supply one:
 *   assets/betagraphics/textures/colormap/beta_grass.png
 *   assets/betagraphics/textures/colormap/beta_foliage.png
 * both 256x256, same layout Beta/1.12.2 already use (e.g. pulled straight
 * from a 1.7.3b client jar's own colormap assets). Drop them into the mod's
 * resources and this activates automatically on the next texture reload;
 * until then it logs once and leaves 1.12.2's stock gradient in place.
 *
 * The three hardcoded foliage overrides (pine/birch/third) bypass this
 * buffer entirely and are handled unconditionally by MixinColorizerFoliage,
 * since those values came straight from your reference source rather than
 * an image -- they apply regardless of whether the override PNGs exist.
 *
 * Water is intentionally not handled here: your ColorizerWater.java
 * reference shows only a buffer setter with no getter/consumer anywhere in
 * what was provided, so whether (or how) Beta actually applies a water tint
 * through this class is unconfirmed -- see chat for the open question.
 *
 * Called from BetaGraphicsEventHandler.onTextureStitchPost -- texture-stitch
 * time, not mod init, so this re-applies after every resource pack switch
 * instead of being silently overwritten by vanilla's own reload. TIMING
 * ASSUMPTION (unverified): this assumes vanilla's own colormap load happens
 * at or before TextureStitchEvent.Post, not after it -- if grass/foliage
 * revert to stock colours after a resource pack switch specifically (but
 * look right on first launch), vanilla's load is happening later than this
 * hook and a different/later hook point is needed instead.
 */
public final class BetaColorHelper {

    private BetaColorHelper() {}

    private static final String GRASS_OVERRIDE   = "/assets/betagraphics/textures/colormap/beta_grass.png";
    private static final String FOLIAGE_OVERRIDE = "/assets/betagraphics/textures/colormap/beta_foliage.png";

    private static boolean warnedMissingOnce = false;

    public static void applyBetaColormaps() {
        boolean grassApplied   = tryApply(GRASS_OVERRIDE, ColorizerGrass.class);
        boolean foliageApplied = tryApply(FOLIAGE_OVERRIDE, ColorizerFoliage.class);

        if (!grassApplied && !foliageApplied && !warnedMissingOnce) {
            warnedMissingOnce = true;
            System.out.println("[BetaGraphics] No beta_grass.png/beta_foliage.png override found under "
                + "assets/betagraphics/textures/colormap/ -- grass/foliage gradient stays at 1.12.2's "
                + "stock colours. Pine/birch hardcoded overrides are still applied via MixinColorizerFoliage.");
        }
    }

    private static boolean tryApply(String resourcePath, Class<?> colorizerClass) {
        int[] buffer = loadBuffer(resourcePath);
        if (buffer == null) return false;

        Method setter = findBufferSetter(colorizerClass);
        if (setter == null) {
            System.err.println("[BetaGraphics] Found " + resourcePath + " but couldn't locate "
                + colorizerClass.getSimpleName() + "'s buffer setter (expected a public static "
                + "void method taking a single int[] argument) -- override not applied.");
            return false;
        }

        try {
            setter.invoke(null, (Object) buffer);
            System.out.println("[BetaGraphics] Applied beta colormap override: " + resourcePath
                + " -> " + colorizerClass.getSimpleName() + "." + setter.getName() + "()");
            return true;
        } catch (ReflectiveOperationException e) {
            System.err.println("[BetaGraphics] Failed to apply " + resourcePath + ": " + e);
            return false;
        }
    }

    /** 256x256 ARGB image -> flat 65536-entry RGB int buffer, same layout vanilla's own loader uses. */
    private static int[] loadBuffer(String resourcePath) {
        try (InputStream in = BetaColorHelper.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null || img.getWidth() != 256 || img.getHeight() != 256) {
                System.err.println("[BetaGraphics] " + resourcePath
                    + " must be exactly 256x256 -- ignoring.");
                return null;
            }
            int[] buffer = new int[65536];
            img.getRGB(0, 0, 256, 256, buffer, 0, 256);
            // Strip alpha -- ColorizerGrass/Foliage buffers are opaque RGB ints.
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] &= 0x00FFFFFF;
            }
            return buffer;
        } catch (Exception e) {
            System.err.println("[BetaGraphics] Error reading " + resourcePath + ": " + e);
            return null;
        }
    }

    /**
     * Finds the public static void(int[]) setter on a Colorizer class.
     * Beta's own MCP mapping never resolved a clean name for these
     * (func_28181_a / func_28152_a) -- rather than guess a 1.12.2 name that
     * may or may not match, this resolves structurally by signature, the
     * same "don't trust a guessed name" instinct behind this project's
     * existing reflection-based field resolution (e.g. MixinAmbientOcclusionFace).
     */
    private static Method findBufferSetter(Class<?> colorizerClass) {
        for (Method m : colorizerClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0] == int[].class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
