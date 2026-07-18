package com.michaelsebero.betagraphics.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FML coremod entry point for Beta Graphics.
 *
 * Bootstraps MixinBooter so that the Mixin classes in
 * com.michaelsebero.betagraphics.mixin are applied before any game class loads.
 * All bytecode patches are handled by Mixins; no ASM transformer is needed.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("BetaGraphicsPlugin")
@IFMLLoadingPlugin.SortingIndex(1001)
public class BetaGraphicsPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Nullable
    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) { }

    @Nullable
    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.betagraphics.json");
    }
}
