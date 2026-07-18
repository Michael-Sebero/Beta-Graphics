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
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Renders flat items as 2D quads, matching Beta 1.7.3b's RenderItem.doRenderItem.
 *
 * Beta's 2D item path drew a single flat quad per copy with only a Y-axis
 * billboard rotation applied by the caller (180 - playerViewY). There is no
 * back face, no edge geometry, and no thickness whatsoever.
 *
 * The quad is centred using Beta's var21=0.5 and var22=0.25 offsets:
 *   X: -(var21) to +(var20-var21) = -0.5 to +0.5
 *   Y: -(var22) to +(var20-var22) = -0.25 to +0.75
 * Normal is set to (0, 1, 0) matching Beta's tessellator.setNormal(0, 1, 0).
 *
 * Only called for non-ItemBlock items. Block items are rendered by
 * renderBetaBlockItem() in MixinRenderEntityItem.
 */
public final class BetaItemHelper {

    private BetaItemHelper() {}

    /**
     * Renders one flat quad for {@code stack} at the current GL origin.
     *
     * The quad is pre-centred (X: -0.5 to +0.5, Y: -0.25 to +0.75) matching
     * Beta's var21/var22 offsets. The caller has already applied the Y-axis
     * billboard rotation and any per-copy XYZ jitter before calling this method.
     *
     * @param stack      ItemStack to render. Must not be empty.
     * @param brightness Uniform light value (0.0-1.0), applied via glColor4f.
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

        // Beta: var20=1.0, var21=0.5, var22=0.25
        // addVertexWithUV(0-var21, 0-var22, 0, ...) etc.
        // Single flat quad, normal (0,1,0), no back face, no edges.
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(-0.5,  -0.25, 0.0).tex(minU, maxV).normal(0, 1, 0).endVertex();
        buf.pos( 0.5,  -0.25, 0.0).tex(maxU, maxV).normal(0, 1, 0).endVertex();
        buf.pos( 0.5,   0.75, 0.0).tex(maxU, minV).normal(0, 1, 0).endVertex();
        buf.pos(-0.5,   0.75, 0.0).tex(minU, minV).normal(0, 1, 0).endVertex();
        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.disableRescaleNormal();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---- Sprite resolution --------------------------------------------------

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
