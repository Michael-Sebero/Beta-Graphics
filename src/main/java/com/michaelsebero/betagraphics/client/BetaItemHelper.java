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
 * Renders flat items as 2D cards, matching Beta 1.7.3b's ItemRenderer.renderItem.
 *
 * Beta's 2D item path drew a unit-square card (X: 0→1, Y: 0→1, Z: 0→-THICKNESS):
 *   1. Front face  — one quad at Z=0, natural UV (minU left, maxU right).
 *   2. Back face   — one quad at Z=-THICKNESS, mirrored UV, reversed winding.
 *   3–4. Left/right edge — 16 thin vertical quads each.
 *   5–6. Top/bottom edge — 16 thin horizontal quads each.
 *
 * Only called for non-ItemBlock items. Block items are rendered via vanilla
 * renderItem(GROUND) in MixinRenderEntityItem.renderVanillaBlockItem().
 */
public final class BetaItemHelper {

    /** Card depth — 1/16 of the card's world-space size, matching Beta. */
    private static final float THICKNESS = 1.0F / 16.0F;

    /**
     * Bleed-prevention UV offset for edge slice sampling (half-texel in a 256-wide atlas).
     */
    private static final float HALF_TEXEL = 0.001953125F;

    private BetaItemHelper() {}

    /**
     * Renders {@code stack} as a flat 2D card at the current GL origin.
     *
     * Card occupies local X: 0→1, Y: 0→1, Z: 0→-THICKNESS.
     * Caller should translate(-0.5, -0.5, THICKNESS/2) first to centre it.
     *
     * @param stack      ItemStack to render. Must not be empty.
     * @param brightness Uniform light value (0.0–1.0).
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

        // ── Front face (Z = 0) ───────────────────────────────────────────────
        // Natural UV: minU on the left, maxU on the right. Normal +Z faces viewer.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(0.0, 0.0, 0.0).tex(minU, maxV).normal(0, 0, 1).endVertex();
        buf.pos(1.0, 0.0, 0.0).tex(maxU, maxV).normal(0, 0, 1).endVertex();
        buf.pos(1.0, 1.0, 0.0).tex(maxU, minV).normal(0, 0, 1).endVertex();
        buf.pos(0.0, 1.0, 0.0).tex(minU, minV).normal(0, 0, 1).endVertex();
        tess.draw();

        // ── Back face (Z = -THICKNESS) ───────────────────────────────────────
        // Horizontally mirrored UV so it reads correctly from behind.
        // Reversed winding makes the face point -Z.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(0.0, 1.0, -THICKNESS).tex(maxU, minV).normal(0, 0, -1).endVertex();
        buf.pos(1.0, 1.0, -THICKNESS).tex(minU, minV).normal(0, 0, -1).endVertex();
        buf.pos(1.0, 0.0, -THICKNESS).tex(minU, maxV).normal(0, 0, -1).endVertex();
        buf.pos(0.0, 0.0, -THICKNESS).tex(maxU, maxV).normal(0, 0, -1).endVertex();
        tess.draw();

        // ── Left-facing edge slices (normal -1, 0, 0) ───────────────────────
        // Pixel column i is at X = i/16. U maps left-to-right: minU + (maxU-minU)*t.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float u  = minU + (maxU - minU) * t + HALF_TEXEL;
            float px = t;
            buf.pos(px, 0.0, -THICKNESS).tex(u, maxV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 0.0,  0.0      ).tex(u, maxV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 1.0,  0.0      ).tex(u, minV).normal(-1, 0, 0).endVertex();
            buf.pos(px, 1.0, -THICKNESS).tex(u, minV).normal(-1, 0, 0).endVertex();
        }
        tess.draw();

        // ── Right-facing edge slices (normal +1, 0, 0) ──────────────────────
        // Right face of pixel column i is at X = (i+1)/16 = t + THICKNESS.
        // Same U formula as left edge — both sample the same column centre.
        // Winding is CCW from the +X side.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float u  = minU + (maxU - minU) * t + HALF_TEXEL;
            float px = t + THICKNESS;
            buf.pos(px, 0.0, -THICKNESS).tex(u, maxV).normal(1, 0, 0).endVertex();
            buf.pos(px, 1.0, -THICKNESS).tex(u, minV).normal(1, 0, 0).endVertex();
            buf.pos(px, 1.0,  0.0      ).tex(u, minV).normal(1, 0, 0).endVertex();
            buf.pos(px, 0.0,  0.0      ).tex(u, maxV).normal(1, 0, 0).endVertex();
        }
        tess.draw();

        // ── Top-facing edge slices (normal 0, +1, 0) ────────────────────────
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float v  = maxV + (minV - maxV) * t - HALF_TEXEL;
            float py = t + THICKNESS;
            buf.pos(0.0, py,  0.0      ).tex(minU, v).normal(0, 1, 0).endVertex();
            buf.pos(1.0, py,  0.0      ).tex(maxU, v).normal(0, 1, 0).endVertex();
            buf.pos(1.0, py, -THICKNESS).tex(maxU, v).normal(0, 1, 0).endVertex();
            buf.pos(0.0, py, -THICKNESS).tex(minU, v).normal(0, 1, 0).endVertex();
        }
        tess.draw();

        // ── Bottom-facing edge slices (normal 0, -1, 0) ─────────────────────
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (int i = 0; i < 16; i++) {
            float t  = i / 16.0F;
            float v  = maxV + (minV - maxV) * t - HALF_TEXEL;
            float py = t;
            buf.pos(1.0, py,  0.0      ).tex(maxU, v).normal(0, -1, 0).endVertex();
            buf.pos(0.0, py,  0.0      ).tex(minU, v).normal(0, -1, 0).endVertex();
            buf.pos(0.0, py, -THICKNESS).tex(minU, v).normal(0, -1, 0).endVertex();
            buf.pos(1.0, py, -THICKNESS).tex(maxU, v).normal(0, -1, 0).endVertex();
        }
        tess.draw();

        // Restore GL state.
        GlStateManager.disableBlend();
        GlStateManager.disableRescaleNormal();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableAlpha();
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
