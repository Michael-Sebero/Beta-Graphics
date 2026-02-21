package com.michaelsebero.betagraphics.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * Replaces 1.12.2's entity blob shadow with Beta 1.7.3b's exact logic.
 *
 * Beta 1.7.3b shadow conditions (Render.java line 118):
 *   1. A non-air block exists directly below the tested position.
 *   2. The combined light level (max of reduced-sky and block) is > 3.
 * Both conditions must be true for a shadow quad to render. 1.12.2 has no
 * condition 2; the shadow renders at any light level with fading opacity.
 *
 * Shadow opacity formula (renderShadowOnBlock):
 *   alpha = (shadowOpacity - heightAboveBlock / 2) * 0.5 * getLightBrightness(bx, by, bz)
 *
 * Render.shadowSize is located by type scan (first float field declared on
 * Render) rather than by name, making the lookup immune to SRG/MCP mapping
 * differences. A MutableBlockPos is reused across the inner loop to avoid
 * per-iteration heap allocation for large entities with wide shadow radii.
 *
 * UV formula (centred on entity XZ, scaled by shadowSize):
 *   u = (entityX_render - blockCornerX_render) / (2 * shadowSize) + 0.5
 *   v = (entityZ_render - blockCornerZ_render) / (2 * shadowSize) + 0.5
 */
public final class BetaShadowHelper {

    private static final ResourceLocation SHADOW_TEXTURE =
        new ResourceLocation("textures/misc/shadow.png");

    /**
     * Render.shadowSize — first float field declared on the class.
     * This is unambiguous: all other float-like values live on subclasses
     * or on RenderManager rather than on Render directly.
     */
    private static final Field SHADOW_SIZE_FIELD;

    static {
        Field found = null;
        for (Field f : Render.class.getDeclaredFields()) {
            if (f.getType() == float.class) {
                f.setAccessible(true);
                found = f;
                System.out.println("[BetaGraphics] Located Render float field '"
                    + f.getName() + "' as shadowSize — shadow patch ready.");
                break;
            }
        }
        SHADOW_SIZE_FIELD = found;
        if (SHADOW_SIZE_FIELD == null) {
            System.err.println(
                "[BetaGraphics] Could not locate shadowSize in Render. Shadow threshold disabled.");
        }
    }

    private BetaShadowHelper() {}

    /**
     * Full replacement for {@code Render.renderShadow}.
     * Parameters match the 1.12.2 signature exactly.
     *
     * @param renderer     The entity renderer (this).
     * @param entity       The entity being rendered.
     * @param x            Entity X in render-camera space.
     * @param y            Entity Y in render-camera space.
     * @param z            Entity Z in render-camera space.
     * @param shadowOpacity Distance-faded opacity from doRenderShadowAndFire.
     * @param partialTicks  For position interpolation.
     */
    public static void renderBetaShadow(Render<?> renderer, Entity entity,
            double x, double y, double z, float shadowOpacity, float partialTicks) {

        if (SHADOW_SIZE_FIELD == null) return;

        float shadowSize;
        try {
            shadowSize = SHADOW_SIZE_FIELD.getFloat(renderer);
        } catch (IllegalAccessException e) {
            return;
        }
        if (shadowSize <= 0.0F) return;

        RenderManager rm = renderer.getRenderManager();
        World world = rm.world;
        if (world == null) return;

        // Interpolated entity world position.
        // eyBase: pure interpolated Y with no shadow offset — used for the
        // render-to-world offset calculation so offY == -rm.viewerPosY exactly.
        // ey: eyBase + shadowSize, used for height-above-block comparisons,
        // matching Beta's var15 = lastTickPosY + (posY - lastTickPosY) * pt + getShadowSize().
        double ex     = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double eyBase = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double ez     = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        double ey     = eyBase + shadowSize;

        double offX = x - ex;
        double offY = y - eyBase;
        double offZ = z - ez;

        int minX = MathHelper.floor(ex - shadowSize);
        int maxX = MathHelper.floor(ex + shadowSize);
        int minY = MathHelper.floor(ey - shadowSize);
        int maxY = MathHelper.floor(ey);
        int minZ = MathHelper.floor(ez - shadowSize);
        int maxZ = MathHelper.floor(ez + shadowSize);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        rm.renderEngine.bindTexture(SHADOW_TEXTURE);
        GlStateManager.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7 /* GL_QUADS */, DefaultVertexFormats.POSITION_TEX_COLOR);

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {

                    // Condition 1: non-air block directly below.
                    mpos.setPos(bx, by - 1, bz);
                    IBlockState ground = world.getBlockState(mpos);
                    if (ground.getMaterial() == Material.AIR) continue;

                    // Condition 2: Beta's Render.java line 118 — light must be > 3.
                    mpos.setPos(bx, by, bz);
                    if (world.getLightFromNeighbors(mpos) <= 3) continue;

                    renderShadowQuad(buf, ground, world,
                        bx, by, bz,
                        ex, ey, ez, x, z,
                        shadowOpacity, shadowSize,
                        offX, offY, offZ,
                        mpos);
                }
            }
        }

        tess.draw();

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
    }

    /**
     * Draws one shadow quad on the top face of block (bx, by, bz).
     * Direct port of Beta's renderShadowOnBlock.
     *
     * Alpha formula: (shadowOpacity - heightAboveBlock/2) * 0.5 * getLightBrightness
     * UV formula:    u/v centred on entity XZ, scaled by shadowSize * 2.
     */
    private static void renderShadowQuad(BufferBuilder buf, IBlockState state, World world,
            int bx, int by, int bz,
            double ex, double ey, double ez, double rx, double rz,
            float shadowOpacity, float shadowSize,
            double offX, double offY, double offZ,
            BlockPos.MutableBlockPos mpos) {

        if (!state.isFullBlock()) return;

        double heightAboveBlock = ey - by;

        double alpha = ((double) shadowOpacity - heightAboveBlock / 2.0D)
                        * 0.5D
                        * world.getLightBrightness(mpos);

        if (alpha < 0.0D) return;
        if (alpha > 1.0D) alpha = 1.0D;

        int a = (int) (alpha * 255.0D);

        double x0 = bx + offX;
        double x1 = bx + 1.0D + offX;
        double qy  = by + offY + 0.015625D;
        double z0 = bz + offZ;
        double z1 = bz + 1.0D + offZ;

        float twoS = shadowSize * 2.0F;
        float u0 = (float) ((rx - x0) / twoS + 0.5D);
        float u1 = (float) ((rx - x1) / twoS + 0.5D);
        float v0 = (float) ((rz - z0) / twoS + 0.5D);
        float v1 = (float) ((rz - z1) / twoS + 0.5D);

        buf.pos(x0, qy, z0).tex(u0, v0).color(255, 255, 255, a).endVertex();
        buf.pos(x0, qy, z1).tex(u0, v1).color(255, 255, 255, a).endVertex();
        buf.pos(x1, qy, z1).tex(u1, v1).color(255, 255, 255, a).endVertex();
        buf.pos(x1, qy, z0).tex(u1, v0).color(255, 255, 255, a).endVertex();
    }
}
