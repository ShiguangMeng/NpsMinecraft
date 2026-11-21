package com.shiguangmeng.dynamicisland.neoforge;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.TickEvent;

/**
 * NeoForge 1.20.4 平台层。
 *
 * <p>这一代还没有 {@code client.event.ClientTickEvent.Pre}，只有
 * {@code event.TickEvent.ClientTickEvent} 加 {@code TickEvent.Phase}，
 * 所以单独一份源码。构造器注入 {@link IEventBus} 在这一代已经支持
 * （{@code FMLModContainer} 的常量池里有 {@code getConstructors} 与
 * {@code net/neoforged/bus/api/IEventBus}），因此不必依赖
 * {@code FMLJavaModLoadingContext}。
 */
@Mod(IslandCore.MOD_ID)
public final class NeoForgeEntry {
    private static final int ANNOUNCE_KEY = 71;

    private KeyMapping announceKey;

    public NeoForgeEntry(IEventBus modBus) {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onShutdown);
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        announceKey = Compat.get().createKeyMapping("key.dynamicisland.announce", ANNOUNCE_KEY);
        event.register(announceKey);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        IslandCore.init(announceKey);
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        IslandCore.tick(client);
    }

    private void onShutdown(GameShuttingDownEvent event) {
        IslandCore.shutdown();
    }
}
