package com.michaelsebero.betagraphics.mixin;

import com.michaelsebero.betagraphics.client.BetaShadowHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin targeting Render to restore Beta 1.7.3b's shadow light threshold.
 *
 * Replaces Render.renderShadow with a version that only draws shadow quads
 * where light > 3, matching the hard cutoff in Beta's Render.java line 118.
 * Delegates all logic to BetaShadowHelper.
 */
@Mixin(Render.class)
public abstract class MixinRender<T extends Entity> {

    /**
     * @reason Restores Beta 1.7.3b Render.java line 118 shadow light cutoff.
     * @author michaelsebero
     */
    @Overwrite
    protected void renderShadow(Entity entity, double x, double y, double z,
            float shadowOpacity, float partialTicks) {
        BetaShadowHelper.renderBetaShadow(
            (Render<?>) (Object) this, entity, x, y, z, shadowOpacity, partialTicks);
    }
}
