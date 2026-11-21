package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 挖掘进度。
 * <p>进度不是问游戏要的，而是按「工具速度 / 方块硬度 / 修正系数」自己积分出来的：
 * 原版那条路径没有对外暴露进度，只能复刻它的公式。
 * <p>节流两道：进度变化不足 1% 不发，距上次发送不足 100ms 不发。但挖穿那一帧必须发，
 * 否则桌面端会停在 99%。
 */
public final class MiningTracker {
    static final float MIN_PERCENT_STEP = 1.0f;
    static final long MIN_INTERVAL_MS = 100L;
    static final int COMPLETE_HOLD_TICKS = 5;

    private static BlockPos diggingPos;
    private static float progress;
    private static float lastSentPercent = -1.0f;
    private static long lastSentAtMs;
    private static int holdTicks;

    private static BlockState contentState;
    private static BlockPos contentPos;
    private static String contentLabel = "";
    private static int contentColor = -1;

    private MiningTracker() {
    }

    public static void tick(Minecraft client) {
        if (holdTicks > 0) {
            if (--holdTicks == 0) {
                reset();
                IslandBridge.sendIdle();
            }
            return;
        }
        Player player = client.player;
        if (player == null || client.level == null) {
            stop();
            return;
        }
        if (Compat.get().creativeMode(player)) {
            stop();
            return;
        }
        if (!client.options.keyAttack.isDown() || !(client.hitResult instanceof BlockHitResult)) {
            stop();
            return;
        }
        BlockHitResult hit = (BlockHitResult) client.hitResult;
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) {
            stop();
            return;
        }
        float hardness = state.getDestroySpeed(client.level, pos);
        if (hardness <= 0.0f) {
            stop();
            return;
        }
        if (!pos.equals(diggingPos)) {
            diggingPos = pos.immutable();
            progress = 0.0f;
        }
        progress = Math.min(1.0f, progress + damagePerTick(player, state, hardness));
        if (progress >= 1.0f) {
            push(100.0f, state, client, pos);
            diggingPos = null;
            progress = 0.0f;
            holdTicks = COMPLETE_HOLD_TICKS;
            return;
        }
        push(progress * 100.0f, state, client, pos);
    }

    public static void stop() {
        if (diggingPos == null && progress == 0.0f && holdTicks == 0) {
            return;
        }
        reset();
        IslandBridge.sendIdle();
    }

    public static void reset() {
        diggingPos = null;
        progress = 0.0f;
        holdTicks = 0;
        lastSentPercent = -1.0f;
        lastSentAtMs = 0L;
    }

    private static void push(float percent, BlockState state, Minecraft client, BlockPos pos) {
        boolean finalFrame = percent >= 100.0f;
        if (!finalFrame) {
            if (lastSentPercent >= 0.0f && percent - lastSentPercent < MIN_PERCENT_STEP) {
                return;
            }
            long now = System.currentTimeMillis();
            if (lastSentAtMs != 0L && now - lastSentAtMs < MIN_INTERVAL_MS) {
                return;
            }
            lastSentAtMs = now;
        } else {
            lastSentAtMs = System.currentTimeMillis();
        }
        lastSentPercent = percent;
        // BlockState 是注册期的单例，引用比较就够；位置变化则要重取地图色（同一方块不同位置颜色可能不同）
        if (contentState != state || !pos.equals(contentPos)) {
            contentState = state;
            contentPos = pos.immutable();
            contentLabel = ItemInfo.blockDisplayName(state);
            contentColor = ItemInfo.mapColorOf(state, client, pos);
        }
        IslandBridge.sendMining(percent, contentLabel, contentColor, ItemIcon.take(state.getBlock().asItem()));
    }

    /**
     * 复刻原版的每 tick 破坏量：{@code speed / hardness / 30}（能掉落）或 {@code / 100}（不能掉落）。
     */
    private static float damagePerTick(Player player, BlockState state, float hardness) {
        float speed = player.getDestroySpeed(state);
        boolean canHarvest = player.hasCorrectToolForDrops(state);
        float divisor = canHarvest ? 30.0f : 100.0f;
        return speed / hardness / divisor;
    }
}
