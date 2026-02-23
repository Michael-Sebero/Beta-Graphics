package com.michaelsebero.betagraphics;

import com.michaelsebero.betagraphics.client.BetaFogHelper;
import com.michaelsebero.betagraphics.client.BetaLeavesHelper;
import com.michaelsebero.betagraphics.client.BetaLightmapHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central event handler for Beta Graphics.
 *
 * Responsibilities:
 *   - Patches lightBrightnessTable on every world/dimension load.
 *   - Locks gammaSetting to 0.0F and ambientOcclusion to 1 each client tick.
 *   - Calls BetaLightmapHelper.generateBetaLightmap() at 20Hz as a fallback
 *     in case the Mixin injection into updateLightmap does not fire.
 *   - Ticks BetaFogHelper.tickAmbientDarken() each client tick.
 *   - Simulates Beta's updateAllRenderers() chunk-invalidation wave when
 *     skylightSubtracted changes.
 *   - Forces cross-chunk VBO rebuilds when light-emitting blocks change.
 *   - Applies GL_FLAT shading around living entity renders.
 *   - Wires BetaLeavesHelper into the model bake pipeline.
 *
 * Flat shading:
 *   Beta's RenderHelper.enableStandardItemLighting() called glShadeModel(GL_FLAT).
 *   Because MixinRenderHelper cannot target this method in Cleanroom, GL_FLAT is
 *   applied via RenderLivingEvent.Pre and restored via RenderLivingEvent.Post.
 *   RenderWorldLastEvent provides a safety-net restore after the full world pass.
 *
 * Dual lightmap correction:
 *   Path A (~60Hz): MixinEntityRenderer injects generateBetaLightmap() before
 *   each return in EntityRenderer.updateLightmap.
 *   Path B (20Hz): generateBetaLightmap() is called here as a guaranteed fallback.
 *
 * Cross-chunk light fix:
 *   BlockEvent.PlaceEvent / BlockEvent.BreakEvent fire server-side
 *   (world.isRemote == false). The server-side markBlockRangeForRenderUpdate
 *   flushes pending chunk sections to the client immediately. A PendingRebuild
 *   is also queued to mark client VBOs dirty after LIGHT_PACKET_WAIT_TICKS,
 *   catching any cross-chunk light data that races with the initial server flush.
 *   The delayed rebuild always uses mc.world (the live client render world)
 *   resolved at flush time, not a stored server-world reference.
 *
 * --- FIX: Thread safety for pendingRebuilds ---
 * onBlockPlace and onBlockBreak fire on the integrated server thread.
 * onClientTick (and onWorldLoad for remote worlds) runs on the client thread.
 * The original ArrayDeque is not thread-safe; concurrent access from both threads
 * can corrupt the deque structure or throw ConcurrentModificationException.
 * Fix: replaced ArrayDeque with ConcurrentLinkedQueue, which is designed for
 * exactly this producer-on-one-thread / consumer-on-another-thread pattern.
 * The flush loop is rewritten to drain using poll() in a single-pass, respecting
 * entries that were added between the start and end of the tick's drain.
 *
 * --- FIX: onBlockBreak missing checkLightFor calls ---
 * onBlockPlace called checkLightFor for the origin and all 6 neighbors to force
 * the BFS re-propagation of block light immediately. onBlockBreak did not,
 * leaving stale block-light values in neighbour positions until vanilla's slower
 * update path resolved them. Fix: added matching checkLightFor calls to
 * onBlockBreak, consistent with how onBlockPlace was already written.
 */
public class BetaGraphicsEventHandler {

    private static final float BETA_AMBIENT = 0.1F;
    private static final int   BETA_AO      = 1;
    private static final float BETA_GAMMA   = 0.0F;

    /**
     * Ticks before the delayed client-side VBO rebuild fires after a block change.
     * 5 ticks (~250ms) provides enough headroom for server-side BFS to complete
     * and for all resulting chunk-section packets to arrive.
     */
    private static final int LIGHT_PACKET_WAIT_TICKS = 5;

    private int     prevSkyLightSub     = -1;
    private boolean skyLightInitialized = false;

    private static final class PendingRebuild {
        final BlockPos pos;
        final int      lightValue;
        volatile int   ticksRemaining;

        PendingRebuild(BlockPos pos, int lightValue, int ticksRemaining) {
            this.pos            = pos;
            this.lightValue     = lightValue;
            this.ticksRemaining = ticksRemaining;
        }
    }

    /**
     * FIX: ConcurrentLinkedQueue replaces ArrayDeque.
     *
     * onBlockPlace/onBlockBreak (server thread) add entries; onClientTick (client
     * thread) drains them. ArrayDeque is not thread-safe and would corrupt under
     * concurrent access. ConcurrentLinkedQueue provides lock-free thread safety
     * with no synchronisation overhead on the hot client-tick path.
     */
    private final ConcurrentLinkedQueue<PendingRebuild> pendingRebuilds =
        new ConcurrentLinkedQueue<>();

    // ── World load ────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        patchLightBrightnessTable(world);

