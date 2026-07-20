package com.michaelsebero.betagraphics.mixin;

import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Restores Beta 1.7.3b's lower cloud altitude.
 *
 * UNLIKE the rest of this mod, this one isn't checked against Beta's own
 * source -- I don't have RenderGlobal/WorldProvider in the reference set.
 * It's grounded in general Minecraft version history instead: cloud height
 * was Y 108-112 prior to Beta 1.8, then raised to Y 127 starting with Beta
 * 1.8. 1.7.3b is pre-1.8, so it used the lower range; 1.12.2 has used
 * Y 128 (functionally the same post-1.8 value) for its entire run.
 *
 * 108.0F is used here as the floor of that range, reasoned from 1.12.2's own
 * fancy-cloud volume being about 4 blocks tall (112-108 = 4, the same
 * thickness) -- on the assumption getCloudHeight() returns the floor of the
 * volume, not its center or ceiling. If clouds sit a touch lower than you
 * remember once you're actually looking at them, 112.0F is the other end of
 * the cited range to try instead.
 *
 * Confirmed against Forge-1.12.2-14.23.5.2854's actual RenderGlobal source:
 * cloud height isn't a magic number buried in renderClouds/renderCloudsFancy
 * -- both paths read it once per frame from theWorld.provider.getCloudHeight(),
 * so overriding it here covers fast and fancy clouds alike without touching
 * RenderGlobal at all. WorldProviderHell/WorldProviderEnd aren't a concern:
 * neither dimension renders clouds in the first place.
 *
 * SCOPE: height only, and only a first pass at that -- render style (1.12.2's
 * Fast = flat opaque sheet vs Fancy = translucent 3D prism) and overall
 * shape/size aren't touched here. Those were flagged earlier as open
 * questions, not confirmed as real Beta/1.12.2 differences yet.
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProvider {

    /**
     * @reason Restores Beta 1.7.3b's cloud altitude (pre-Beta 1.8 range,
     *         Y 108-112). 1.12.2 vanilla returns 128.0F here.
     * @author michaelsebero
     */
    @Overwrite
    public float getCloudHeight() {
        return 108.0F;
    }
}
