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
 * The vertexColorMultiplier field is located by type scan (first float[] on the
 * class) rather than by name, making the lookup immune to SRG/MCP mapping
 * differences. Fields are resolved once at first call and cached statically.
 */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public abstract class MixinAmbientOcclusionFace {

    private static Field COLOR_MULTIPLIER_FIELD = null;
    private static boolean fieldsResolved = false;

    private static void resolveFields(Object instance) {
        if (fieldsResolved) return;
        fieldsResolved = true;
        for (Field f : instance.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == float[].class) {
                COLOR_MULTIPLIER_FIELD = f;
                System.out.println("[BetaGraphics] AO: located float[] field '"
                        + f.getName() + "' (vertexColorMultiplier)");
            }
        }
    }

    @Inject(
        method = "updateVertexBrightness",
        at = @At("RETURN"),
        remap = false
    )
    private void betaNeutraliseAO(IBlockAccess world, IBlockState state, BlockPos pos,
            EnumFacing facing, float[] weights, BitSet shapeState, CallbackInfo ci) {
        try {
            resolveFields(this);

            if (COLOR_MULTIPLIER_FIELD != null) {
                float[] vcm = (float[]) COLOR_MULTIPLIER_FIELD.get(this);
                if (vcm != null) {
                    vcm[0] = 1.0F;
                    vcm[1] = 1.0F;
                    vcm[2] = 1.0F;
                    vcm[3] = 1.0F;
                }
            }
        } catch (IllegalAccessException e) {
            // ignore
        }
    }
}
