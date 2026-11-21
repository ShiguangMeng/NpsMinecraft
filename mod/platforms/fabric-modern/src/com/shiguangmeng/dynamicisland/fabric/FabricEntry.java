package com.shiguangmeng.dynamicisland.fabric;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Fabric entry point for Minecraft 26.x and newer.
 *
 * <p>26.x ships unobfuscated names and renamed the key binding API module from
 * {@code fabric-key-binding-api-v1} to {@code fabric-key-mapping-api-v1}, therefore the modern
 * entry point registers through {@link KeyMappingHelper} instead of {@code KeyBindingHelper}.
 */
public final class FabricEntry implements ClientModInitializer {

    /** GLFW_KEY_G; kept as a literal so the build does not need a compile-time LWJGL dependency. */
    private static final int ANNOUNCE_KEY = 71;

    @Override
    public void onInitializeClient() {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        KeyMapping announce = Compat.get().createKeyMapping("key.dynamicisland.announce", ANNOUNCE_KEY);
        KeyMappingHelper.registerKeyMapping(announce);
        IslandCore.init(announce);
        ClientTickEvents.END_CLIENT_TICK.register(IslandCore::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> IslandCore.shutdown());
    }
}
