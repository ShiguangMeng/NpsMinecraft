package com.shiguangmeng.dynamicisland;

import com.mojang.blaze3d.platform.InputConstants;
import com.shiguangmeng.dynamicisland.bridge.IslandBridge;
import com.shiguangmeng.dynamicisland.track.MiningTracker;
import com.shiguangmeng.dynamicisland.track.PlacementTracker;
import com.shiguangmeng.dynamicisland.track.SlotTracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口：采集游戏内状态并推送给 Windows 侧的 NotchPeninsula 插件。
 *
 * <p>本模组不在游戏内绘制任何界面 —— 动态岛只呈现在 Windows 桌面上，
 * 游戏端只负责三件事：跟踪挖掘进度、方块放置余量与当前选中物品，
 * 并通过本地回环把状态发出去。</p>
 *
 * <p>事件自动触发是主路径；按键 <c>G</c> 是补充手段，用于在没有事件时
 * 手动把当前手持物品唤出到岛上。</p>
 */
public final class DynamicIslandMod implements ClientModInitializer {

    /** 模组标识，同时用作资源包命名空间。 */
    public static final String MOD_ID = "dynamicisland";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 心跳间隔（tick）。
     *
     * <p>必须明显小于插件的断连判定窗口（8 秒），否则岛上会在「已连接」与
     * 「未连接」之间周期性来回切换 —— 40 tick（约 2 秒）留出了 4 倍余量。</p>
     */
    private static final int PING_INTERVAL_TICKS = 40;

    /**
     * 单次 tick 处理的告警阈值（纳秒）。
     *
     * <p>4 ms 已经是一个 60 FPS 帧预算的四分之一，超过它就足以被眼睛察觉。
     * 这条线同时是本模组的自证：日志里没有这行，说明卡顿不是本模组造成的。</p>
     */
    private static final long STALL_NANOS = 4_000_000L;

    /** 告警汇总的静默期：这段时间内再多抖动也只报一行，避免日志本身变成负担。 */
    private static final long STALL_REPORT_INTERVAL_MS = 10_000L;

    private static KeyMapping.Category keyCategory;
    private static KeyMapping announceKey;
    private static int tickCounter;
    private static boolean inWorld;

    private static long stallCount;
    private static long stallMaxNanos;
    private static long lastStallReportMs;

    @Override
    public void onInitializeClient() {
        IslandBridge.init();

        keyCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        announceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dynamicisland.announce",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                keyCategory));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long startedAt = System.nanoTime();
            try {
                tick(client);
            } finally {
                noteTickCost(System.nanoTime() - startedAt);
            }
        });

        // 退出游戏时主动收尾：让插件立刻知道该收起，而不是等 8 秒心跳超时
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MiningTracker.stop();
            PlacementTracker.reset();
            SlotTracker.reset();
            IslandBridge.sendIdle();
            IslandBridge.close();
        });

        LOGGER.info("[DynamicIsland] 客户端入口已初始化");
    }

    private static void tick(Minecraft client) {
        while (announceKey.consumeClick()) {
            if (client.player != null) {
                SlotTracker.announce(client.player);
            }
        }

        // 未进入世界时清掉残留状态并告知插件收起，避免退出世界后岛上还挂着上一份内容
        if (client.level == null) {
            if (inWorld) {
                inWorld = false;
                MiningTracker.stop();
                PlacementTracker.reset();
                SlotTracker.reset();
                IslandBridge.sendIdle();
            }
            return;
        }
        inWorld = true;

        MiningTracker.tick(client);
        PlacementTracker.tick(client);
        SlotTracker.tick(client);

        if (++tickCounter >= PING_INTERVAL_TICKS) {
            tickCounter = 0;
            IslandBridge.sendPing();
        }
    }

    /**
     * 记录本模组这一 tick 的处理耗时。
     *
     * <p>只统计自己的三个 tracker 与一条心跳，不含游戏本体与渲染 ——
     * 因此这行日志是一条清晰的归因依据：它出现 = 本模组在拖帧，它不出现 = 卡顿另有来源。</p>
     *
     * <p>稳态下这段代码只多出两次 {@code nanoTime}（约百纳秒级），不参与任何分配。</p>
     */
    private static void noteTickCost(long nanos) {
        if (nanos < STALL_NANOS) {
            return;
        }

        stallCount++;
        if (nanos > stallMaxNanos) {
            stallMaxNanos = nanos;
        }

        long now = System.currentTimeMillis();
        if (lastStallReportMs != 0L && now - lastStallReportMs < STALL_REPORT_INTERVAL_MS) {
            return;
        }

        LOGGER.info("[DynamicIsland] 本模组 tick 处理耗时异常：{} 次超过 {} ms，最长 {} ms",
                stallCount, STALL_NANOS / 1_000_000L, stallMaxNanos / 1_000_000L);

        stallCount = 0;
        stallMaxNanos = 0;
        lastStallReportMs = now;
    }
}
