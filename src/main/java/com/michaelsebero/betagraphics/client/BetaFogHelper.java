package com.michaelsebero.betagraphics.client;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;

/**
 * Restores Beta 1.7.3b's fog model and ambient atmospheric darkening.
 *
 * Fog model (setupBetaFog):
 *   Water  — GL_EXP, density 0.1
 *   Lava   — GL_EXP, density 2.0
 *   Normal — GL_LINEAR, start = farPlane * 0.25, end = farPlane
 *     Sky pass (startCoords < 0): start = 0, end = farPlane * 0.8
 *     GL_NV_fog_distance: spherical fog via EYE_RADIAL_NV when available
 *     Nether (no sky light): start = 0 (haze from camera)
 *   No void fog — that was added in Beta 1.8.
 *
 * Ambient darkening (fogColor1 / fogColor2 system):
 *   Beta's EntityRenderer tracked the player's local ambient brightness
 *   and multiplied the entire fog/sky colour by it each frame, making caves
 *   dramatically darker than 1.12.2 even at gamma=0. This system was removed
 *   in 1.12.2 and is restored here.
 *
 *   tickAmbientDarken() — called once per client tick:
 *     Underground: betaFogDarken smoothly lerps toward lightBrightnessTable[finalLight].
 *     Outdoor:     betaFogDarken is fixed at 1.0. getBetaSkyColor already applies
 *                  time-of-day brightness; multiplying again would double-darken and
 *                  cause a sunset shimmer due to the 20Hz lerp lag.
 *
 *   applyAmbientDarken() — injected before each RETURN in updateFogColor:
 *     Reads GL_COLOR_CLEAR_VALUE, multiplies by the partial-tick-interpolated
 *     ambient factor, and re-writes it via glClearColor. Because setupBetaFog
 *     reads GL_COLOR_CLEAR_VALUE as its fog colour source, it automatically
 *     picks up the darkened value without additional work.
 */
public final class BetaFogHelper {

    private static final int NV_FOG_DISTANCE = 34138;
    private static final int EYE_RADIAL_NV   = 34139;

    /**
     * betaFogDarken  = fogColor1: current smooth ambient value (updated each tick).
     * betaFogDarken2 = fogColor2: previous tick value (for partial-tick interpolation).
     * Initialised to 1.0 to avoid a flash of darkness before the first tick.
     */
    public static volatile float betaFogDarken  = 1.0F;
    public static volatile float betaFogDarken2 = 1.0F;

    private static volatile Field   farPlaneField      = null;
    private static volatile boolean farPlaneSearchDone = false;

    private static final FloatBuffer CLEAR_COLOR_BUF = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer FOG_COLOR_BUF   = BufferUtils.createFloatBuffer(4);

    private static final float BETA_FOG_START_FACTOR   = 0.25F;
    private static final float BETA_SKY_FOG_END_FACTOR = 0.80F;

    private BetaFogHelper() {}

    // ── Tick update ───────────────────────────────────────────────────────────

    /**
     * Updates the ambient darkening factor once per client tick.
     *
     * Mirrors Beta's updateRenderer() tracking:
     *   ambient   = world.getLightBrightness(playerX, playerY, playerZ)
     *   fogColor1 += (ambient - fogColor1) * 0.1F
     *
     * Outdoor path sets betaFogDarken = 1.0 directly (no lerp) to keep the
     * ambient factor in sync with getBetaSkyColor's celestial-angle brightness.
     *
     * @param world     The client world.
     * @param playerPos The player's block position (feet).
     */
    public static void tickAmbientDarken(World world, BlockPos playerPos) {
        int combined     = world.getCombinedLight(playerPos, 0);
        int blockLight   = (combined >> 4)  & 0xF;
        int skyLight     = (combined >> 20) & 0xF;
        int skyLightSub  = world.calculateSkylightSubtracted(1.0F);
        int effectiveSky = Math.max(0, skyLight - skyLightSub);
        int finalLight   = Math.max(effectiveSky, blockLight);
        float ambient    = world.provider.getLightBrightnessTable()[finalLight];

        if (skyLight > 0) {
            betaFogDarken2 = betaFogDarken;
            betaFogDarken  = 1.0F;
        } else {
            betaFogDarken2 = betaFogDarken;
            betaFogDarken += (ambient - betaFogDarken) * 0.1F;
        }
    }

    // ── Per-frame ambient darkening ───────────────────────────────────────────

    /**
     * Multiplies the GL clear colour by Beta's ambient factor.
     * Injected by MixinEntityRenderer before each RETURN in updateFogColor.
     *
     * Reads the fog colour written by vanilla, multiplies R/G/B by the
     * partial-tick-interpolated betaFogDarken value, and re-writes it.
     * setupBetaFog subsequently reads GL_COLOR_CLEAR_VALUE, so the darkening
     * is automatically present in the rendered fog.
     *
     * @param er           EntityRenderer this (for signature compatibility; unused).
     * @param partialTicks Frame interpolation factor.
     */
    public static void applyAmbientDarken(EntityRenderer er, float partialTicks) {
        float mult = betaFogDarken2 + (betaFogDarken - betaFogDarken2) * partialTicks;

        CLEAR_COLOR_BUF.clear();
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, CLEAR_COLOR_BUF);
        float r = CLEAR_COLOR_BUF.get(0) * mult;
        float g = CLEAR_COLOR_BUF.get(1) * mult;
        float b = CLEAR_COLOR_BUF.get(2) * mult;

