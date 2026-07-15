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
 * --- FIX: wrong name/remap combination ---
 * This was previously declared with the literal SRG name and remap=false, on
 * the assumption that no refmap is generated for this build and the runtime
 * target is SRG-obfuscated. Neither holds: the toolchain (RetroFuturaGradle)
 * writes a refmap on every compile, and the Mixin annotation processor
 * resolves @Overwrite targets against the MCP-named compilePatchedMcJava
 * output -- confirmed directly by a build where func_72833_a produced
 * "Cannot find target for @Overwrite method in net.minecraft.world.World"
 * while methods targeted by their MCP name in sibling Mixins (e.g.
 * EntityRenderer.setupFog) resolved without complaint. Using the MCP name
 * with the default remap=true lets the annotation processor both find the
 * target now and populate the refmap correctly for whatever obfuscation
 * state the mixin is actually applied against later.
 */
@Mixin(World.class)
public abstract class MixinWorld {

    /**
     * Full replacement of World.getSkyColor with Beta 1.7.3b's formula.
     * Delegates to {@link BetaSkyHelper#getBetaSkyColor}.
     *
     * @reason Replaces 1.12.2's ColorizerSky colormap lookup and fog-blend with
     *         Beta 1.7.3b's direct HSB sky colour formula.
     * @author michaelsebero
     */
    @Overwrite
    public Vec3d getSkyColor(Entity entityIn, float partialTicks) {
        return BetaSkyHelper.getBetaSkyColor((World) (Object) this, entityIn, partialTicks);
    }
}
