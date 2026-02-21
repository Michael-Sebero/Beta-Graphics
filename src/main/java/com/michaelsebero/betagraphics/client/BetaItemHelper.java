package com.michaelsebero.betagraphics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Renders items as flat 2D cards, matching Beta 1.7.3b's ItemRenderer.renderItem.
 *
 * Beta's 2D item path drew a unit-square card (X: 0→1, Y: 0→1, Z: 0→-THICKNESS):
 *   1. Front face  — one quad at Z=0, UV horizontally mirrored.
 *   2. Back face   — one quad at Z=-THICKNESS, reversed winding.
 *   3–4. Left/right edge — 16 thin vertical quads each.
 *   5–6. Top/bottom edge — 16 thin horizontal quads each.
 *
 * Sprite resolution uses a three-tier fallback to handle models that return null
 * from getParticleTexture() (e.g. OBJ/B3D-backed models):
 *   Tier 1: model.getParticleTexture()
 *   Tier 2: first quad sprite from model.getQuads(null, null, 0L)
 *   Tier 3: TextureMap.getMissingSprite() (always visible; intentionally ugly)
 *
 * Called from MixinRenderEntityItem after the billboard matrix is applied.
 */
public final class BetaItemHelper {

    /** Card depth — 1/16 of the card's world-space size, matching Beta. */
    private static final float THICKNESS = 1.0F / 16.0F;

    /**
     * Bleed-prevention UV offset for edge slice sampling.
     * Beta used 1/512, one half-texel in a 256-wide atlas.
     */
    private static final float HALF_TEXEL = 0.001953125F;

    private BetaItemHelper() {}

    /**
     * Renders {@code stack} as a flat 2D card at the current GL origin.
     *
     * Card occupies local X: 0→1, Y: 0→1, Z: 0→-THICKNESS.
     * The caller should translate(-0.5, -0.5, THICKNESS/2) first to centre it.
     *
     * @param stack      ItemStack to render. Must not be empty.
     * @param brightness Uniform light value (0.0–1.0) applied as glColor4f(b,b,b,1).
     */
    public static void renderBetaItem2D(ItemStack stack, float brightness) {
        if (stack.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return;

        TextureAtlasSprite sprite = resolveSprite(mc, stack);
        if (sprite == null) return;

        float minU = sprite.getMinU();
        float maxU = sprite.getMaxU();
        float minV = sprite.getMinV();
        float maxV = sprite.getMaxV();

        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        GlStateManager.color(brightness, brightness, brightness, 1.0F);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableRescaleNormal();

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // Front face (Z=0) — UV horizontally mirrored: maxU on left, minU on right.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(0.0, 0.0, 0.0).tex(maxU, maxV).normal(0, 0, 1).endVertex();
        buf.pos(1.0, 0.0, 0.0).tex(minU, maxV).normal(0, 0, 1).endVertex();
        buf.pos(1.0, 1.0, 0.0).tex(minU, minV).normal(0, 0, 1).endVertex();
        buf.pos(0.0, 1.0, 0.0).tex(maxU, minV).normal(0, 0, 1).endVertex();
        tess.draw();

        // Back face (Z=-THICKNESS) — same UV, reversed winding.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(0.0, 1.0, -THICKNESS).tex(maxU, minV).normal(0, 0, -1).endVertex();
        buf.pos(1.0, 1.0, -THICKNESS).tex(minU, minV).normal(0, 0, -1).endVertex();
        buf.pos(1.0, 0.0, -THICKNESS).tex(minU, maxV).normal(0, 0, -1).endVertex();
        buf.pos(0.0, 0.0, -THICKNESS).tex(maxU, maxV).normal(0, 0, -1).endVertex();
        tess.draw();

        // Left-facing edge slices (normal -1,0,0).
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float u  = maxU + (minU - maxU) * t - HALF_TEXEL;
            float px = t;
            buf.pos(px, 0.0, -THICKNESS).tex(u, maxV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 0.0,  0.0      ).tex(u, maxV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 1.0,  0.0      ).tex(u, minV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 1.0, -THICKNESS).tex(u, minV).normal(-1, 0, 0).endVertex();
        }
        tess.draw();

        // Right-facing edge slices (normal +1,0,0).
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float u  = maxU + (minU - maxU) * t - HALF_TEXEL;
            float px = t + THICKNESS;
            buf.pos(px, 1.0, -THICKNESS).tex(u, minV).normal(1, 0, 0).endVertex();
            buf.pos(px, 1.0,  0.0      ).tex(u, minV).normal(1, 0, 0).endVertex();
            buf.pos(px, 0.0,  0.0      ).tex(u, maxV).normal(1, 0, 0).endVertex();
            buf.pos(px, 0.0, -THICKNESS).tex(u, maxV).normal(1, 0, 0).endVertex();
        }
        tess.draw();

        // Top-facing edge slices (normal 0,+1,0).
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float v  = maxV + (minV - maxV) * t - HALF_TEXEL;
            float py = t + THICKNESS;
            buf.pos(0.0, py,  0.0      ).tex(maxU, v).normal(0, 1, 0).endVertex();
            buf.pos(1.0, py,  0.0      ).tex(minU, v).normal(0, 1, 0).endVertex();
            buf.pos(1.0, py, -THICKNESS).tex(minU, v).normal(0, 1, 0).endVertex();
            buf.pos(0.0, py, -THICKNESS).tex(maxU, v).normal(0, 1, 0).endVertex();
        }
        tess.draw();

        // Bottom-facing edge slices (normal 0,-1,0).
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float v  = maxV + (minV - maxV) * t - HALF_TEXEL;
            float py = t;
            buf.pos(1.0, py,  0.0      ).tex(minU, v).normal(0, -1, 0).endVertex();
            buf.pos(0.0, py,  0.0      ).tex(maxU, v).normal(0, -1, 0).endVertex();
            buf.pos(0.0, py, -THICKNESS).tex(maxU, v).normal(0, -1, 0).endVertex();
            buf.pos(1.0, py, -THICKNESS).tex(minU, v).normal(0, -1, 0).endVertex();
        }
        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Returns Beta-style light brightness at the given world position.
     */
    public static float getBrightnessAt(double worldX, double worldY, double worldZ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null) return 1.0F;
        BlockPos pos = new BlockPos(
            MathHelper.floor(worldX),
            MathHelper.floor(worldY),
            MathHelper.floor(worldZ));
        return MathHelper.clamp(mc.world.getLightBrightness(pos), 0.0F, 1.0F);
    }

    // ── Sprite resolution ─────────────────────────────────────────────────────

    private static TextureAtlasSprite resolveSprite(Minecraft mc, ItemStack stack) {
        IBakedModel model;
        try {
            model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
        } catch (Exception e) {
            return null;
        }
        if (model == null) return null;

        try {
            TextureAtlasSprite s = model.getParticleTexture();
            if (s != null) return s;
        } catch (Exception ignored) {}

        try {
            List<BakedQuad> quads = model.getQuads(null, null, 0L);
            if (quads != null && !quads.isEmpty()) {
                TextureAtlasSprite s = quads.get(0).getSprite();
                if (s != null) return s;
            }
        } catch (Exception ignored) {}

        try {
            TextureMap atlas = mc.getTextureMapBlocks();
            if (atlas != null) return atlas.getMissingSprite();
        } catch (Exception ignored) {}

        return null;
    }
}
