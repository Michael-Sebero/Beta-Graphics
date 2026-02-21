package com.michaelsebero.betagraphics;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

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
 *  4. Ambient occlusion lock
 *     ambientOcclusion is locked to 1 (smooth lighting, minimal). The geometry-
 *     aware darkening factor is neutralised by MixinAmbientOcclusionFace, which
 *     resets vertexColorMultiplier to 1.0f after each AO calculation.
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

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;

        MinecraftForge.EVENT_BUS.register(new BetaGraphicsEventHandler());

        System.out.println("[BetaGraphics] Registered event handler.");
    }
}
