package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaSkyHelper;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin targeting World to restore Beta 1.7.3b's sky colour formula.
 *
 * World.getSkyColor is called from two independent render-side methods:
 *   1. EntityRenderer.updateFogColor — sets the GL clear colour (fog colour) each frame.
 *   2. RenderGlobal.renderSky — colours the sky dome vertices.
 *
 * Patching getSkyColor at the World level fixes both paths simultaneously.
 * Patching only RenderGlobal would leave updateFogColor using 1.12.2's
 * ColorizerSky formula, creating a colour mismatch between fog and sky dome.
 *
 * What 1.12.2 getSkyColor does that Beta did not:
 *   - ColorizerSky.getSkyColor(temp): reads a precomputed 256×256 colormap
 *     instead of Beta's direct HSB calculation.
 *   - Fog-blend: multiplies sky colour by (1 - fogFactor) and adds fog colour
 *     * fogFactor. Beta returned the pure biome sky colour; the seamless horizon
 *     transition came entirely from GL_LINEAR fog over the sky dome.
 *
 * SRG mapping:
 *   MCP: getSkyColor(Entity, float) → Vec3d
 *   SRG: func_72833_a(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/util/math/Vec3d;
 *
 * The method is declared with its SRG name and remap=false because no refmap is
 * loaded at runtime. Using the MCP name "getSkyColor" with remap=true causes a
 * build-time obfuscation-mapping lookup that cannot resolve to "func_72833_a".
 */
@Mixin(World.class)
public abstract class MixinWorld {

    /**
     * Full replacement of World.getSkyColor (SRG: func_72833_a) with Beta 1.7.3b's formula.
     * Delegates to {@link BetaSkyHelper#getBetaSkyColor}.
     *
     * @reason Replaces 1.12.2's ColorizerSky colormap lookup and fog-blend with
     *         Beta 1.7.3b's direct HSB sky colour formula.
     * @author michaelsebero
     */
    @Overwrite(remap = false)
    public Vec3d func_72833_a(Entity entityIn, float partialTicks) {
        return BetaSkyHelper.getBetaSkyColor((World) (Object) this, entityIn, partialTicks);
    }
}
