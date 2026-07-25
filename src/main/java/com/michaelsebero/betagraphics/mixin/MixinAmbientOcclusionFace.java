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
 * --- FIX: method target used remap=false + a literal SRG name ---
 * This was the only injection in the whole mod still resolving its target this
 * way. Every other Mixin here went through the same two-step history — MCP
 * name + remap=false silently matching nothing, "fixed" at the time by
 * switching to the SRG name func_178203_a while keeping remap=false — before
 * landing on what this toolchain actually needs: the plain MCP name with the
 * default remap=true, so the Mixin AP populates mixins.betagraphics.refmap.json
 * and the Mixin FML Remapper Adapter resolves the live target at
 * class-transform time (see MixinEntityRenderer, MixinWorld,
 * MixinRenderEntityItem, MixinRender, MixinColorizerFoliage, and
 * MixinParticleRain for the identical fix, already confirmed working). This
 * one mixin never got that second half of the fix — and it turns out to be
 * fatal specifically with OptiFine installed: remap=false takes
 * "func_178203_a" as a literal runtime name, i.e. whatever bytecode happens to
 * carry that name once every earlier-running transformer — including
 * OptiFine's own ClassTransformer, which substantially rewrites
 * AmbientOcclusionFace's brightness calculation for its own smooth-lighting
 * feature — has had its turn on the class. OptiFine's own
 * net.optifine.render.RenderEnv is what first forces AmbientOcclusionFace to
 * load, from inside RenderGlobal's constructor at startup, and what Mixin
 * finds under that literal name at that point takes four ints and four floats
 * and returns a value — not the vanilla updateVertexBrightness(IBlockAccess,
 * IBlockState, BlockPos, EnumFacing, float[], BitSet) signature
 * betaNeutraliseAO was written against. Mixin correctly refuses to inject into
 * a method whose parameters don't match; since this injection is required
 * rather than optional, that refusal escalates to a MixinTransformerError,
 * AmbientOcclusionFace never finishes loading, and the game crashes at startup
 * — every time, as soon as OptiFine is present (confirmed via a 14-mod
 * OptiFine + Beta Graphics repro: NoClassDefFoundError on AmbientOcclusionFace,
 * thrown from net.optifine.render.RenderEnv.<init>, root cause
 * InvalidInjectionException: Invalid descriptor).
 *
 * Fix: target the plain MCP name and let remap default to true, matching
 * every sibling mixin in this project.
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
     * FIX: target the plain MCP name with the default remap=true — see the
     * class-level "FIX: method target used remap=false + a literal SRG name"
     * note above. This is the same pattern already used by every other
     * @Inject/@Overwrite in this project, and the one this mixin was missing.
     */
    @Inject(
        method = "updateVertexBrightness",
        at = @At("RETURN")
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
