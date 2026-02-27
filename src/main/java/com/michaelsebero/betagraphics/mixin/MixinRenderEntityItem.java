package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaItemHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Random;

/**
 * Mixin targeting RenderEntityItem to restore Beta 1.7.3b's dropped-item appearance.
 *
 * Two render paths, ported directly from Beta's RenderItem.doRenderItem:
 *
 *   ItemBlock (blocks) -- Beta-style spinning 3D cube with random pile jitter.
 *     Scale: 0.25F for normal-render blocks, 0.5F for non-normal (matches Beta).
 *     Per-copy jitter: (random * 2 - 1) * 0.2F / scale in X, Y, Z.
 *     Random seed reset to 187L each call (Beta: this.random.setSeed(187L)).
 *
 *   All other items -- Y-axis cylindrical billboard + flat 2D quad.
 *     Only rotate(180 - playerViewY, 0,1,0) applied -- no pitch rotation.
 *     Items face where the player IS, not where they are looking.
 *     Per-copy jitter: (random * 2 - 1) * 0.3F in X, Y, Z (Beta's exact values).
 *     BetaItemHelper.renderBetaItem2D draws one centred flat quad per copy.
 *
 * Stack count thresholds (Beta 1.7.3b):
 *   1 copy   -- count == 1
 *   2 copies -- count > 1
 *   3 copies -- count > 5
 *   4 copies -- count > 20
 *
 * isDead guard:
 *   EntityItem.setDead() fires while the entity is still in the render queue when
 *   collected. Guard skips item geometry for dead entities to prevent a ghost frame.
 */
@Mixin(net.minecraft.client.renderer.entity.RenderEntityItem.class)
public abstract class MixinRenderEntityItem extends Render<Entity> {

    // Beta's RenderItem declared this.random = new Random() and seeded it to 187L each call.
    private final Random random = new Random();

    protected MixinRenderEntityItem(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * Full replacement for RenderEntityItem.doRender (SRG: func_76986_a).
     *
     * @reason Restores Beta 1.7.3b dropped item appearance:
     *         blocks as Beta-style spinning 3D cubes with random pile jitter,
     *         all other items as Y-axis-billboard flat 2D quads.
     * @author michaelsebero
     */
    @Overwrite(remap = false)
    public void func_76986_a(Entity entityIn, double x, double y, double z,
            float entityYaw, float partialTicks) {

        if (!(entityIn instanceof EntityItem)) {
            super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
            return;
        }

        EntityItem entityItem = (EntityItem) entityIn;
        ItemStack stack = entityItem.getItem();

        // Dead or empty -- skip geometry, still allow super to handle shadow/fire.
        if (entityIn.isDead || stack.isEmpty()) {
            super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
            return;
        }

        // Beta stack count thresholds: 1 / >1->2 / >5->3 / >20->4.
        int count = stack.getCount();
        int copies;
        if      (count > 20) copies = 4;
        else if (count >  5) copies = 3;
        else if (count >  1) copies = 2;
        else                 copies = 1;

        // Bob -- Beta: sin((age + partialTicks) / 10 + field_804_d) * 0.1 + 0.1
        float age = entityItem.ticksExisted + partialTicks;
        float bob = MathHelper.sin((age / 10.0F) + entityItem.hoverStart) * 0.1F + 0.1F;

        // Spin angle -- Beta: (age + partialTicks) / 20 * (180/PI), i.e. 1 rad/s.
        float spinAngle = (age / 20.0F) * (180.0F / (float) Math.PI);

        if (stack.getItem() instanceof ItemBlock) {
            renderBetaBlockItem(stack, x, y, z, bob, spinAngle, copies);
        } else {
            float brightness = getBrightnessAt(entityIn.posX, entityIn.posY, entityIn.posZ);
            renderBetaFlatItem(stack, x, y, z, bob, brightness, copies);
        }

        // Shadow -- MixinRender applies the Beta light > 3 threshold.
        super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
    }

    // -------------------------------------------------------------------------
    // Block items -- Beta 1.7.3b behaviour
    // -------------------------------------------------------------------------

    /**
     * Renders a block item matching Beta's doRenderItem block path.
     *
     * Beta scale: 0.25F for normal-render blocks, 0.5F for non-normal.
     * Per-copy jitter: (random * 2 - 1) * 0.2F / scale in all three axes,
     * so the jitter is 0.2F in world space regardless of the scale factor.
     * No fixed offset arrays; no extra per-copy Y-rotation spread.
     * Random seed reset to 187L each call (Beta: this.random.setSeed(187L)).
     */
    private void renderBetaBlockItem(ItemStack stack,
            double x, double y, double z,
            float bob, float spinAngle, int copies) {

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return;

        // Beta's scale selection: 0.25F for normal blocks, 0.5F for non-normal.
        float scale = 0.25F;
        net.minecraft.block.Block block = ((ItemBlock) stack.getItem()).getBlock();
        if (!block.isOpaqueCube(block.getDefaultState())) {
            scale = 0.5F;
        }

        random.setSeed(187L);

        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) x, (float) y + bob, (float) z);
            GlStateManager.rotate(spinAngle, 0.0F, 1.0F, 0.0F);
            GlStateManager.scale(scale, scale, scale);

