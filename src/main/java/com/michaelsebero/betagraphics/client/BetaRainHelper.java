package com.michaelsebero.betagraphics.client;

import java.util.Random;

/**
 * Restores Beta 1.7.3b's rain-splash particle physics and appearance.
 *
 * Beta's EntityRainFX (extends EntityFX, the Entity-based particle system --
 * NOT the unrelated net.minecraft.src.Particle/GuiParticle GUI-overlay pair)
 * is a single, unified particle handling both the falling droplet and its
 * eventual ground behaviour. 1.7.3b has no separate "splash" visual.
 *
 * 1.12.2 splits this into ParticleRain (falling) and ParticleSplash extends
 * ParticleRain (impact) -- exactly the kind of post-Beta visual refinement
 * this mod otherwise avoids. This helper only restores ParticleRain's own
 * behaviour (see MixinParticleRain); whether ParticleSplash needs its own
 * override depends on whether it independently overrides onUpdate, which
 * isn't confirmed -- flagged as a follow-up rather than guessed at.
 *
 * All constants below are ported directly from EntityRainFX's constructor
 * and onUpdate, unchanged:
 *   Initial motion     -- X/Z scaled x0.3, Y = random 0.1-0.3 (the "pop" off
 *                          whatever surface it spawned near).
 *   Gravity             -- 0.06F per tick, subtracted from motionY.
 *   Air resistance      -- all three axes x0.98 per tick, applied after
 *                          gravity and movement.
 *   Ground behaviour     -- once grounded: 50% chance per tick to despawn,
 *                          and X/Z further damped x0.7 (skids to a stop
 *                          rather than continuing to slide indefinitely).
 *   Lifespan            -- (int)(8 / (random*0.8 + 0.2)), roughly 8-40 ticks,
 *                          weighted toward the shorter end.
 *   Colour              -- fixed white (1,1,1) -- no biome/water tint.
 *   Size                -- 0.01 x 0.01 (Beta's setSize call).
 *   Texture variation   -- 4 frames, chosen as (base index 19) + rand(4).
 *
 * KNOWN GAP: Beta indexes a single flat particleTextureIndex (19-22) into
 * its particle sheet. 1.12.2's Particle exposes separate
 * particleTextureIndexX/particleTextureIndexY (grid coordinates), and I
 * don't have confirmation of 1.12.2's actual particles.png grid width, or
 * whether ParticleRain even resolves its sprite that way versus a named
 * TextureAtlasSprite (common for post-1.8 particles). computeTextureFrame()
 * assumes a 16-wide grid, matching Beta's own sheet -- verify against your
 * decompile and adjust RAIN_TEXTURE_GRID_WIDTH if the lookup differs.
 *
 * OMITTED: Beta's despawn-on-submersion check (dies if it sinks below a
 * liquid/solid surface it's inside) isn't reproduced here -- it needs Beta's
 * BlockFluid height/percent-air source, which isn't in the reference set.
 * The onGround + random-despawn path covers the common visible case; revisit
 * if particles are seen persisting inside blocks.
 */
public final class BetaRainHelper {

    private BetaRainHelper() {}

    public static final float GRAVITY            = 0.06F;
    public static final float AIR_RESISTANCE      = 0.98F;
    public static final float GROUND_DAMPING      = 0.7F;
    public static final float GROUND_DEATH_CHANCE = 0.5F;
    public static final float SIZE                = 0.01F;

    private static final int RAIN_TEXTURE_INDEX_BASE   = 19;
    private static final int RAIN_TEXTURE_INDEX_FRAMES  = 4;
    private static final int RAIN_TEXTURE_GRID_WIDTH    = 16; // see KNOWN GAP above

    /** Beta: (int)(8.0D / (Math.random() * 0.8D + 0.2D)) -- roughly 8-40 ticks. */
    public static int computeMaxAge(Random rand) {
        return (int) (8.0D / (rand.nextDouble() * 0.8D + 0.2D));
    }

    /** Beta: (float)Math.random() * 0.2F + 0.1F -- the initial upward "pop". */
    public static float computeInitialMotionY(Random rand) {
        return rand.nextFloat() * 0.2F + 0.1F;
    }

    /**
     * Returns {textureIndexX, textureIndexY} for one of Beta's 4 rain-drop
     * frames (19-22), decomposed into 1.12.2's grid-coordinate scheme.
     */
    public static int[] computeTextureFrame(Random rand) {
        int flat = RAIN_TEXTURE_INDEX_BASE + rand.nextInt(RAIN_TEXTURE_INDEX_FRAMES);
        return new int[] { flat % RAIN_TEXTURE_GRID_WIDTH, flat / RAIN_TEXTURE_GRID_WIDTH };
    }
}
