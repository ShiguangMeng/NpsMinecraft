package com.shiguangmeng.dynamicisland.forge;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.19.2 ~ 1.21.5 平台层（eventbus 6 世代：IEventBus + RegisterKeyMappingsEvent）。
 *
 * <p>1.20.4 起 ClientTickEvent 多了 Pre / Post 子类，但父类仍在、{@code phase} 字段也仍在，
 * 所以只监听父类并只认 END 即可，不必为 1.20.4 再拆源码。
 */
@Mod(IslandCore.MOD_ID)
public final class ForgeEntry {
    private static final int ANNOUNCE_KEY = 71;

    private KeyMapping announceKey;

    public ForgeEntry() {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onShutdown);
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
