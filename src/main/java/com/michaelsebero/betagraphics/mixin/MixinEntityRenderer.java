package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaFogHelper;
import com.michaelsebero.betagraphics.client.BetaLightmapHelper;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin targeting EntityRenderer for three patches:
 *
 * Patch 1: updateLightmap — @Inject at RETURN (SRG: func_78472_g)
 *   Vanilla updateLightmap runs completely first (including its own
 *   updateDynamicTexture call), then our inject overwrites all 256 lightmap
 *   pixels with Beta 1.7.3b's neutral-white max(sky,block) values and calls
 *   updateDynamicTexture() again. The final GL upload entering each frame is
 *   always ours.
 *
 * Patch 2: updateFogColor — @Inject at RETURN (SRG: func_78466_h)
 *   Replicates Beta's fogColor1/fogColor2 ambient-darkening system by
 *   multiplying the GL clear colour by betaFogDarken each frame.
 *
 * Patch 3: setupFog — @Overwrite
 *   Full replacement with Beta 1.7.3b's fog model (water/lava/linear/sky/Nether).
 *   setupFog is unobfuscated in 1.12.2 so remap=true (default) is correct.
 *
 * Naming strategy for @Inject targets:
 *   SRG names are used directly with remap=false. This bypasses refmap lookup
 *   entirely, which is required here because no refmap is loaded at runtime.
 *   remap=true causes the Mixin AP to search the MCP→SRG obfuscation table at
 *   build time, which fails for these methods; remap=false skips that lookup
 *   and uses the provided name against obfuscated bytecode at runtime directly.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    /**
     * Fires before each RETURN in updateLightmap (SRG: func_78472_g).
     * Overwrites vanilla's gamma-lifted lightmap with Beta 1.7.3b values.
     */
    @Inject(method = "func_78472_g", at = @At("RETURN"), remap = false)
    private void betaUpdateLightmap(float partialTicks, CallbackInfo ci) {
        BetaLightmapHelper.generateBetaLightmap();
    }

    /**
     * Fires before each RETURN in updateFogColor (SRG: func_78466_h).
     * Multiplies the GL clear colour by Beta's ambient factor, restoring
     * the fogColor1/fogColor2 atmospheric darkening that 1.12.2 removed.
     */
    @Inject(method = "func_78466_h", at = @At("RETURN"), remap = false)
    private void betaApplyAmbientDarken(float partialTicks, CallbackInfo ci) {
        BetaFogHelper.applyAmbientDarken((EntityRenderer) (Object) this, partialTicks);
    }

    /**
     * Full replacement for EntityRenderer.setupFog.
     * setupFog is unobfuscated in 1.12.2 — remap=true (default) is correct.
     *
     * @reason Restores Beta 1.7.3b's complete GL fog model.
     * @author michaelsebero
     */
    @Overwrite
    public void setupFog(int startCoords, float partialTicks) {
        BetaFogHelper.setupBetaFog((EntityRenderer) (Object) this, startCoords, partialTicks);
    }
}