            if (i > 0) {
                // Beta: (random * 2 - 1) * 0.2F / scale per axis.
                float jitterScale = 0.2F / scale;
                float jx = (random.nextFloat() * 2.0F - 1.0F) * jitterScale;
                float jy = (random.nextFloat() * 2.0F - 1.0F) * jitterScale;
                float jz = (random.nextFloat() * 2.0F - 1.0F) * jitterScale;
                GlStateManager.translate(jx, jy, jz);
            }

            mc.getRenderItem().renderItem(stack, ItemCameraTransforms.TransformType.GROUND);
            GlStateManager.popMatrix();
        }
    }

    // -------------------------------------------------------------------------
    // Non-block items -- Beta 1.7.3b flat quad cylindrical billboard
    // -------------------------------------------------------------------------

    /**
     * Renders a non-block item as one or more flat 2D quads, matching Beta's
     * doRenderItem 2D path exactly.
     *
     * Beta applies only a Y-axis rotation (180 - playerViewY) per copy -- a
     * cylindrical billboard. Items face the player's horizontal position, not the
     * camera's look direction. No pitch rotation is applied.
     *
     * Per-copy jitter (i > 0): (random * 2 - 1) * 0.3F in X, Y, Z.
     * Random seed reset to 187L each call.
     *
     * Scale is 0.5F in all axes (Beta: glScalef(0.5, 0.5, 0.5)).
     * BetaItemHelper.renderBetaItem2D draws the single centred flat quad.
     */
    private void renderBetaFlatItem(ItemStack stack,
            double x, double y, double z,
            float bob, float brightness, int copies) {

        random.setSeed(187L);

        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) x, (float) y + bob, (float) z);

            // Y-axis billboard only -- face the player's position, not look direction.
            GlStateManager.rotate(180.0F - this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);

            if (i > 0) {
                // Beta: (random * 2 - 1) * 0.3F jitter per extra copy.
                float jx = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                float jy = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                float jz = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                GlStateManager.translate(jx, jy, jz);
            }

            GlStateManager.scale(0.5F, 0.5F, 0.5F);

            BetaItemHelper.renderBetaItem2D(stack, brightness);

            GlStateManager.popMatrix();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns Beta-style light brightness at the given world position. */
    private static float getBrightnessAt(double worldX, double worldY, double worldZ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null) return 1.0F;
        BlockPos pos = new BlockPos(
            MathHelper.floor(worldX),
            MathHelper.floor(worldY),
            MathHelper.floor(worldZ));
        return MathHelper.clamp(mc.world.getLightBrightness(pos), 0.0F, 1.0F);
    }
}
