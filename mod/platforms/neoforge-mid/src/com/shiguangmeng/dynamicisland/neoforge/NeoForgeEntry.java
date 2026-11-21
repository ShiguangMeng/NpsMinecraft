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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * NeoForge 1.20.6 ~ 26.3 平台层。
 *
 * <p>1.20.6 起旧的 {@code event.TickEvent} 被删除，换成
 * {@code client.event.ClientTickEvent$Pre|$Post}。这两个子类不像 Forge
 * 1.21.8+ 那样带静态 BUS，只能通过 {@link IEventBus#addListener} 注册。
 * 构造器注入 {@code IEventBus} 在所有这一代版本都支持。
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

    private void onClientTick(ClientTickEvent.Pre event) {
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
