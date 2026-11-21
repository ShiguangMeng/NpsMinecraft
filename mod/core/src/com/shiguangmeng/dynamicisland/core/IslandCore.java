package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 平台无关的模组主体：各加载器平台层只负责「什么时候调 tick / shutdown」，
 * 真正的采集逻辑全在这里。这样 Fabric、Forge、NeoForge 三份平台层加起来的胶水不到百行。
 */
public final class IslandCore {
    public static final String MOD_ID = "dynamicisland";

    static final int PING_INTERVAL_TICKS = 40;
    static final long STALL_NANOS = 4_000_000L;
    static final long STALL_REPORT_INTERVAL_MS = 10_000L;

    private static KeyMapping announceKey;
    private static int tickCounter;
    private static boolean inWorld;

    private static long stallCount;
    private static long stallMaxNanos;
    private static long lastStallReportMs;

    private static Log log = Log.NOOP;

    private IslandCore() {
    }

    public static void setLog(Log sink) {
        log = sink == null ? Log.NOOP : sink;
        IslandBridge.setLog(log);
        ItemIcon.setLog(log);
    }

    /**
     * 平台层在客户端初始化时调用一次。
     *
     * @param key 公告按键（按一下把当前手持推到桌面卡片）；平台不支持按键时可以为 {@code null}
     */
    public static void init(KeyMapping key) {
        announceKey = key;
        IslandBridge.init();
        log.info("[DynamicIsland] 客户端已接入，游戏状态将镜像到灵动岛");
    }

    /** 客户端每 tick 调一次。 */
    public static void tick(Minecraft client) {
        long startedAt = System.nanoTime();
        try {
            run(client);
        } finally {
            noteTickCost(System.nanoTime() - startedAt);
        }
    }

    private static void run(Minecraft client) {
        KeyMapping key = announceKey;
        if (key != null) {
            while (key.consumeClick()) {
                Player player = client.player;
                if (player != null) {
                    SlotTracker.announce(player);
                }
            }
        }
        if (client.level == null) {
            if (inWorld) {
                inWorld = false;
                MiningTracker.reset();
                PlacementTracker.reset();
                SlotTracker.reset();
                IslandBridge.sendIdle();
            }
            return;
        }
        if (!inWorld) {
            inWorld = true;
            log.info("[DynamicIsland] 已进入世界，开始上报状态");
        }
        MiningTracker.tick(client);
        PlacementTracker.tick(client);
        SlotTracker.tick(client);
        if (++tickCounter >= PING_INTERVAL_TICKS) {
            tickCounter = 0;
            IslandBridge.sendPing();
        }
    }

    /** 客户端关闭时调用：先喊一声 IDLE 再关通道，免得桌面端要等满断连窗口。 */
    public static void shutdown() {
        MiningTracker.reset();
        PlacementTracker.reset();
        SlotTracker.reset();
        IslandBridge.sendIdle();
        IslandBridge.close();
    }

    /**
     * 自检：模组自己在主线程上花的时间一旦超阈值就记一笔。
     * <p>玩家反馈过「装了本模组后卡顿」，但当时没有任何量化证据，这条日志就是为它准备的。
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
        if (now - lastStallReportMs < STALL_REPORT_INTERVAL_MS) {
            return;
        }
        lastStallReportMs = now;
        log.warn("[DynamicIsland] 本模组 tick 处理耗时异常：" + stallCount + " 次超过 "
                + (STALL_NANOS / 1_000_000L) + " ms，最长 " + (stallMaxNanos / 1_000_000L) + " ms");
        stallCount = 0;
        stallMaxNanos = 0L;
    }
}
