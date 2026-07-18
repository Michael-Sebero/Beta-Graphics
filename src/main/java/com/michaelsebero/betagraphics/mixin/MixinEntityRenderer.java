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
 * Patch 1: updateLightmap — @Inject at RETURN
 *   Vanilla updateLightmap runs completely first (including its own
 *   updateDynamicTexture call), then our inject overwrites all 256 lightmap
 *   pixels with Beta 1.7.3b's neutral-white max(sky,block) values and calls
 *   updateDynamicTexture() again. The final GL upload entering each frame is
 *   always ours.
 *
 * Patch 2: updateFogColor — @Inject at RETURN
 *   Replicates Beta's fogColor1/fogColor2 ambient-darkening system by
 *   multiplying the GL clear colour by betaFogDarken each frame.
 *
 * Patch 3: setupFog — @Overwrite
 *   Full replacement with Beta 1.7.3b's fog model (water/lava/linear/sky/Nether).
 *
 * --- FIX: Patches 1 and 2 previously used SRG names with remap=false ---
 * on the assumption that no refmap is generated for this build and the
 * runtime target is SRG-obfuscated. Both were wrong: this toolchain
 * (RetroFuturaGradle 1.4.1) writes mixins.betagraphics.refmap.json on every
 * compile, and the Mixin AP resolves targets against the MCP-named
 * compilePatchedMcJava output, same as setupFog already correctly relied on
 * below. All three patches now use their plain MCP name with the default
 * remap=true.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    /**
     * Fires before each RETURN in updateLightmap.
     * Overwrites vanilla's gamma-lifted lightmap with Beta 1.7.3b values.
     */
    @Inject(method = "updateLightmap", at = @At("RETURN"))
    private void betaUpdateLightmap(float partialTicks, CallbackInfo ci) {
        BetaLightmapHelper.generateBetaLightmap();
    }

    /**
     * Fires before each RETURN in updateFogColor.
     * Multiplies the GL clear colour by Beta's ambient factor, restoring
     * the fogColor1/fogColor2 atmospheric darkening that 1.12.2 removed.
     */
    @Inject(method = "updateFogColor", at = @At("RETURN"))
    private void betaApplyAmbientDarken(float partialTicks, CallbackInfo ci) {
        BetaFogHelper.applyAmbientDarken((EntityRenderer) (Object) this, partialTicks);
    }

    /**
     * Full replacement for EntityRenderer.setupFog.
     * setupFog is unobfuscated in 1.12.2 — remap=true (default) is correct.
     *
     * FIX: was declared public, which the Mixin AP warned about ("PUBLIC
     * @Overwrite method will upgrade visibility of PRIVATE method") since
     * vanilla's setupFog is private. Narrowed back to private to match
     * exactly -- nothing outside EntityRenderer itself calls this, so there
     * was no functional reason for the wider visibility.
     *
     * @reason Restores Beta 1.7.3b's complete GL fog model.
     * @author michaelsebero
     */
    @Overwrite
    private void setupFog(int startCoords, float partialTicks) {
        BetaFogHelper.setupBetaFog((EntityRenderer) (Object) this, startCoords, partialTicks);
    }
}