        if (world.isRemote) {
            pendingRebuilds.clear();
        }
    }

    /**
     * Rewrites the world's lightBrightnessTable with Beta's 0.1 ambient floor.
     * Vanilla 1.12.2 uses 0.05, which makes darkness ~50% deeper than Beta and
     * shifts the curve for all subsequent light calculations.
     */
    public static void patchLightBrightnessTable(World world) {
        float[] table = world.provider.getLightBrightnessTable();
        for (int i = 0; i <= 15; i++) {
            float darkness = 1.0F - (float) i / 15.0F;
            table[i] = (1.0F - darkness) / (darkness * 3.0F + 1.0F)
                            * (1.0F - BETA_AMBIENT)
                            + BETA_AMBIENT;
        }
    }

    // ── Client tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return;

        if (mc.gameSettings.ambientOcclusion != BETA_AO) {
            mc.gameSettings.ambientOcclusion = BETA_AO;
        }

        if (mc.gameSettings.gammaSetting != BETA_GAMMA) {
            mc.gameSettings.gammaSetting = BETA_GAMMA;
        }

        if (mc.world != null && mc.entityRenderer != null) {
            BetaLightmapHelper.generateBetaLightmap();
        }

        // Flush delayed VBO rebuilds.
        // FIX: Rewritten for ConcurrentLinkedQueue. We snapshot the current queue
        // size to avoid processing entries that were added during this tick's drain
        // (they'd have their full ticksRemaining and should wait). Items not yet
        // ready are re-queued; items due are applied to mc.world at flush time.
        if (!pendingRebuilds.isEmpty() && mc.world != null) {
            // Snapshot size: only process entries already in the queue at tick start.
            int toProcess = pendingRebuilds.size();
            for (int i = 0; i < toProcess; i++) {
                PendingRebuild rb = pendingRebuilds.poll();
                if (rb == null) break;
                rb.ticksRemaining--;
                if (rb.ticksRemaining <= 0) {
                    markLightRange(mc.world, rb.pos, rb.lightValue);
                } else {
                    pendingRebuilds.add(rb);
                }
            }
        }

        if (mc.world == null || mc.player == null) {
            skyLightInitialized = false;
            prevSkyLightSub = -1;
            return;
        }

        BlockPos playerPos = new BlockPos(mc.player);
        BetaFogHelper.tickAmbientDarken(mc.world, playerPos);

        // Trigger dusk/dawn chunk-invalidation wave when skylightSubtracted changes.
        int currentSkyLightSub = mc.world.calculateSkylightSubtracted(1.0F);
        if (!skyLightInitialized) {
            prevSkyLightSub = currentSkyLightSub;
            skyLightInitialized = true;
            return;
        }
        if (currentSkyLightSub != prevSkyLightSub) {
            prevSkyLightSub = currentSkyLightSub;
            triggerWaveRerender(mc);
        }
    }

    // ── Flat shading ──────────────────────────────────────────────────────────

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onRenderLivingPre(RenderLivingEvent.Pre event) {
        GL11.glShadeModel(GL11.GL_FLAT);
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onRenderLivingPost(RenderLivingEvent.Post event) {
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    /** Safety-net restore of GL_SMOOTH after the full world render pass. */
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    // ── Cross-chunk block light fix ───────────────────────────────────────────

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        int lv = event.getPlacedBlock().getLightValue();
        if (lv <= 0) return;

        World world = event.getWorld();
        BlockPos origin = event.getPos();

        world.checkLightFor(EnumSkyBlock.BLOCK, origin);
        for (EnumFacing face : EnumFacing.VALUES) {
            world.checkLightFor(EnumSkyBlock.BLOCK, origin.offset(face));
        }
        markLightRange(world, origin, lv);

        if (!world.isRemote) {
            pendingRebuilds.add(new PendingRebuild(origin, lv, LIGHT_PACKET_WAIT_TICKS));
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        int lv = event.getState().getLightValue();
        if (lv <= 0) return;

        World world = event.getWorld();
        BlockPos origin = event.getPos();

        // FIX: Added checkLightFor calls to match onBlockPlace.
        // Without these, breaking a light-emitting block leaves stale block-light
        // values in the 6 neighbouring positions until vanilla's BFS catches up.
        // The origin check re-propagates from the now-dark position; the neighbor
        // checks ensure their contributions from the removed block are cleared.
        world.checkLightFor(EnumSkyBlock.BLOCK, origin);
        for (EnumFacing face : EnumFacing.VALUES) {
            world.checkLightFor(EnumSkyBlock.BLOCK, origin.offset(face));
        }
        markLightRange(world, origin, lv);

        if (!world.isRemote) {
            pendingRebuilds.add(new PendingRebuild(origin, lv, LIGHT_PACKET_WAIT_TICKS));
        }
    }

    // ── Model bake ────────────────────────────────────────────────────────────

    /** Wires BetaLeavesHelper into the model bake pipeline for Beta-style leaf rendering. */
    @SubscribeEvent
    public void onModelBake(ModelBakeEvent event) {
        BetaLeavesHelper.onModelBake(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SideOnly(Side.CLIENT)
    private static void triggerWaveRerender(Minecraft mc) {
        int r  = (mc.gameSettings.renderDistanceChunks + 1) * 16;
        int px = (int) mc.player.posX;
        int pz = (int) mc.player.posZ;
        mc.world.markBlockRangeForRenderUpdate(
            px - r,   0, pz - r,
            px + r, 255, pz + r
        );
    }

    private static void markLightRange(World world, BlockPos origin, int lightValue) {
        int r = lightValue + 1;
        world.markBlockRangeForRenderUpdate(
            origin.getX() - r, origin.getY() - r, origin.getZ() - r,
            origin.getX() + r, origin.getY() + r, origin.getZ() + r
        );
    }
}
