package com.michaelsebero.betagraphics.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.BitSet;

/**
 * Layer-3 AO suppression — resets vertexColorMultiplier to 1.0f after every
 * updateVertexBrightness call, neutralising per-vertex corner darkening.
 *
 * Three layers cooperate to suppress AO for Beta-accurate rendering:
 *
 *   Layer 1 — MixinBlockLeaves.getAmbientOcclusionLightValue = 1.0F:
 *     Prevents leaves from contributing AO darkening weight to adjacent corners.
 *
 *   Layer 2 — BetaLeafModel.isAmbientOcclusion() = false:
 *     Routes leaf models to renderModelFlat(), bypassing per-vertex AO entirely.
 *
 *   Layer 3 — this class:
 *     For any block that still reaches updateVertexBrightness (e.g. a modded
 *     block that overrides getAmbientOcclusionLightValue with a value above the
 *     Layer 1 threshold, or a leaf model that bypassed the ModelBakeEvent wrap),
 *     vertexColorMultiplier is reset to 1.0F on all four vertices unconditionally.
 *
 * --- FIX: SRG method name ---
 * The original code used method = "updateVertexBrightness" with remap=false.
 * remap=false means Mixin uses the provided string as the literal runtime symbol
 * without going through the refmap. In the compiled game, this method is in SRG
 * form: func_178203_a. Using the MCP name with remap=false caused the inject to
 * silently never apply — the Mixin framework finds no matching method and skips
 * the injection without error. This was the only injection in the mod that used
 * an MCP name with remap=false; all others correctly use SRG names.
 *
 * --- FIX: vertexColorMultiplier field resolution ---
 * AmbientOcclusionFace declares two float[] fields (vertexBrightness then
 * vertexColorMultiplier, in that order). The original code wrote every matching
 * field in the loop, keeping the last one found. While this happened to produce
 * the correct field (vertexColorMultiplier, declared second), it was fragile.
 *
 * The fix resolves the field by its SRG name "field_178201_c" first. If that
 * fails (unusual build or mapping variant), it falls back to a counted type scan:
 * skipping the first float[] (vertexBrightness / field_178200_b) and taking the
 * second (vertexColorMultiplier / field_178201_c). Both strategies are more
 * explicit than relying on loop-last-wins behaviour.
 */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public abstract class MixinAmbientOcclusionFace {

    // SRG name for AmbientOcclusionFace.vertexColorMultiplier in 1.12.2.
    private static final String VCM_SRG_NAME = "field_178201_c";

    private static volatile Field   COLOR_MULTIPLIER_FIELD = null;
    private static volatile boolean fieldsResolved         = false;

    /**
     * Resolves vertexColorMultiplier exactly once.
     *
     * Strategy:
     *   1. Try SRG name "field_178201_c" directly (fast path, exact).
     *   2. Fall back to index-1 type scan (second float[] in declaration order).
     *      AmbientOcclusionFace declares: float[] vertexBrightness (index 0),
     *      float[] vertexColorMultiplier (index 1). Both strategies are equivalent
     *      for unmodified 1.12.2 but the name lookup is preferred because it
     *      survives if a subclass adds float[] fields before vertexColorMultiplier.
     */
    private static void resolveFields(Object instance) {
        if (fieldsResolved) return;
        synchronized (MixinAmbientOcclusionFace.class) {
            if (fieldsResolved) return; // double-check after lock

            Field found = null;

            // Pass 1: exact SRG name.
            try {
                Field f = instance.getClass().getDeclaredField(VCM_SRG_NAME);
                if (f.getType() == float[].class) {
                    f.setAccessible(true);
                    found = f;
                    System.out.println("[BetaGraphics] AO: resolved vertexColorMultiplier "
                        + "by SRG name '" + VCM_SRG_NAME + "'");
                }
            } catch (NoSuchFieldException ignored) { }

            // Pass 2: index-based type scan (take second float[] found).
            if (found == null) {
                int floatArrayCount = 0;
                for (Field f : instance.getClass().getDeclaredFields()) {
                    if (f.getType() != float[].class) continue;
                    f.setAccessible(true);
                    floatArrayCount++;
                    if (floatArrayCount == 2) {    // second float[] == vertexColorMultiplier
                        found = f;
                        System.out.println("[BetaGraphics] AO: resolved vertexColorMultiplier "
                            + "by type-scan index-1, field name '" + f.getName() + "'");
                        break;
                    }
                }
            }

            if (found == null) {
                System.err.println("[BetaGraphics] AO: WARN — could not resolve "
                    + "vertexColorMultiplier; AO suppression (Layer 3) inactive.");
            }

            COLOR_MULTIPLIER_FIELD = found;
            fieldsResolved = true;
        }
    }

    /**
     * Fires after every updateVertexBrightness call.
     *
     * FIX: method uses SRG name func_178203_a with remap=false.
     * The original used the MCP name "updateVertexBrightness" with remap=false,
     * which caused the injection to silently never apply at runtime because the
     * compiled game uses SRG names, not MCP names.
     */
    @Inject(
        method = "func_178203_a",   // SRG for updateVertexBrightness — remap=false required
        at = @At("RETURN"),
        remap = false
    )
    private void betaNeutraliseAO(IBlockAccess world, IBlockState state, BlockPos pos,
            EnumFacing facing, float[] weights, BitSet shapeState, CallbackInfo ci) {
        resolveFields(this);
        if (COLOR_MULTIPLIER_FIELD == null) return;
        try {
            float[] vcm = (float[]) COLOR_MULTIPLIER_FIELD.get(this);
            if (vcm != null) {
                vcm[0] = 1.0F;
                vcm[1] = 1.0F;
                vcm[2] = 1.0F;
                vcm[3] = 1.0F;
            }
        } catch (IllegalAccessException ignored) { }
    }
}
