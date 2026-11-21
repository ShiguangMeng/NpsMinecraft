package com.shiguangmeng.dynamicisland.forge;

import com.shiguangmeng.dynamicisland.core.Compat;
import com.shiguangmeng.dynamicisland.core.IslandCore;
import com.shiguangmeng.dynamicisland.platform.CompatBootstrap;
import com.shiguangmeng.dynamicisland.platform.Slf4jLog;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Method;

/**
 * Forge 1.16.5 / 1.18.2 平台层。
 *
 * <p>这一代 Forge 还没有 {@code RegisterKeyMappingsEvent} 与 {@code GameShuttingDownEvent}：
 * 按键必须走 {@code ClientRegistry.registerKeyBinding}，退出只能靠桌面端的断连窗口兜底。
 * 两个版本的 ClientRegistry 包名不同、参数类型也不同（1.16.5 的参数在编译期叫
 * {@code net.minecraft.client.settings.KeyBinding}，1.18.2 叫 {@code net.minecraft.client.KeyMapping}），
 * 所以这里用反射调用，避免为一行代码再拆一份源码。
 */
@Mod(IslandCore.MOD_ID)
public final class ForgeEntry {
    /** GLFW_KEY_G 的字面量，避免编译期依赖 LWJGL。 */
    private static final int ANNOUNCE_KEY = 71;

    public ForgeEntry() {
        IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        CompatBootstrap.install();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        KeyMapping announce = Compat.get().createKeyMapping("key.dynamicisland.announce", ANNOUNCE_KEY);
        registerKeyBinding(announce);
        IslandCore.init(announce);
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        // 1.16.5 / 1.18.2 的 ClientTickEvent 每 tick 只来一次，没有 Pre/Post 之分；
        // 仍然查 phase 是为了让同一份源码在 1.20.4+ 上也不会重复处理。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        IslandCore.tick(client);
    }

    private static void registerKeyBinding(KeyMapping key) {
        if (tryRegistry("net.minecraftforge.client.ClientRegistry", key)) {
            return;
        }
        tryRegistry("net.minecraftforge.fml.client.registry.ClientRegistry", key);
    }

    private static boolean tryRegistry(String className, KeyMapping key) {
        try {
            Class<?> registry = Class.forName(className);
            for (Method method : registry.getMethods()) {
                if (!"registerKeyBinding".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) {
                    continue;
                }
                // 参数类型在两个版本里是同一个类的两个名字，直接传实例即可。
                method.invoke(null, key);
                return true;
            }
        } catch (Throwable error) {
            IslandCore.setLog(new Slf4jLog(IslandCore.MOD_ID, false));
        }
        return false;
    }
}
