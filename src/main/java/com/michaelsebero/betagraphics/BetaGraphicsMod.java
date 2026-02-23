package com.michaelsebero.betagraphics;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.io.File;

/**
 * Beta Graphics — by michaelsebero
 *
 * Restores the exact lighting and rendering model used by Minecraft Beta 1.7.3b.
 *
 * Feature overview:
 *
 *  1. lightBrightnessTable
 *     Overwritten on every world/dimension load using Beta's 0.1 ambient floor.
 *     Vanilla 1.12.2 uses 0.05, producing darker caves and a different light curve.
 *
 *  2. Lightmap algorithm
 *     EntityRenderer.updateLightmap is replaced with Beta's formula:
 *       effectiveSky = max(0, skyLight - world.skylightSubtracted)
 *       finalLight   = max(effectiveSky, blockLight)
 *       brightness   = lightBrightnessTable[finalLight]   (neutral white)
 *     No torch colour tint, no per-frame interpolation, no gamma curve.
 *
 *  3. Gamma lock
 *     gammaSetting is locked to 0.0F each tick. Beta had no brightness slider.
 *     Locking to 0.0F suppresses vanilla's quartic gamma lift and prevents a
 *     brightness flash when vanilla uploads its own lightmap mid-frame.
 *
 *  4. Smooth lighting default
 *     On the very first run (fresh install or missing config), ambientOcclusion
 *     is set to 1 (Minimum smooth lighting) — matching Beta's minimal shading.
 *     After that, the user's own choice is respected and never overridden; the
 *     chosen value is saved to options.txt by the game as normal.
 *
 *     The geometry-aware AO darkening factor is still neutralised at all AO
 *     settings (0/1/2) by MixinAmbientOcclusionFace, which resets
 *     vertexColorMultiplier to 1.0F after each calculation.  This means:
 *       Off  (0) — no smooth lighting, flat per-face shading.
 *       Min  (1) — smooth lighting geometry but Beta-accurate uniform brightness.
 *       Max  (2) — same as Min; AO darkening is suppressed regardless.
 *     The setting therefore controls vertex interpolation only, not darkness.
 *
 *  5. Dusk/dawn wave
 *     When skylightSubtracted changes, all loaded chunk sections are marked dirty
 *     simultaneously, replicating Beta's updateAllRenderers() sweep at dusk/dawn.
 *
 *  6. Fog system
 *     EntityRenderer.setupFog is replaced with Beta 1.7.3b's fog model:
 *       Overworld — GL_LINEAR, start = renderDist * 0.25, end = renderDist
 *       Sky pass  — start = 0, end = renderDist * 0.8 (seamless horizon blend)
 *       NV_fog_distance — spherical fog when the GL extension is available
 *       Nether    — GL_FOG_START = 0 (haze from camera)
 *       Water     — GL_EXP density 0.1 / Lava — GL_EXP density 2.0
 *     Note: Beta 1.7.3b had no void fog (added in Beta 1.8).
 *
 *  7. Ambient fog darkening
 *     Restores Beta's fogColor1/fogColor2 system. Each tick, betaFogDarken
 *     smoothly tracks getLightBrightness at the player's position (0.1 in a cave,
 *     1.0 in full daylight). Each frame the GL clear colour is multiplied by the
 *     partial-tick-interpolated factor, darkening the entire atmospheric backdrop
 *     underground — not just individual block faces.
 *
 *  8. Shadow light threshold
 *     Render.renderShadow is replaced: shadows only draw where light > 3,
 *     matching Beta's Render.java line 118 hard cutoff. Shadow opacity also
 *     scales with getLightBrightness using the patched brightness table.
 *
 *  9. Flat shading for entity lighting
 *     GL_FLAT is applied before each living entity render (RenderLivingEvent.Pre)
 *     and GL_SMOOTH restored after (RenderLivingEvent.Post), matching the effect
 *     of Beta's RenderHelper.enableStandardItemLighting().
 *
 * 10. Cross-chunk light fix
 *     Forces client VBO rebuild across chunk boundaries when light-emitting blocks
 *     are placed or removed.
 *
 * Required asset override:
 *   Beta's vignette has a dark centre that brightens toward screen edges — the
 *   opposite of vanilla. Place the provided vignette.png at:
 *     assets/minecraft/textures/misc/vignette.png
 */
@Mod(
    modid                     = BetaGraphicsMod.MOD_ID,
    name                      = "Beta Graphics",
    version                   = "1.0.0",
    acceptedMinecraftVersions = "[1.12.2]",
    clientSideOnly            = true
)
public class BetaGraphicsMod {

    public static final String MOD_ID = "betagraphics";

    @Mod.Instance(MOD_ID)
    public static BetaGraphicsMod instance;

    // ── Config ────────────────────────────────────────────────────────────────

    /**
     * Forge configuration file: config/betagraphics.cfg
     *
     * Contains a single flag, "aoDefaultApplied", that records whether this mod
     * has already set the one-time AO default. Persists across game restarts so
     * subsequent launches never override the user's chosen smooth lighting value.
     */
    private static Configuration config;

    private static final String CFG_CATEGORY = "defaults";
    private static final String CFG_KEY_AO   = "aoDefaultApplied";

    /**
     * Returns true if the one-time AO default has already been written during a
     * previous session. When false, the event handler will set ambientOcclusion=1
     * exactly once, then call markAoDefaultApplied() to prevent future overrides.
     */
    public static boolean isAoDefaultApplied() {
        if (config == null) return true; // safety: don't touch settings without config
        return config.getBoolean(CFG_KEY_AO, CFG_CATEGORY, false,
            "Set to true after the mod has applied its one-time smooth lighting "
            + "default (Minimum). Once true, the user's own setting is never "
            + "overridden again.");
    }

    /**
     * Marks the one-time AO default as applied and saves the config to disk.
     * Called by BetaGraphicsEventHandler immediately after setting ambientOcclusion=1.
     */
    public static void markAoDefaultApplied() {
        if (config == null) return;
        config.get(CFG_CATEGORY, CFG_KEY_AO, false).set(true);
        config.save();
        System.out.println("[BetaGraphics] Smooth lighting default applied (AO=1). "
            + "Future changes by the player will be respected.");
    }

    // ── FML events ────────────────────────────────────────────────────────────

    /**
     * Pre-init: load the configuration file before any tick handler can run.
     * This guarantees isAoDefaultApplied() is ready when the first client tick fires.
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;

        File cfgFile = new File(event.getModConfigurationDirectory(), "betagraphics.cfg");
        config = new Configuration(cfgFile);
        config.load();

        // Eagerly read the flag so the backing Property object is initialised and
        // the config file is written to disk on first run (with the comment block).
        boolean alreadyApplied = isAoDefaultApplied();
        if (config.hasChanged()) config.save();

        System.out.println("[BetaGraphics] Config loaded. aoDefaultApplied=" + alreadyApplied);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;

        MinecraftForge.EVENT_BUS.register(new BetaGraphicsEventHandler());

        System.out.println("[BetaGraphics] Registered event handler.");
    }
}
