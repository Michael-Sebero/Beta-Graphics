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
 *   - Locks gammaSetting to 0.0F each client tick (Beta had no brightness slider).
 *   - Sets ambientOcclusion = 1 exactly once (on first install) as a default,
 *     then never overrides the player's choice again.
 *   - Calls BetaLightmapHelper.generateBetaLightmap() at 20Hz as a fallback
 *     in case the Mixin injection into updateLightmap does not fire.
 *   - Ticks BetaFogHelper.tickAmbientDarken() each client tick.
 *   - Simulates Beta's updateAllRenderers() chunk-invalidation wave when
 *     skylightSubtracted changes.
 *   - Forces cross-chunk VBO rebuilds when light-emitting blocks change.
 *   - Applies GL_FLAT shading around living entity renders.
 *   - Wires BetaLeavesHelper into the model bake pipeline.
 *
 * Smooth lighting default (changed from original lock):
 *   The original code locked ambientOcclusion = 1 every tick, immediately
 *   reverting any change the player made in the Video Settings screen.
 *
 *   New behaviour: BetaGraphicsMod.isAoDefaultApplied() is checked once per
 *   session (and once per JVM start). If it returns false (fresh install or
 *   missing config), ambientOcclusion is set to 1 and
 *   BetaGraphicsMod.markAoDefaultApplied() is called, which writes
 *   aoDefaultApplied=true to config/betagraphics.cfg. Subsequent launches load
 *   the flag as true and skip the default entirely, leaving the player's value
 *   in options.txt untouched.
 *
 *   Because MixinAmbientOcclusionFace still neutralises vertexColorMultiplier
 *   regardless of the AO setting, all three vanilla AO levels (0/1/2) produce
 *   Beta-accurate uniform brightness. The setting only affects vertex
 *   interpolation smoothness, not lightness.
 *
 * Flat shading:
 *   Beta's RenderHelper.enableStandardItemLighting() called glShadeModel(GL_FLAT).
 *   Because MixinRenderHelper cannot target this method in Cleanroom, GL_FLAT is
 *   applied via RenderLivingEvent.Pre and restored via RenderLivingEvent.Post.
 *   flatShadeDepth guards against nested Pre/Post pairs -- a mount rendering
 *   its rider (spider/chicken jockeys, a player on a horse or in a boat) fires
 *   a second pair from inside the outer entity's own render call, and without
 *   the guard the inner Post would restore GL_SMOOTH mid-render of the outer
 *   entity. RenderWorldLastEvent provides a safety-net restore, and resets the
 *   depth counter, after the full world pass.
 *
 * Dual lightmap correction:
 *   Path A (~60Hz): MixinEntityRenderer injects generateBetaLightmap() before
 *   each return in EntityRenderer.updateLightmap.
 *   Path B (20Hz): generateBetaLightmap() is called here as a guaranteed fallback.
 *
 * Cross-chunk light fix:
 *   BlockEvent.PlaceEvent / BlockEvent.BreakEvent fire server-side
 *   (world.isRemote == false). markBlockRangeForRenderUpdate is a no-op on the
 *   server-side World -- only WorldClient overrides it to touch the render
 *   dispatcher -- so an immediate rebuild has to be issued against mc.world,
 *   not the event's World. A PendingRebuild is also queued to mark the same
 *   range dirty again after LIGHT_PACKET_WAIT_TICKS, catching cases where the
 *   immediate attempt ran before the server's light recalculation had synced
 *   back to the client. Both the immediate and delayed rebuilds resolve
 *   mc.world fresh at call time, never a stored world reference.
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
 *
 * --- FIX: markLightRange targeted the wrong World ---
 * onBlockPlace/onBlockBreak called markLightRange(world, ...) using
 * event.getWorld(), which is always the server-side World for these two
 * events (world.isRemote is never true here). World.markBlockRangeForRenderUpdate
 * is an empty stub; WorldClient is the only subclass that does anything with
 * it. The "immediate" half of the cross-chunk fix was therefore a no-op in
 * every case, and the feature was working purely off the delayed
 * PendingRebuild queue, which already resolved mc.world correctly. Fix:
 * extracted queueRebuild(), which issues the immediate call against mc.world
 * instead of the event's World.
 *
 * --- FIX: RenderLivingEvent.Pre/Post had no nesting guard ---
 * A mount rendering its rider fires a nested Pre/Post pair from inside the
 * outer entity's own render call (spider/chicken jockeys, a player on a horse
 * or in a boat). Unconditional set/restore meant the inner Post could switch
 * back to GL_SMOOTH while the outer entity was still being drawn. Fix:
 * flatShadeDepth counts active Pre calls; GL_FLAT is only (re)applied on the
 * 0->1 transition and GL_SMOOTH only restored on the 1->0 transition, so
 * nested pairs no longer step on each other. RenderWorldLastEvent resets the
 * counter to 0 each frame as a backstop in case a Pre is ever left unmatched
 * (e.g. an exception mid-render).
 */
public class BetaGraphicsEventHandler {

    private static final float BETA_GAMMA   = 0.0F;
    private static final int   BETA_AO      = 1;   // Minimum smooth lighting

