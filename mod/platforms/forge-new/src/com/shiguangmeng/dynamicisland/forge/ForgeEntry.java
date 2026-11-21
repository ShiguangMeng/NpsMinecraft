package com.shiguangmeng.dynamicisland.forge;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Forge 1.21.8 / 1.21.11 / 26.3 平台层（eventbus 7 世代：BusGroup + 静态 BUS）。
 *
 * <p>这三版的 {@code RegisterKeyMappingsEvent} 入口不一致：1.21.8 只有 {@code getBus(BusGroup)}，
 * 1.21.11 两者都有，26.3 只剩静态字段 {@code BUS} —— 用反射兼容，省一份源码。
 */
@Mod(IslandCore.MOD_ID)
public final class ForgeEntry {
    private static final int ANNOUNCE_KEY = 71;

    private static volatile KeyMapping announceKey;

    public ForgeEntry() {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        BusGroup group = FMLJavaModLoadingContext.get().getModBusGroup();
        listenRegisterKeys(group);
        TickEvent.ClientTickEvent.Pre.BUS.addListener(ForgeEntry::onClientTick);
        GameShuttingDownEvent.BUS.addListener(ForgeEntry::onShutdown);
    }

    @SuppressWarnings("unchecked")
    private static void listenRegisterKeys(BusGroup group) {
        Consumer<RegisterKeyMappingsEvent> listener = ForgeEntry::onRegisterKeys;
        try {
            Method getBus = RegisterKeyMappingsEvent.class.getMethod("getBus", BusGroup.class);
            EventBus<RegisterKeyMappingsEvent> bus = (EventBus<RegisterKeyMappingsEvent>) getBus.invoke(null, group);
            bus.addListener(listener);
            return;
        } catch (NoSuchMethodException ignored) {
            // 26.3 删掉了 getBus，只剩静态 BUS
        } catch (Throwable ignored) {
            return;
        }
        try {
            Field field = RegisterKeyMappingsEvent.class.getField("BUS");
            EventBus<RegisterKeyMappingsEvent> bus = (EventBus<RegisterKeyMappingsEvent>) field.get(null);
            bus.addListener(listener);
        } catch (Throwable ignored) {
        }
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        KeyMapping key = Compat.get().createKeyMapping("key.dynamicisland.announce", ANNOUNCE_KEY);
        announceKey = key;
        event.register(key);
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Pre event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        // 按键注册事件与 FMLClientSetupEvent 的先后顺序在各版本不完全一致，
        // 这里做一次懒初始化兜底，避免漏掉 IslandBridge.init()。
        ensureInit();
        IslandCore.tick(client);
    }

    private static void onShutdown(GameShuttingDownEvent event) {
        IslandCore.shutdown();
    }

    private static volatile boolean initialized;

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        IslandCore.init(announceKey);
    }
}
