package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaLeavesHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Restores Beta 1.7.3b leaf rendering behaviour.
 *
 * Targets BlockLeaves directly; BlockNewLeaf (acacia/dark oak) extends it,
 * so all overrides apply to every leaf type automatically.
 *
 * Three cooperating layers produce Beta-accurate flat leaf rendering:
 *   Layer 1 — getAmbientOcclusionLightValue = 1.0F (this class):
 *              No AO darkening weight on adjacent block corners.
 *   Layer 2 — BetaLeafModel.isAmbientOcclusion() = false:
 *              Routes to renderModelFlat(); no per-vertex AO blending.
 *   Layer 3 — BetaLeafModel.getQuads() with applyDiffuseLighting = false:
 *              No directional face multipliers (0.5/0.8/0.6/1.0).
 *
 * getPackedLightmapCoords is intentionally NOT overridden. Forcing sky=15
 * block=15 unconditionally made leaves luminescent at night, ignoring actual
 * world light. Only AO and face shading are suppressed, not lightmap sampling.
 */
@Mixin(BlockLeaves.class)
public abstract class MixinBlockLeaves extends Block {

    /** Required because this mixin declares extends Block. Never called at runtime. */
    private MixinBlockLeaves(Material material) {
        super(material);
    }

    /**
     * @reason Restores Beta 1.7.3b leaf render layer:
     *         Fast → SOLID, Fancy → CUTOUT (never CUTOUT_MIPPED).
     * @author michaelsebero
     */
    @Overwrite
    public BlockRenderLayer getBlockLayer() {
        return BetaLeavesHelper.getLeafRenderLayer();
    }

    /**
     * @reason Restores Beta BlockLeaves.isOpaqueCube() = !this.graphicsLevel:
     *         Fast → true (solid, internal faces culled),
     *         Fancy → false (transparent, all faces kept).
     * @author michaelsebero
     */
    @Overwrite
    public boolean isOpaqueCube(IBlockState state) {
        return BetaLeavesHelper.isLeafOpaque();
    }

    /**
     * Returns 1.0F unconditionally. Prevents leaves from contributing AO
     * darkening weight to adjacent solid block corners (Layer 1).
     *
     * @Override omitted intentionally — getAmbientOcclusionLightValue is
     * declared on IForgeBlock, not directly on Block, so the Java compiler
     * cannot resolve @Override at compile time. Mixin merges the method
     * via class hierarchy resolution at apply time.
     */
    public float getAmbientOcclusionLightValue() {
        return 1.0F;
    }
}
