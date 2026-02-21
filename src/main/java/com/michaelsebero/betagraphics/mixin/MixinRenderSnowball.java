package com.michaelsebero.betagraphics.mixin;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;

/**
 * Mixin targeting RenderSnowball to restore Beta 1.7.3b's 2D thrown item appearance.
 *
 * Billboard rotation is applied (inverse camera yaw then pitch) so that the
 * subsequent scale(0.5, 0.5, 0.001) collapses depth in camera space rather
 * than world space, producing a correctly flat camera-facing sprite.
 *
 * RenderSnowball's Item and RenderItem fields are located by type scan rather
 * than by name to avoid @Shadow resolution failures due to mapping ambiguity.
 * RenderSnowball has exactly one of each type, so the scan is unambiguous.
 * Fields are resolved once at first doRender call and cached statically.
 */
@Mixin(net.minecraft.client.renderer.entity.RenderSnowball.class)
public abstract class MixinRenderSnowball<T extends Entity> extends Render<T> {

    private static Field SNOWBALL_ITEM_FIELD      = null;
    private static Field SNOWBALL_RENDERITEM_FIELD = null;
    private static boolean fieldsResolved          = false;

    private static void resolveFields(Object instance) {
        if (fieldsResolved) return;
        fieldsResolved = true;

        for (Field f : instance.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == Item.class && SNOWBALL_ITEM_FIELD == null) {
                SNOWBALL_ITEM_FIELD = f;
                System.out.println("[BetaGraphics] RenderSnowball: located Item field '"
                    + f.getName() + "'");
            } else if (f.getType() == RenderItem.class && SNOWBALL_RENDERITEM_FIELD == null) {
                SNOWBALL_RENDERITEM_FIELD = f;
                System.out.println("[BetaGraphics] RenderSnowball: located RenderItem field '"
                    + f.getName() + "'");
            }
        }

        if (SNOWBALL_ITEM_FIELD == null) {
            System.err.println("[BetaGraphics] RenderSnowball: WARN — could not locate Item field.");
        }
        if (SNOWBALL_RENDERITEM_FIELD == null) {
            System.err.println("[BetaGraphics] RenderSnowball: WARN — could not locate RenderItem field.");
        }
    }

    /** Required because this mixin declares extends Render<T>. Never called at runtime. */
    protected MixinRenderSnowball(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * Full replacement for RenderSnowball.doRender (SRG: func_76986_a).
     *
     * @reason Restores Beta 1.7.3b flat 2D thrown item appearance.
     * @author michaelsebero
     */
    @Overwrite(remap = false)
    public void func_76986_a(T entity, double x, double y, double z,
            float entityYaw, float partialTicks) {

        resolveFields(this);
        if (SNOWBALL_ITEM_FIELD == null || SNOWBALL_RENDERITEM_FIELD == null) return;

        Item     item;
        RenderItem renderItem;
        try {
            item       = (Item)       SNOWBALL_ITEM_FIELD.get(this);
            renderItem = (RenderItem) SNOWBALL_RENDERITEM_FIELD.get(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (item == null || renderItem == null) return;

        ItemStack stack = new ItemStack(item);

        GlStateManager.pushMatrix();

        GlStateManager.translate((float) x, (float) y + 0.15F, (float) z);

        // Billboard rotation — makes local +Z point along camera depth so that
        // scale(0.5, 0.5, 0.001) collapses depth in camera space, not world space.
        GlStateManager.rotate(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate( this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);

        GlStateManager.enableRescaleNormal();
        GlStateManager.scale(0.5F, 0.5F, 0.001F);

        this.bindEntityTexture(entity);
        RenderHelper.disableStandardItemLighting();
        renderItem.renderItem(stack, ItemCameraTransforms.TransformType.GROUND);
        RenderHelper.enableStandardItemLighting();

        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();

        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }
}
