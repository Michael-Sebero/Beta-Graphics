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
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin targeting RenderEntityItem to restore Beta 1.7.3b's dropped-item appearance.
 *
 * Two render paths:
 *
 *   ItemBlock (blocks) → vanilla 1.12.2 rendering with count-based multi-copy piling.
 *     Standard item lighting stays enabled so diffuse shading is preserved.
 *     1–5 copies rendered based on stack count, each offset and rotated, matching
 *     vanilla's pile appearance.
 *
 *   All other items → full spherical billboard + 2D card via BetaItemHelper.
 *     Spherical billboard cancels both camera yaw and pitch:
 *       rotate(180 - playerViewY, 0,1,0) — cancel yaw, flip front face toward viewer.
 *       rotate(-playerViewX,      1,0,0) — cancel pitch (negated to invert camera tilt).
 *     Yaw applied first (outer), pitch second (inner), mirroring the view matrix order.
 *     Every non-block item uses this path — no flat-model detection.
 *
 * isDead guard:
 *   EntityItem.setDead() fires while the entity is still in the render queue when
 *   collected. Guard skips item geometry for dead entities to prevent a ghost frame.
 */
@Mixin(net.minecraft.client.renderer.entity.RenderEntityItem.class)
public abstract class MixinRenderEntityItem extends Render<Entity> {

    protected MixinRenderEntityItem(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * Full replacement for RenderEntityItem.doRender (SRG: func_76986_a).
     *
     * @reason Restores Beta 1.7.3b dropped item appearance:
     *         blocks as vanilla 1.12.2 shaded 3D cubes with pile counts,
     *         all other items as spherical-billboard 2D cards.
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

        // Dead or empty — skip geometry, still allow super to handle shadow/fire.
        if (entityIn.isDead || stack.isEmpty()) {
            super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
            return;
        }

        // Gentle sine-wave bob; hoverStart staggers items in the same pile.
        float age = entityItem.ticksExisted + partialTicks;
        float bob = MathHelper.sin((age / 10.0F) + entityItem.hoverStart) * 0.1F + 0.1F;

        if (stack.getItem() instanceof ItemBlock) {
            renderVanillaBlockItem(stack, x, y, z, bob, partialTicks, entityItem);
        } else {
            float brightness = BetaItemHelper.getBrightnessAt(
                entityIn.posX, entityIn.posY, entityIn.posZ);
            renderSphericalBillboard(stack, x, y, z, bob, brightness);
        }

        // Shadow — MixinRender applies the Beta light > 3 threshold.
        super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Block item — vanilla 1.12.2 behaviour
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replicates vanilla 1.12.2 RenderEntityItem block rendering:
     *   - Standard item lighting ENABLED — diffuse shading is preserved.
     *   - Count-based multi-copy piling: 1/2/3/4/5 copies at 1/2/16/32/48+ items.
     *   - Each copy is offset in X/Z and has an additional Y rotation so the
     *     pile looks natural, matching vanilla's exact values.
     *   - No extra scale applied — GROUND ItemCameraTransforms controls size.
     */
    private void renderVanillaBlockItem(ItemStack stack,
            double x, double y, double z,
            float bob, float partialTicks, EntityItem entityItem) {

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return;

        int count = stack.getCount();
        int copies;
        if      (count >= 48) copies = 5;
        else if (count >= 32) copies = 4;
        else if (count >= 16) copies = 3;
        else if (count >   1) copies = 2;
        else                  copies = 1;

        // Offsets and rotation spread for each copy — vanilla's values.
        float[] offX = { 0.0F,  0.17F, -0.17F,  0.17F, -0.17F };
        float[] offZ = { 0.0F,  0.17F,  0.17F, -0.17F, -0.17F };
        float[] rot  = { 0.0F, 36.0F,  72.0F,  108.0F, 144.0F };

        // Spin angle — vanilla 1.12.2: ((float)getAge() + partialTicks) / 20.0F radians.
        float spinRad   = ((float) entityItem.getAge() + partialTicks) / 20.0F;
        float spinAngle = spinRad * (180.0F / (float) Math.PI);

        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(
                (float) x + offX[i],
                (float) y + 0.2F + bob,
                (float) z + offZ[i]);
            GlStateManager.rotate(spinAngle + rot[i], 0.0F, 1.0F, 0.0F);
            // Lighting intentionally left ENABLED — block items need diffuse shading.
            mc.getRenderItem().renderItem(stack, ItemCameraTransforms.TransformType.GROUND);
            GlStateManager.popMatrix();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All non-block items — full spherical billboard + 2D card
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders any non-block item as a 2D billboard card that fully tracks the camera.
     *
     * Spherical billboard:
     *   rotate(180 - playerViewY, 0,1,0)
     *     Cancels camera yaw. 180° flips the card so the front face (+Z normal)
     *     points toward the viewer rather than away.
     *   rotate(-playerViewX, 1,0,0)
     *     Cancels camera pitch. Negated because a positive playerViewX tilts the
     *     camera downward; we must tilt back up by the same amount. Applied after
     *     yaw so it acts in the yaw-corrected local frame.
     *
     * Result: card is perpendicular to the view ray at every yaw+pitch combination,
     * eliminating edge-on distortion when viewed from above or below.
     */
    private void renderSphericalBillboard(ItemStack stack,
            double x, double y, double z,
            float bob, float brightness) {

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + 0.2F + bob, (float) z);

        // Cancel camera yaw and flip front face toward viewer.
        GlStateManager.rotate(180.0F - this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        // Cancel camera pitch in the yaw-corrected local frame.
        GlStateManager.rotate(-this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);

        GlStateManager.scale(0.5F, 0.5F, 0.5F);
        // Centre the 0→1 card at the origin (in scaled space).
        GlStateManager.translate(-0.5F, -0.5F, 1.0F / 32.0F);

        BetaItemHelper.renderBetaItem2D(stack, brightness);

        GlStateManager.popMatrix();
    }
}
