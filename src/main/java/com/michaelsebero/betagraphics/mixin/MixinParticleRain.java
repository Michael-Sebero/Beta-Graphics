package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaRainHelper;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRain;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores Beta 1.7.3b's EntityRainFX physics and appearance onto 1.12.2's
 * ParticleRain. Full behaviour lives in BetaRainHelper; see that class for
 * the constant-by-constant Beta source mapping.
 *
 * One naming adaptation from a direct port, confirmed against Forge's
 * Particle (1.12.2-14.23.5.2854) javadoc after the first build caught two
 * wrong guesses (moveEntity -> move, isCollided -> onGround -- onGround
 * turned out to carry over from Beta unchanged, no substitution needed):
 *
 *   Age tracking -- Beta decrements a single particleMaxAge counter and
 *   checks <= 0 each tick. 1.12.2 keeps particleAge (incrementing) and
 *   particleMaxAge (fixed cap) as separate fields, so this sets
 *   particleMaxAge once at construction to Beta's computed value, then
 *   increments particleAge each tick and compares -- mutating particleMaxAge
 *   itself the way Beta does would corrupt a value other vanilla code may
 *   read expecting it to stay constant.
 *
 * Confirmed against the same javadoc: Particle's 4-arg constructor is
 * exactly (World, double, double, double), matching the super() call below.
 *
 * ParticleSplash extends ParticleRain in 1.12.2. This mixin covers
 * ParticleRain itself; whether ParticleSplash needs its own mixin depends on
 * whether it independently overrides onUpdate or the constructor, which
 * isn't confirmed here -- flagged as a follow-up rather than guessed at.
 *
 * remap=true (default) is used throughout, matching the FIX already applied
 * to every other Mixin in this project for this toolchain (RetroFuturaGradle
 * 1.4.1 resolves @Overwrite/@Inject targets against MCP names).
 */
@Mixin(ParticleRain.class)
public abstract class MixinParticleRain extends Particle {

    /** Required because this mixin declares extends Particle. Never called at runtime. */
    protected MixinParticleRain(World worldIn, double x, double y, double z) {
        super(worldIn, x, y, z);
    }

    /**
     * Fires after ParticleRain's constructor completes, overwriting whatever
     * initial state vanilla set with Beta's exact values -- mirrors how
     * Beta's own EntityRainFX constructor overrides values its EntityFX
     * super() call would otherwise have set.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void betaInit(World worldIn, double x, double y, double z, CallbackInfo ci) {
        this.motionX *= 0.3D;
        this.motionZ *= 0.3D;
        this.motionY = BetaRainHelper.computeInitialMotionY(this.rand);

        this.particleRed   = 1.0F;
        this.particleGreen = 1.0F;
        this.particleBlue  = 1.0F;

        this.setSize(BetaRainHelper.SIZE, BetaRainHelper.SIZE);
        this.particleGravity = BetaRainHelper.GRAVITY;
        this.particleMaxAge  = BetaRainHelper.computeMaxAge(this.rand);
        this.particleAge     = 0;

        int[] frame = BetaRainHelper.computeTextureFrame(this.rand);
        this.particleTextureIndexX = frame[0];
        this.particleTextureIndexY = frame[1];
    }

    /**
     * Full replacement for ParticleRain.onUpdate -- direct port of Beta's
     * EntityRainFX.onUpdate, adapted per the age-tracking note above.
     *
     * @reason Restores Beta 1.7.3b's rain-splash physics exactly.
     * @author michaelsebero
     */
    @Overwrite
    public void onUpdate() {
        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;

        this.motionY -= this.particleGravity;
        this.move(this.motionX, this.motionY, this.motionZ);
        this.motionX *= BetaRainHelper.AIR_RESISTANCE;
        this.motionY *= BetaRainHelper.AIR_RESISTANCE;
        this.motionZ *= BetaRainHelper.AIR_RESISTANCE;

        if (++this.particleAge >= this.particleMaxAge) {
            this.setExpired();
            return;
        }

        if (this.onGround) {
            if (this.rand.nextFloat() < BetaRainHelper.GROUND_DEATH_CHANCE) {
                this.setExpired();
                return;
            }
            this.motionX *= BetaRainHelper.GROUND_DAMPING;
            this.motionZ *= BetaRainHelper.GROUND_DAMPING;
        }
    }
}
