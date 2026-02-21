package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaItemHelper;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin targeting RenderEntityItem to restore Beta 1.7.3b's dropped-item appearance.
 *
 * Dropped items in Beta were billboards — the quad always faces the camera,
 * achieved by rotating opposite to the camera's yaw and pitch:
 *   GlStateManager.rotate(-renderManager.playerViewY, 0, 1, 0);  // undo yaw
 *   GlStateManager.rotate( renderManager.playerViewX, 1, 0, 0);  // undo pitch
 * After these rotations, local +Z points along camera depth, so the flat card
 * drawn by BetaItemHelper always faces the viewer from any angle.
 */
@Mixin(net.minecraft.client.renderer.entity.RenderEntityItem.class)
public abstract class MixinRenderEntityItem extends Render<Entity> {

    protected MixinRenderEntityItem(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * Full replacement for RenderEntityItem.doRender (SRG: func_76986_a).
     *
     * @reason Restores Beta 1.7.3b dropped item 2D billboard appearance.
     * @author michaelsebero
     */
    @Overwrite(remap = false)
    public void func_76986_a(Entity entityIn, double x, double y, double z,
            float entityYaw, float partialTicks) {

        if (!(entityIn instanceof EntityItem)) return;
        EntityItem entityItem = (EntityItem) entityIn;

        ItemStack stack = entityItem.getItem();
        if (stack.isEmpty()) return;

        // Gentle sine-wave bob; hoverStart staggers items in the same pile.
        float age = entityItem.ticksExisted + partialTicks;
        float bob = MathHelper.sin((age / 10.0F) + entityItem.hoverStart) * 0.1F + 0.1F;

        float brightness = BetaItemHelper.getBrightnessAt(
            entityIn.posX, entityIn.posY, entityIn.posZ);

        GlStateManager.pushMatrix();

        // Translate to entity position; 0.2F base hover clears the ground plane.
        GlStateManager.translate((float) x, (float) y + 0.2F + bob, (float) z);

        // Billboard rotation — inverse of camera yaw then pitch.
        GlStateManager.rotate(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate( this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);

        GlStateManager.scale(0.5F, 0.5F, 0.5F);
        GlStateManager.translate(-0.5F, -0.5F, 1.0F / 32.0F);

        BetaItemHelper.renderBetaItem2D(stack, brightness);

        GlStateManager.popMatrix();

        // Render entity shadow (applies Beta light > 3 threshold via MixinRender).
        super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
    }
}
