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
 * Two render paths:
 *
 *   ItemBlock (blocks) -- vanilla 1.12.2 default size and appearance.
 *     GROUND ItemCameraTransforms handles all scaling internally; no manual scale
 *     is applied. Beta's 0.25F scale made blocks appear tiny in 1.12.2 because the
 *     GROUND transform already includes its own scale factor.
 *     Vanilla copy counts (1/>1/>5/>20/>48 -> 1/2/3/4/5) are used for blocks.
 *
 *   All other items -- Y-axis cylindrical billboard + flat 2D quad (Beta 1.7.3b).
 *     Only rotate(180 - playerViewY, 0,1,0) applied -- no pitch rotation.
 *     Items face where the player IS, not where they are LOOKING.
 *     0.2F base Y lift compensates for 1.12.2 posY being at the bounding box floor;
 *     in Beta, EntityItem set yOffset = height/2 = 0.125F and the render y was
 *     already elevated. 0.2F provides correct visible floating height in 1.12.2.
 *     Per-copy jitter seed: 187L ^ entityId, so separate EntityItem entities at the
 *     same block position get different jitter offsets and do not perfectly combine.
 *
 * Stack count thresholds for flat items (Beta 1.7.3b):
 *   1 copy   -- count == 1
 *   2 copies -- count > 1
 *   3 copies -- count > 5
 *   4 copies -- count > 20
 */
@Mixin(net.minecraft.client.renderer.entity.RenderEntityItem.class)
public abstract class MixinRenderEntityItem extends Render<Entity> {

    private final Random random = new Random();

    protected MixinRenderEntityItem(RenderManager renderManager) {
        super(renderManager);
    }

    /**
     * Full replacement for RenderEntityItem.doRender (SRG: func_76986_a).
     *
     * @reason Restores Beta 1.7.3b dropped item appearance for non-block items;
     *         keeps vanilla 1.12.2 default rendering for block items.
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

        if (entityIn.isDead || stack.isEmpty()) {
            super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
            return;
        }

        // Bob -- Beta: sin((age + partialTicks) / 10 + field_804_d) * 0.1 + 0.1
        float age = entityItem.ticksExisted + partialTicks;
        float bob = MathHelper.sin((age / 10.0F) + entityItem.hoverStart) * 0.1F + 0.1F;

        // Spin angle -- Beta: (age + partialTicks) / 20 * (180/PI), i.e. 1 rad/s.
        float spinAngle = (age / 20.0F) * (180.0F / (float) Math.PI);

        if (stack.getItem() instanceof ItemBlock) {
            renderDefaultBlockItem(stack, x, y, z, bob, spinAngle, stack.getCount());
        } else {
            // Beta stack count thresholds: 1 / >1->2 / >5->3 / >20->4.
            int count = stack.getCount();
            int copies;
            if      (count > 20) copies = 4;
            else if (count >  5) copies = 3;
            else if (count >  1) copies = 2;
            else                 copies = 1;

            float brightness = getBrightnessAt(entityIn.posX, entityIn.posY, entityIn.posZ);
            renderBetaFlatItem(stack, x, y, z, bob, brightness, copies,
                entityItem.getEntityId());
        }

        super.doRender(entityIn, x, y, z, entityYaw, partialTicks);
    }

    // -------------------------------------------------------------------------
    // Block items -- vanilla 1.12.2 default appearance
    // -------------------------------------------------------------------------

    /**
     * Renders a block item with vanilla 1.12.2 default size and piling.
     *
     * No manual scale is applied. GROUND ItemCameraTransforms handles all sizing
     * internally. Applying an additional glScale (e.g. Beta's 0.25F) on top of
     * the GROUND transform makes blocks appear tiny in 1.12.2.
     *
     * Vanilla copy counts: 1 / >1->2 / >5->3 / >20->4 / >48->5.
     * Per-copy jitter: (random*2-1)*0.15F in X/Z, fixed +/- pattern for Y so
     * the pile stays roughly level (vanilla uses fixed offset arrays; we use a
     * simple deterministic spread that looks equivalent).
     */
    private void renderDefaultBlockItem(ItemStack stack,
            double x, double y, double z,
            float bob, float spinAngle, int count) {

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return;

        int copies;
        if      (count > 48) copies = 5;
        else if (count > 20) copies = 4;
        else if (count >  5) copies = 3;
        else if (count >  1) copies = 2;
        else                 copies = 1;

        // Deterministic fixed offsets matching vanilla's visual pile appearance.
        float[] offX = { 0.0F,  0.17F, -0.17F,  0.17F, -0.17F };
        float[] offZ = { 0.0F,  0.17F,  0.17F, -0.17F, -0.17F };
        float[] offR = { 0.0F, 36.0F,  72.0F,  108.0F, 144.0F };

        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(
                (float) x + offX[i],
                (float) y + 0.2F + bob,
                (float) z + offZ[i]);
            GlStateManager.rotate(spinAngle + offR[i], 0.0F, 1.0F, 0.0F);
            // No glScale here -- GROUND transform handles size internally.
            mc.getRenderItem().renderItem(stack, ItemCameraTransforms.TransformType.GROUND);
            GlStateManager.popMatrix();
        }
    }

    // -------------------------------------------------------------------------
    // Non-block items -- Beta 1.7.3b flat quad cylindrical billboard
    // -------------------------------------------------------------------------

    /**
     * Renders a non-block item as one or more flat 2D quads per Beta 1.7.3b.
     *
     * Y position: y + 0.2F + bob.
     *   The 0.2F base lift is needed because in 1.12.2 posY is the entity bounding
     *   box floor. In Beta, EntityItem set yOffset = height/2 = 0.125F which raised
     *   the logical origin above the floor; combined with the GL translate in
     *   doRenderItem, items visually floated roughly 0.2F above ground.
     *
     * Jitter seed: 187L ^ entityId.
     *   Beta used setSeed(187L) for all entities, which caused separate EntityItem
     *   entities stacked on the same block to produce identical copy offsets and
     *   visually merge into one blob. XOR-ing with the entity ID makes each entity's
     *   copies land at different positions while keeping the pattern deterministic
     *   across frames (entity IDs are stable for the lifetime of the entity).
     *
     * Billboard: Y-axis only (180 - playerViewY). No pitch.
     */
    private void renderBetaFlatItem(ItemStack stack,
            double x, double y, double z,
            float bob, float brightness, int copies, int entityId) {

        // Seed mixes Beta's 187L with the entity ID so stacked entities spread apart.
        random.setSeed(187L ^ (long) entityId);

        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            // 0.2F base lift so items float above the ground, not into it.
            GlStateManager.translate((float) x, (float) y + 0.2F + bob, (float) z);

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
