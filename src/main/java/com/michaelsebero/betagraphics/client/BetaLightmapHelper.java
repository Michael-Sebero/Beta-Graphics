package com.michaelsebero.betagraphics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * Replaces 1.12.2's EntityRenderer lightmap with Beta 1.7.3b's lighting algorithm.
 *
 * Beta had no lightmap texture. All brightness was applied per-vertex via direct
 * glColor4f calls in RenderBlocks. In 1.12.2, a 16×16 lightmap texture is computed
 * each frame by EntityRenderer.updateLightmap and uploaded to GL. Without interception,
 * vanilla applies a quartic gamma lift making light level 0 appear ~40% bright.
 *
 * Beta 1.7.3b lighting model:
 *   effectiveSky = max(0, skylightMap[x][y][z] - skyLightSubtracted)
 *   finalLight   = max(effectiveSky, blocklightMap[x][y][z])
 *   brightness   = lightBrightnessTable[finalLight]   // 0.1–1.0 (patched ambient)
 *   glColor4f(brightness, brightness, brightness, 1.0f)  // neutral white, no tint
 *
 * generateBetaLightmap() overwrites all 256 lightmap pixels with these values
 * and calls updateDynamicTexture() to upload them to GL. It is called in two ways:
 *   - By MixinEntityRenderer before each RETURN in updateLightmap (~60Hz),
 *     guaranteeing our values are the final GL upload each frame.
 *   - Directly from BetaGraphicsEventHandler.onClientTick (20Hz) as a fallback.
 *
 * The EntityRenderer's DynamicTexture field is located by type scan rather than
 * name, making the lookup immune to SRG/MCP mapping differences across Forge builds.
 * World.skylightSubtracted is located by both its MCP and SRG names with a fallback
 * to calculateSkylightSubtracted(1.0F) if neither name is found.
 */
public final class BetaLightmapHelper {

    private static final Field LIGHTMAP_TEXTURE_FIELD;
    private static final Field SKYLIGHT_SUBTRACTED_FIELD;

    static {
        Field lightmap = null;
        for (Field f : EntityRenderer.class.getDeclaredFields()) {
            if (f.getType() == DynamicTexture.class) {
                f.setAccessible(true);
                lightmap = f;
                break;
            }
        }
        LIGHTMAP_TEXTURE_FIELD = lightmap;
        if (LIGHTMAP_TEXTURE_FIELD == null) {
            System.err.println("[BetaGraphics] FATAL: Could not locate DynamicTexture "
                + "in EntityRenderer — lightmap will not be patched.");
        } else {
            System.out.println("[BetaGraphics] Located EntityRenderer DynamicTexture field '"
                + lightmap.getName() + "' — lightmap patch ready.");
        }

        Field sky = null;
        for (String name : new String[]{ "skylightSubtracted", "field_72989_e" }) {
            try {
                Field f = World.class.getDeclaredField(name);
                f.setAccessible(true);
                sky = f;
                System.out.println("[BetaGraphics] Located World.skylightSubtracted as '"
                    + name + "'.");
                break;
            } catch (NoSuchFieldException ignored) { }
        }
        SKYLIGHT_SUBTRACTED_FIELD = sky;
        if (SKYLIGHT_SUBTRACTED_FIELD == null) {
            System.out.println("[BetaGraphics] World.skylightSubtracted not found by name — "
                + "falling back to calculateSkylightSubtracted(1.0F).");
        }
    }

    private BetaLightmapHelper() {}

    /**
     * Overwrites the EntityRenderer lightmap texture with Beta 1.7.3b's values.
     *
     * Fills the 16×16 lightmap using Beta's max(sky, block) logic pre-baked per
     * (sky, block) index pair. Output is neutral white with no gamma, no tint,
     * and no post-processing. Calls updateDynamicTexture() to upload to GL.
     */
    public static void generateBetaLightmap() {
        if (LIGHTMAP_TEXTURE_FIELD == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null || mc.entityRenderer == null) return;

        World world = mc.world;

        int skyLightSub;
        if (SKYLIGHT_SUBTRACTED_FIELD != null) {
            try {
                skyLightSub = SKYLIGHT_SUBTRACTED_FIELD.getInt(world);
            } catch (IllegalAccessException e) {
                skyLightSub = world.calculateSkylightSubtracted(1.0F);
            }
        } else {
            skyLightSub = world.calculateSkylightSubtracted(1.0F);
        }

        DynamicTexture lightmapTexture;
        try {
            lightmapTexture = (DynamicTexture) LIGHTMAP_TEXTURE_FIELD.get(mc.entityRenderer);
        } catch (IllegalAccessException e) {
            return;
        }
        if (lightmapTexture == null) return;

        int[] pixels = lightmapTexture.getTextureData();
        if (pixels == null || pixels.length < 256) return;

        float[] lbt = world.provider.getLightBrightnessTable();

        for (int skyIndex = 0; skyIndex < 16; skyIndex++) {
            int effectiveSky = Math.max(0, skyIndex - skyLightSub);

            for (int blockIndex = 0; blockIndex < 16; blockIndex++) {
                int   finalLight = Math.max(effectiveSky, blockIndex);
                float brightness = MathHelper.clamp(lbt[finalLight], 0.0F, 1.0F);
                int   b          = (int)(brightness * 255.0F);

                pixels[skyIndex * 16 + blockIndex] = 0xFF000000 | (b << 16) | (b << 8) | b;
            }
        }

        lightmapTexture.updateDynamicTexture();
    }
}