        GL11.glClearColor(r, g, b, 0.0F);
    }

    // ── Fog setup ─────────────────────────────────────────────────────────────

    /**
     * Full replacement for EntityRenderer.setupFog(int, float).
     * Implements Beta 1.7.3b's complete GL fog model.
     *
     * @param er           The EntityRenderer instance (this).
     * @param startCoords  {@code < 0} for sky pass, {@code >= 0} for terrain pass.
     * @param partialTicks Retained for signature compatibility; unused.
     */
    public static void setupBetaFog(EntityRenderer er, int startCoords, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.world == null) return;

        World  world  = mc.world;
        Entity entity = mc.getRenderViewEntity();
        if (entity == null) entity = mc.player;
        if (entity == null) return;

        final float farPlane = Math.max(16.0F, mc.gameSettings.renderDistanceChunks * 16.0F);
        tryWriteFarPlane(er, farPlane);

        // Read fog colour from GL_COLOR_CLEAR_VALUE (already ambient-darkened by
        // applyAmbientDarken, which fires before setupFog in the render order).
        CLEAR_COLOR_BUF.clear();
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, CLEAR_COLOR_BUF);
        float fogR = CLEAR_COLOR_BUF.get(0);
        float fogG = CLEAR_COLOR_BUF.get(1);
        float fogB = CLEAR_COLOR_BUF.get(2);

        FOG_COLOR_BUF.clear();
        FOG_COLOR_BUF.put(fogR).put(fogG).put(fogB).put(1.0F);
        FOG_COLOR_BUF.flip();
        GL11.glFog(GL11.GL_FOG_COLOR, FOG_COLOR_BUF);

        GL11.glNormal3f(0.0F, -1.0F, 0.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        if (entity.isInsideOfMaterial(Material.WATER)) {
            GL11.glFogi(GL11.GL_FOG_MODE,    GL11.GL_EXP);
            GL11.glFogf(GL11.GL_FOG_DENSITY, 0.1F);

        } else if (entity.isInsideOfMaterial(Material.LAVA)) {
            GL11.glFogi(GL11.GL_FOG_MODE,    GL11.GL_EXP);
            GL11.glFogf(GL11.GL_FOG_DENSITY, 2.0F);

        } else {
            GL11.glFogi(GL11.GL_FOG_MODE,  GL11.GL_LINEAR);
            GL11.glFogf(GL11.GL_FOG_START, farPlane * BETA_FOG_START_FACTOR);
            GL11.glFogf(GL11.GL_FOG_END,   farPlane);

            if (startCoords < 0) {
                GL11.glFogf(GL11.GL_FOG_START, 0.0F);
                GL11.glFogf(GL11.GL_FOG_END,   farPlane * BETA_SKY_FOG_END_FACTOR);
            }

            if (GLContext.getCapabilities().GL_NV_fog_distance) {
                GL11.glFogi(NV_FOG_DISTANCE, EYE_RADIAL_NV);
            }

            if (!world.provider.hasSkyLight()) {
                GL11.glFogf(GL11.GL_FOG_START, 0.0F);
            }
        }

        GlStateManager.enableFog();
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Locates EntityRenderer.farPlaneDistance by value and writes {@code value} to it.
     * Uses reflection by type scan since the field name varies across Forge builds.
     * Searches only once; result is cached for subsequent calls.
     */
    private static void tryWriteFarPlane(EntityRenderer er, float value) {
        if (farPlaneSearchDone) {
            if (farPlaneField != null) {
                try { farPlaneField.setFloat(er, value); }
                catch (IllegalAccessException ignored) {}
            }
            return;
        }

        Field candidate = null;
        try {
            for (Field f : EntityRenderer.class.getDeclaredFields()) {
                if (f.getType() != float.class) continue;
                f.setAccessible(true);
                float current;
                try { current = f.getFloat(er); }
                catch (IllegalAccessException e) { continue; }
                if (Math.abs(current - value) < 1.0F && current >= 32.0F) {
                    candidate = f;
                    System.out.println("[BetaGraphics] Located farPlaneDistance as '"
                        + f.getName() + "' (value=" + current + ") — far plane patch ready.");
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[BetaGraphics] farPlaneDistance scan error: " + e);
        }

        if (candidate == null) {
            System.out.println("[BetaGraphics] INFO: farPlaneDistance not found (expected ~"
                + value + "). Cloud rendering uses its own lookup — no fog impact.");
        }

        farPlaneField      = candidate;
        farPlaneSearchDone = true;

        if (farPlaneField != null) {
            try { farPlaneField.setFloat(er, value); }
            catch (IllegalAccessException ignored) {}
        }
    }
}
