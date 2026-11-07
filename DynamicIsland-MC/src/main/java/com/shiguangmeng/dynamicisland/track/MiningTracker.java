package com.shiguangmeng.dynamicisland.track;

import com.shiguangmeng.dynamicisland.bridge.IslandBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 挖掘进度跟踪。
 *
 * <p>不注入游戏内部状态，而是在每个客户端 tick 里用公开 API 复现原版的破坏公式：
 * 每 tick 的进度增量 = 挖掘速度 / 方块硬度 / （能否收获 ? 30 : 100），
 * 累加到 1 即为方块被破坏 —— 这与原版 {@code MultiPlayerGameMode} 的推进方式一致。</p>
 *
 * <p>进度只在「按住攻击键 + 准星指向方块 + 该方块确实存在且有破坏过程」期间推进；
 * 一旦换个方块、松开按键、瞄准落空或目标已消失，立即归零并通知岛上收起。</p>
 *
 * <p>推送有两道闸门：进度变化不足 {@link #MIN_PERCENT_STEP} 个百分点不发，
 * 两次推送间隔不足 {@link #MIN_INTERVAL_MS} 毫秒也不发。
 * 挖掘时每秒有二十个 tick，不设闸门就是每秒二十条报文，而肉眼分辨不出这点差别。</p>
 */
public final class MiningTracker {

    /** 进度变化达到这个百分点才重发。 */
    private static final float MIN_PERCENT_STEP = 1.0f;

    /** 两次推送的最小间隔（毫秒），相当于把推送频率上限压到每秒十条。 */
    private static final long MIN_INTERVAL_MS = 100L;

    /** 方块破坏后把 100% 留在岛上的时长（tick）。 */
    private static final int COMPLETE_HOLD_TICKS = 5;

    private static BlockPos diggingPos;
    private static float progress;
    private static float lastSentPercent = -1.0f;
    private static long lastSentAtMs;
    private static int holdTicks;

    // 当前挖掘方块的展示内容。同一个方块上的名称与 MapColor 不会变，
    // 因此按「方块状态 + 位置」缓存，只有换了目标才重算本地化名称与取色。
    private static BlockState contentState;
    private static BlockPos contentPos;
    private static String contentLabel = "";
    private static int contentColor = -1;

    private MiningTracker() {
    }

    public static void tick(Minecraft client) {
        // 刚破坏完：把 100% 留在岛上几 tick 再收起。
        // 方块碎裂的瞬间就把进度条抹掉会显得突兀。
        if (holdTicks > 0) {
            if (--holdTicks == 0) {
                reset();
                IslandBridge.sendIdle();
            }
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            stop();
            return;
        }

        // 创造模式（有无限材料）的方块是瞬间破坏的，不存在「破坏进度」这一过程。
        if (player.hasInfiniteMaterials()) {
            stop();
            return;
        }

        if (!client.options.keyAttack.isDown() || !(client.hitResult instanceof BlockHitResult hit)) {
            stop();
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);

        // 方块已经不在了：按住攻击键不放时准星继续指向同一个坐标，而那里现在只剩空气。
        // 空气硬度为 0，若照原公式推进就会被当成「瞬间破坏的方块」，
        // 于是每 tick 推一次满进度 —— 这正是「挖完显示空气 100%」与「卡在 100%」的成因。
        if (state.isAir()) {
            stop();
            return;
        }

        float hardness = state.getDestroySpeed(client.level, pos);

        // 硬度为负表示不可破坏（基岩、屏障）；为 0 表示瞬间破坏（草、花、火把）。
        // 两者都没有「破坏进度」可言，因此都不上岛。
        if (hardness <= 0.0f) {
            stop();
            return;
        }

        if (!pos.equals(diggingPos)) {
            diggingPos = pos;
            progress = 0.0f;
        }

        progress = Math.min(1.0f, progress + damagePerTick(player, state, hardness));

        if (progress >= 1.0f) {
            // 方块已破坏：推满最后一帧，留几 tick 展示后再收起
            push(100.0f, state, client, pos);
            diggingPos = null;
            progress = 0.0f;
            holdTicks = COMPLETE_HOLD_TICKS;
            return;
        }

        push(progress * 100.0f, state, client, pos);
    }

    /** 清除挖掘状态并通知岛上收起：挖掘中止、离开世界、目标消失或切换到创造模式时调用。 */
    public static void stop() {
        if (diggingPos == null && progress == 0.0f && holdTicks == 0) {
            return;
        }

        reset();
        IslandBridge.sendIdle();
    }

    /** 清空跟踪状态与推送节流，不产生报文。 */
    private static void reset() {
        diggingPos = null;
        progress = 0.0f;
        holdTicks = 0;
        lastSentPercent = -1.0f;
        lastSentAtMs = 0L;
    }

    private static void push(float percent, BlockState state, Minecraft client, BlockPos pos) {
        boolean finalFrame = percent >= 100.0f;
        long now = System.currentTimeMillis();

        if (!finalFrame) {
            if (lastSentPercent >= 0.0f && percent - lastSentPercent < MIN_PERCENT_STEP) {
                return;
            }
            if (lastSentAtMs != 0L && now - lastSentAtMs < MIN_INTERVAL_MS) {
                return;
            }
        }
        lastSentPercent = percent;
        lastSentAtMs = now;

        // BlockState 在注册表里是单例，引用比较即可判断方块是否换了
        if (contentState != state || !pos.equals(contentPos)) {
            contentState = state;
            contentPos = pos.immutable();
            contentLabel = ItemInfo.blockDisplayName(state);
            contentColor = ItemInfo.mapColorOf(state, client, pos);
        }

        IslandBridge.sendMining(percent, contentLabel, contentColor,
                ItemIcon.take(state.getBlock().asItem()));
    }

    /**
     * 单个 tick 的进度增量。对应原版 `MultiPlayerGameMode.continueDestroyBlock` 里的累加量：
     * 挖掘速度越大越快、硬度越高越慢，工具不适配时效率降到三分之一。
     */
    private static float damagePerTick(LocalPlayer player, BlockState state, float hardness) {
        float speed = player.getDestroySpeed(state);
        boolean canHarvest = player.hasCorrectToolForDrops(state);
        float divisor = canHarvest ? 30.0f : 100.0f;
        return speed / hardness / divisor;
    }
}
