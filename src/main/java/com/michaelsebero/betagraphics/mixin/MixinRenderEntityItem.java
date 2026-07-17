package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaItemHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.IBakedModel;
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
 * FIX: the two render paths were previously split on "is this an ItemBlock",
 * which is not Beta's actual condition. Beta's real check (RenderItem.
 * doRenderItem) is `itemID < 256 && RenderBlocks.renderItemIn3d(getRenderType())`
 * -- i.e. it's a block AND that block's render type is a full 3D shape. Cross/
 * flat render types (torches, flowers, saplings, mushrooms, rails, redstone
 * dust, ladders, vines, etc.) are blocks but fail renderItemIn3d, so Beta drops
 * them as flat 2D cards, not 3D cubes. The old ItemBlock-only check routed all
 * of those through the 3D vanilla path instead. 1.12.2 has no equivalent
 * integer render-type, so isModelGui3d() below uses IBakedModel.isGui3d() as
 * the version-appropriate proxy -- it's false for exactly the same class of
 * cross/flat models, since they use "gui3d": false in their model JSON.
 * Independently confirmed against NostalgicTweaks (a mod that replicates the
 * same Beta behaviour on 1.18.2), which makes the identical flat-model-vs-not
 * distinction rather than block-vs-not-a-block.
 *
 * Two render paths:
 *
 *   3D path -- vanilla 1.12.2 default size and appearance. Used only when the
 *     item is an ItemBlock AND its baked model is gui3d (full-cube-style blocks).
 *     GROUND ItemCameraTransforms handles all scaling internally; no manual scale
 *     is applied. Beta's 0.25F scale made blocks appear tiny in 1.12.2 because the
 *     GROUND transform already includes its own scale factor.
 *     Vanilla copy counts (1/>1/>5/>20/>48 -> 1/2/3/4/5) are used for blocks.
 *
 *   Flat 2D path -- everything else (non-block items, and blocks whose model
 *     isn't gui3d) -- Y-axis cylindrical billboard + flat 2D quad (Beta 1.7.3b).
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
     * Full replacement for RenderEntityItem.doRender.
     *
     * FIX: previously declared as func_76986_a with remap=false, on the
     * assumption this build generates no refmap and the runtime target is
     * SRG-obfuscated. This project's toolchain (RetroFuturaGradle 1.4.1) writes
     * a refmap on every compile, and the Mixin AP resolves @Overwrite targets
     * against the MCP-named compilePatchedMcJava output -- confirmed by a build
     * where func_76986_a produced "Cannot find target for @Overwrite method in
     * net.minecraft.client.renderer.entity.RenderEntityItem". doRender with the
     * default remap=true is what actually resolves.
     *
     * @reason Restores Beta 1.7.3b dropped item appearance for non-block items;
     *         keeps vanilla 1.12.2 default rendering for block items.
     * @author michaelsebero
     */
    @Overwrite
    public void doRender(Entity entityIn, double x, double y, double z,
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

        // Spin angle -- Beta: ((age + partialTicks) / 20 + field_804_d) * (180/PI).
        // FIX: was missing "+ entityItem.hoverStart" (Beta's field_804_d). Without it,
        // every dropped item starts its spin from the same angle at the same tick, so
        // items dropped together spin in lockstep instead of Beta's per-entity phase.
        // Confirmed against Beta's actual RenderItem.doRenderItem, which adds the same
        // field_804_d phase to both the bob and spin terms; bob already had it.
        float spinAngle = (age / 20.0F + entityItem.hoverStart) * (180.0F / (float) Math.PI);

        if (stack.getItem() instanceof ItemBlock && isModelGui3d(stack)) {
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

        // FIX: call order was rotate -> jitter -> scale; Beta's is scale -> jitter -> rotate
        // (GL fixed-function calls apply to vertices in reverse of call order, so this
        // isn't just a cosmetic reordering -- it changes what the jitter offset actually
        // does). With rotate before jitter, the jitter translate got carried inside the
        // billboard rotation, so extra copies scattered along camera-relative axes instead
        // of world-aligned ones. With scale after jitter, the jitter translate never got
        // the 0.5x scale-down applied to it, so copies spread at the raw +/-0.3 blocks
        // instead of Beta's effective +/-0.15. Both are fixed by matching Beta's order.
        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            // 0.2F base lift so items float above the ground, not into it.
            GlStateManager.translate((float) x, (float) y + 0.2F + bob, (float) z);

            GlStateManager.scale(0.5F, 0.5F, 0.5F);

            if (i > 0) {
                // Beta: (random * 2 - 1) * 0.3F jitter per extra copy, applied pre-rotation
                // (world-aligned) and inside the 0.5x scale (effective +/-0.15 blocks).
                float jx = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                float jy = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                float jz = (random.nextFloat() * 2.0F - 1.0F) * 0.3F;
                GlStateManager.translate(jx, jy, jz);
            }

            // Y-axis billboard only -- face the player's position, not look direction.
            // Applied last so only the quad itself is billboarded, not the jitter offset.
            GlStateManager.rotate(180.0F - this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);

            BetaItemHelper.renderBetaItem2D(stack, brightness);

            GlStateManager.popMatrix();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * True if this item's baked model is a full 3D shape (isGui3d) -- the
     * version-appropriate proxy for Beta's RenderBlocks.renderItemIn3d(getRenderType()).
     * False for cross/flat block models (torches, flowers, saplings, mushrooms,
     * rails, redstone dust, ladders, vines, etc.), which use "gui3d": false in
     * their model JSON so they render as flat icons in the inventory GUI -- the
     * same class of blocks Beta's own render-type check routes to its flat 2D
     * path instead of the 3D block path.
     *
     * Fails toward false (flat path) on a missing/errored model, since that's
     * Beta's default for anything that isn't cleanly a normal 3D block.
     */
    private static boolean isModelGui3d(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return false;
        try {
            IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
            return model != null && model.isGui3d();
        } catch (Exception e) {
            return false;
        }
    }

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
