package com.michaelsebero.betagraphics.client;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.awt.Color;

/**
 * Implements Beta 1.7.3b's sky colour system.
 *
 * Beta's two-step sky colour pipeline:
 *
 * Step 1 — BiomeGenBase.getSkyColorByTemp(float temp):
 *   temp  = clamp(temp / 3.0F, -1.0F, 1.0F)
 *   return Color.getHSBColor(
 *               0.6222222F - temp * 0.05F,   // hue (≈224° for temperate biomes)
 *               0.5F       + temp * 0.1F,    // saturation
 *               1.0F                         // value
 *          ).getRGB();
 *
 *   1.12.2 replaced this with ColorizerSky.getSkyColor(temp), which reads a
 *   precomputed 256×256 colormap texture. The HSB formula produces a purer,
 *   brighter sky blue than the colormap lookup.
 *
 * Step 2 — WorldProvider.func_4096_a(float celestialAngle, float partialTicks):
 *   brightness = clamp(cos(celestialAngle * PI * 2) * 2 + 0.5, 0, 1)
 *   r *= brightness * 0.94F + 0.06F
 *   g *= brightness * 0.94F + 0.06F
 *   b *= brightness * 0.91F + 0.09F   // blue fades slightly less
 *
 *   Note: blue uses 0.91/0.09 while red/green use 0.94/0.06, keeping the
 *   night sky faintly blue rather than pure grey.
 *
 * 1.12.2's World.getSkyColor additionally blends the result with the fog colour.
 *
 * CORRECTION (previously claimed no such blend exists in Beta -- confirmed wrong):
 * Beta's EntityRenderer.updateFogColor calls a separate World.getFogColor(partialTicks)
 * -- not this method -- and blends it toward this sky colour using a render-distance
 * weighted factor (1 - (1/(4-renderDistance))^0.25). So Beta really does have two
 * distinct colours (sky vs. fog) blended together; this file only produces the sky
 * half. getFogColor's actual formula hasn't turned up in any source provided yet, so
 * the fog side of that blend is still just the ambient-darkened version of this sky
 * colour (via BetaFogHelper reading GL_COLOR_CLEAR_VALUE), not a true implementation
 * of Beta's blend. Left as-is pending a source that shows World.getFogColor.
 *
 * Moon phases:
 *   Beta had no moon phase system. getBetaMoonPhase() always returns 0, selecting
 *   the full-moon tile (u0=0, v0=0) in the 1.12.2 4×2 sprite sheet.
 *
 * --- FIX: hasSkyLight() guard ---
 * MixinWorld replaces World.getSkyColor (func_72833_a) for ALL worlds, including
 * the Nether and End. Neither dimension has a sky, but updateFogColor still calls
 * getSkyColor to determine the GL clear colour and fog colour. Without a guard,
 * the overworld blue-sky HSB formula was applied to Nether/End fog, producing a
 * blue atmospheric tint in those dimensions.
 *
 * Fix: when the world has no sky light (Nether, custom void dimensions), return
 * Vec3d.ZERO immediately. The Nether's orange fog is computed separately by
 * vanilla's updateFogColor via the fogColorRed/Green/Blue WorldProvider fields,
 * which are unaffected by our getSkyColor override.
 */
public final class BetaSkyHelper {

    private BetaSkyHelper() {}

    /**
     * Returns Beta 1.7.3b's sky colour for the given world, viewer, and frame.
     *
     * Called from MixinWorld.func_72833_a to replace 1.12.2's ColorizerSky-based
     * calculation. Overriding getSkyColor at the World level fixes both
     * EntityRenderer.updateFogColor (fog colour) and RenderGlobal.renderSky
     * (sky dome vertex colours) with a single override.
     *
     * @param world        The client world.
     * @param entity       The render view entity.
     * @param partialTicks Frame interpolation factor.
     * @return             Sky colour as Vec3d(r, g, b), all in [0, 1].
     */
    public static Vec3d getBetaSkyColor(World world, Entity entity, float partialTicks) {

        // FIX: Guard for sky-less dimensions (Nether, End, custom void worlds).
        // The Nether's fog/sky colour is handled entirely by vanilla's
        // updateFogColor reading WorldProvider.fogColorRed/Green/Blue, which are
        // separate from getSkyColor. Returning ZERO here leaves those untouched.
        if (!world.provider.hasSkyLight()) {
            return Vec3d.ZERO;
        }

        // Step 1: Beta's HSB biome sky colour.
        BlockPos pos   = new BlockPos(entity);
        Biome    biome = world.getBiome(pos);
        float    temp  = MathHelper.clamp(biome.getDefaultTemperature() / 3.0F, -1.0F, 1.0F);

        int rgb = Color.HSBtoRGB(
            0.6222222F - temp * 0.05F,
            0.5F       + temp * 0.1F,
            1.0F
        );

        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >>  8) & 0xFF) / 255.0F;
        float b = ( rgb        & 0xFF) / 255.0F;

        // Step 2: Time-of-day brightness scaling (func_4096_a).
        // Applied here so both the sky dome and fog colour receive the same value.
        float celestialAngle = world.getCelestialAngle(partialTicks);
        float brightness = MathHelper.clamp(
            MathHelper.cos(celestialAngle * (float) Math.PI * 2.0F) * 2.0F + 0.5F,
            0.0F, 1.0F
        );
        r *= brightness * 0.94F + 0.06F;
        g *= brightness * 0.94F + 0.06F;
        b *= brightness * 0.91F + 0.09F;

        // Step 3: Rain / thunder darkening.
        // FIX: blue coefficient was 0.2F. Confirmed against Beta's actual
        // EntityRenderer.updateFogColor: red and green use 0.5F, but blue
        // specifically uses 0.4F, not 0.2F -- blue was being under-darkened
        // during rain, giving rainy skies too strong a blue tint.
        // KNOWN GAP (not fixed here): in real Beta this darkening happens in
        // updateFogColor, applied to the already sky/fog-blended colour, not
        // inside the sky-colour function itself -- see the class doc note
        // below on the missing getFogColor/sky-fog blend. The coefficient is
        // now correct; where this logic should ultimately live is still open.
        float rain = world.getRainStrength(partialTicks);
        if (rain > 0.0F) {
            r *= 1.0F - rain * 0.5F;
            g *= 1.0F - rain * 0.5F;
            b *= 1.0F - rain * 0.4F;
        }

        float thunder = world.getThunderStrength(partialTicks);
        if (thunder > 0.0F) {
            r *= 1.0F - thunder * 0.5F;
            g *= 1.0F - thunder * 0.5F;
            b *= 1.0F - thunder * 0.5F;
        }

        return new Vec3d(r, g, b);
    }
}
