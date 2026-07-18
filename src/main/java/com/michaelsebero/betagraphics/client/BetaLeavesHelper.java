package com.michaelsebero.betagraphics.client;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.client.event.ModelBakeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Restores Beta 1.7.3b leaf transparency behaviour and lighting.
 *
 * Render layer:
 *   Fast  (fancyGraphics=false) → SOLID,  isOpaqueCube=true
 *   Fancy (fancyGraphics=true)  → CUTOUT, isOpaqueCube=false
 *   (CUTOUT_MIPPED is a 1.12.2 invention; Beta used raw alpha testing only.)
 *
 * Lighting model — three cooperating layers:
 *
 *   Layer 1 — getAmbientOcclusionLightValue = 1.0F (MixinBlockLeaves):
 *     Prevents leaves from contributing AO darkening weight to adjacent block corners.
 *
 *   Layer 2 — BetaLeafModel.isAmbientOcclusion() = false:
 *     Routes leaf models to renderModelFlat() instead of renderModelSmooth(),
 *     eliminating per-vertex AO brightness blending entirely.
 *
 *   Layer 3 — BetaLeafModel.getQuads() with applyDiffuseLighting = false:
 *     Prevents renderModelFlat() from applying the 0.5/0.8/0.6/1.0 directional
 *     face multipliers, making all leaf faces render at uniform brightness.
 *
 * Quad rewriting is performed once at model bake time, not per frame.
 * Called from BetaGraphicsEventHandler.onModelBake(ModelBakeEvent).
 */
public final class BetaLeavesHelper {

    private BetaLeavesHelper() {}

    public static BlockRenderLayer getLeafRenderLayer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.gameSettings.fancyGraphics) {
            return BlockRenderLayer.CUTOUT;
        }
        return BlockRenderLayer.SOLID;
    }

    public static boolean isLeafOpaque() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return false;
        return !mc.gameSettings.fancyGraphics;
    }

    /**
     * Wraps every leaf block model with {@link BetaLeafModel} so that
     * isAmbientOcclusion() returns false and all quads have applyDiffuseLighting=false.
     * Both changes are required — isAmbientOcclusion()=false alone routes to
     * renderModelFlat(), but renderModelFlat() still reads applyDiffuseLighting per quad.
     */
    public static void onModelBake(ModelBakeEvent event) {
        int wrapped = 0;

        for (ModelResourceLocation key : event.getModelRegistry().getKeys()) {
            if ("inventory".equals(key.getVariant())) continue;

            IBakedModel model = event.getModelRegistry().getObject(key);
            if (model == null || model instanceof BetaLeafModel) continue;
            if (!isLeafModel(key)) continue;

            try {
                event.getModelRegistry().putObject(key, new BetaLeafModel(model));
                wrapped++;
            } catch (Exception e) {
                // skip silently — vanilla model is better than a crash
            }
        }

        System.out.println("[BetaGraphics] BetaLeafModel: wrapped " + wrapped + " leaf models.");
        if (wrapped == 0) {
            System.err.println("[BetaGraphics] WARNING: 0 leaf models wrapped — "
                + "leaves will not render with Beta lighting.");
        }
    }

    private static boolean isLeafModel(ModelResourceLocation key) {
        try {
            Block block = Block.getBlockFromName(
                key.getResourceDomain() + ":" + key.getResourcePath());
            return block != null && block != Blocks.AIR && block instanceof BlockLeaves;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wraps a leaf IBakedModel to produce Beta-style flat, unshaded rendering.
     *
     * Changes from the wrapped model:
     *   isAmbientOcclusion() = false → BlockModelRenderer uses renderModelFlat().
     *   getQuads() rewrites each BakedQuad with applyDiffuseLighting = false,
     *   removing the 0.5/0.8/0.6/1.0 directional face multipliers.
     */
    public static final class BetaLeafModel implements IBakedModel {

        private final IBakedModel wrapped;

        public BetaLeafModel(IBakedModel wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            List<BakedQuad> original;
            try {
                original = wrapped.getQuads(state, side, rand);
            } catch (Exception e) {
                return Collections.emptyList();
            }

            if (original == null || original.isEmpty()) return original;

            List<BakedQuad> result = new ArrayList<>(original.size());
            for (BakedQuad q : original) {
                try {
                    result.add(new BakedQuad(
                        q.getVertexData(),
                        q.getTintIndex(),
                        q.getFace(),
                        q.getSprite(),
                        false,   // applyDiffuseLighting = false: no face darkening
                        q.getFormat()
                    ));
                } catch (Exception e) {
                    result.add(q);
                }
            }
            return result;
        }

        /** false → renderModelFlat(): uniform brightness per face, no AO vertex blending. */
        @Override public boolean isAmbientOcclusion()  { return false; }

        @Override public boolean isGui3d()              { return wrapped.isGui3d(); }
        @Override public boolean isBuiltInRenderer()    { return wrapped.isBuiltInRenderer(); }

        @Override
        public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleTexture() {
            return wrapped.getParticleTexture();
        }

        @Override
        public net.minecraft.client.renderer.block.model.ItemCameraTransforms getItemCameraTransforms() {
            return wrapped.getItemCameraTransforms();
        }

        @Override
        public net.minecraft.client.renderer.block.model.ItemOverrideList getOverrides() {
            return wrapped.getOverrides();
        }
    }
}
