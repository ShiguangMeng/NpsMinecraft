package com.shiguangmeng.dynamicisland.fabric;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Fabric entry point for Minecraft 1.16.5 - 1.21.11 (intermediary namespace at runtime).
 *
 * <p>The mod only mirrors state to the NotchPeninsula island, it never draws anything in game,
 * so the whole client entry point is: install the version shim, register one key binding,
 * hook the client tick and the shutdown hook.
 */
public final class FabricEntry implements ClientModInitializer {

    /** GLFW_KEY_G; kept as a literal so the build does not need a compile-time LWJGL dependency. */
    private static final int ANNOUNCE_KEY = 71;

    @Override
    public void onInitializeClient() {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        KeyMapping announce = Compat.get().createKeyMapping("key.dynamicisland.announce", ANNOUNCE_KEY);
        KeyBindingHelper.registerKeyBinding(announce);
        IslandCore.init(announce);
        ClientTickEvents.END_CLIENT_TICK.register(IslandCore::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> IslandCore.shutdown());
    }
}