    /**
     * Ticks before the delayed client-side VBO rebuild fires after a block change.
     * 5 ticks (~250ms) provides enough headroom for server-side BFS to complete
     * and for all resulting chunk-section packets to arrive.
     */
    private static final int LIGHT_PACKET_WAIT_TICKS = 5;

    private int     prevSkyLightSub     = -1;
    private boolean skyLightInitialized = false;

    /** Active RenderLivingEvent.Pre count, guarding against nested mount/rider pairs. */
    private int flatShadeDepth = 0;

    /**
     * Whether the one-time AO default has been applied this JVM session.
     *
     * This field is separate from BetaGraphicsMod.isAoDefaultApplied() (which
     * reads the on-disk config flag). The session flag avoids a config-file read
     * on every tick after the default has already been applied in this session.
     * It is initialised to false each JVM start; the first tick that finds the
     * config flag also true will skip the write and set this to true immediately.
     */
    private boolean aoDefaultAppliedThisSession = false;

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
     * Rewrites the world's lightBrightnessTable with Beta's ambient floor.
     *
     * FIX: constant was 0.1F on the assumption that vanilla 1.12.2 uses 0.05F and
     * Beta differs from it. Confirmed against Beta's actual WorldProvider.
     * generateLightBrightnessTable: the formula below is a direct match for
     * Beta's own (down to the (1-d)/(d*3+1) shape), and evaluating it at light
     * level 0 returns exactly the ambient constant -- Beta's is 0.05F, not 0.1F.
     * Whether vanilla 1.12.2 actually differs from that is still unconfirmed;
     * this only corrects the constant to match Beta's confirmed source value.
     */
    public static void patchLightBrightnessTable(World world) {
        final float BETA_AMBIENT = 0.05F;
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

        // ── Smooth lighting default (one-time only) ──────────────────────────
        //
        // On fresh install the config flag is false. We set AO=1 once and then
        // write the flag to disk so subsequent launches skip this entirely.
        // After the first session the player's choice in Video Settings is
        // never touched again.
        //
        // Note: MixinAmbientOcclusionFace neutralises the AO darkening factor
        // for all three AO levels, so all values produce Beta-accurate brightness.
        // The setting only governs vertex interpolation smoothness.
        if (!aoDefaultAppliedThisSession) {
            if (!BetaGraphicsMod.isAoDefaultApplied()) {
                mc.gameSettings.ambientOcclusion = BETA_AO;
                BetaGraphicsMod.markAoDefaultApplied();
                System.out.println("[BetaGraphics] First run: set smooth lighting to Minimum (AO=1).");
            }
            // Whether we just wrote the default or found it already applied,
            // flip the session flag so we never enter this block again this run.
            aoDefaultAppliedThisSession = true;
        }

        // ── Gamma lock (permanent — Beta had no brightness slider) ────────────
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
        if (flatShadeDepth == 0) {
            GL11.glShadeModel(GL11.GL_FLAT);
        }
        flatShadeDepth++;
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onRenderLivingPost(RenderLivingEvent.Post event) {
        flatShadeDepth--;
        if (flatShadeDepth <= 0) {
            flatShadeDepth = 0;
            GL11.glShadeModel(GL11.GL_SMOOTH);
        }
    }

    /** Safety-net restore of GL_SMOOTH, and depth reset, after the full world render pass. */
    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        flatShadeDepth = 0;
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
        queueRebuild(world, origin, lv);
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
        // Note: BreakEvent fires before the block is actually removed, so this
        // can't see the change yet -- vanilla's own setBlockState relight covers
        // the actual value once removal goes through right after this handler
        // returns. Kept for parity with onBlockPlace; queueRebuild() below is
        // what the fix actually depends on.
        world.checkLightFor(EnumSkyBlock.BLOCK, origin);
        for (EnumFacing face : EnumFacing.VALUES) {
            world.checkLightFor(EnumSkyBlock.BLOCK, origin.offset(face));
        }
        queueRebuild(world, origin, lv);
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

    /**
     * Issues a best-effort immediate rebuild against the client's own render
     * world, then queues a delayed one on the shared pendingRebuilds queue.
     *
     * The immediate attempt can run ahead of the server's light recalculation
     * syncing back to the client, in which case it rebuilds with stale data
     * and gets redone by the delayed entry -- but for the common case of the
     * local player's own placement/break it often already has correct data,
     * so it's cheap insurance rather than dead weight. world is still needed
     * here (rather than resolving mc.world for both checks) because
     * world.isRemote is what tells us this fired from actual game logic
     * rather than some other caller.
     */
    private void queueRebuild(World world, BlockPos origin, int lightValue) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.world != null) {
            markLightRange(mc.world, origin, lightValue);
        }
        if (!world.isRemote) {
            pendingRebuilds.add(new PendingRebuild(origin, lightValue, LIGHT_PACKET_WAIT_TICKS));
        }
    }

    private static void markLightRange(World world, BlockPos origin, int lightValue) {
        int r = lightValue + 1;
        world.markBlockRangeForRenderUpdate(
            origin.getX() - r, origin.getY() - r, origin.getZ() - r,
            origin.getX() + r, origin.getY() + r, origin.getZ() + r
        );
    }
}
