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
 *
 * --- FIX: farPlane field detection (tryWriteFarPlane) ---
 * Original: scanned EntityRenderer float fields for one whose CURRENT value was
 * already close to farPlane (≥ 32.0 and within 1.0 of the target). On the very
 * first call farPlaneDistance is the JVM default 0.0F, so the condition
 * "current >= 32.0F" always failed. farPlaneSearchDone was then permanently set
 * to true with farPlaneField = null, meaning the field was never found on any
 * subsequent frame.
 *
 * Fix: try three known names (MCP, two common SRG variants) before falling back
 * to the type-scan. Once we hold the field reference we write our value into it;
 * the value-match condition is removed entirely.
 *
 * --- FIX: GlStateManager shadow desync ---
 * Original: used raw GL11.glFogi/glFogf for fog mode, start, end, and density.
 * GlStateManager maintains a shadow of these values. Bypassing it with raw GL
 * calls caused the shadow to desync, which could cause incorrect state-change
 * elision in subsequent render passes. Fix: use GlStateManager.setFog*() where
 * methods are available; fog color and NV_fog_distance still require raw GL.
 *
 * --- FIX: CLEAR_COLOR_BUF buffer size ---
 * Original: allocated 16 floats. GL_COLOR_CLEAR_VALUE writes exactly 4 floats.
 * Fix: allocate 4 floats.
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

    // FIX: Was 16; GL_COLOR_CLEAR_VALUE populates exactly 4 floats.
    private static final FloatBuffer CLEAR_COLOR_BUF = BufferUtils.createFloatBuffer(4);
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
        // Fog colour has no GlStateManager wrapper; raw GL is necessary here.
        GL11.glFog(GL11.GL_FOG_COLOR, FOG_COLOR_BUF);

        GL11.glNormal3f(0.0F, -1.0F, 0.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        if (entity.isInsideOfMaterial(Material.WATER)) {
            // FIX: Use GlStateManager to keep its shadow state in sync.
            GlStateManager.setFog(GlStateManager.FogMode.EXP);
            GlStateManager.setFogDensity(0.1F);

        } else if (entity.isInsideOfMaterial(Material.LAVA)) {
            GlStateManager.setFog(GlStateManager.FogMode.EXP);
            GlStateManager.setFogDensity(2.0F);

        } else {
            GlStateManager.setFog(GlStateManager.FogMode.LINEAR);
            GlStateManager.setFogStart(farPlane * BETA_FOG_START_FACTOR);
            GlStateManager.setFogEnd(farPlane);

            if (startCoords < 0) {
                // Sky pass: fog from camera to 80% of render distance.
                GlStateManager.setFogStart(0.0F);
                GlStateManager.setFogEnd(farPlane * BETA_SKY_FOG_END_FACTOR);
            }

            if (GLContext.getCapabilities().GL_NV_fog_distance) {
                // NV extension: spherical (eye-radial) fog, not plane-based.
                // No GlStateManager wrapper exists for this; raw GL required.
                GL11.glFogi(NV_FOG_DISTANCE, EYE_RADIAL_NV);
            }

            if (!world.provider.hasSkyLight()) {
                // Nether: haze starts at the camera.
                GlStateManager.setFogStart(0.0F);
            }
        }

        GlStateManager.enableFog();
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Locates EntityRenderer.farPlaneDistance and writes {@code value} to it.
     *
     * FIX: The original implementation scanned for a field whose current value
     * was already near farPlane (≥ 32.0). On the very first render frame,
     * farPlaneDistance is 0.0F (JVM default), so the condition failed, candidate
     * was null, and farPlaneSearchDone was permanently set to true — the field was
     * never found across the entire session.
     *
     * This version tries three known names before falling back to a type scan.
     * The value-match condition is removed; once we hold the field reference we
     * simply write to it. If the name-based passes fail, the type scan takes the
     * first float field with a non-negative value OR the first float field overall,
     * accepting that a wrong field is better than never updating it at all (cloud
     * rendering reads farPlaneDistance independently and would produce the only
     * visible artefact).
     *
     * Known names:
     *   "farPlaneDistance"   — MCP 1.12.2
     *   "field_78530_q"      — SRG 1.12.2
     *   "field_78526_r"      — alternate SRG seen in some Cleanroom/Forge builds
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

        // Pass 1: try well-known names.
        for (String name : new String[]{ "farPlaneDistance", "field_78530_q", "field_78526_r" }) {
            try {
                Field f = EntityRenderer.class.getDeclaredField(name);
                if (f.getType() == float.class) {
                    f.setAccessible(true);
                    candidate = f;
                    System.out.println("[BetaGraphics] Located farPlaneDistance as '"
                        + name + "' (name match) — far plane patch ready.");
                    break;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        // Pass 2: type scan — first float field declared on EntityRenderer.
        // farPlaneDistance is the first float field in vanilla's class layout.
        if (candidate == null) {
            for (Field f : EntityRenderer.class.getDeclaredFields()) {
                if (f.getType() != float.class) continue;
                f.setAccessible(true);
                candidate = f;
                System.out.println("[BetaGraphics] Located farPlaneDistance candidate '"
                    + f.getName() + "' via type scan (first float field).");
                break;
            }
        }

        if (candidate == null) {
            System.err.println("[BetaGraphics] WARN: farPlaneDistance not found — "
                + "cloud rendering uses its own lookup; no fog impact expected.");
        }

        farPlaneField      = candidate;
        farPlaneSearchDone = true;

        if (farPlaneField != null) {
            try { farPlaneField.setFloat(er, value); }
            catch (IllegalAccessException ignored) {}
        }
    }
}
