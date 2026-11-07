package com.shiguangmeng.dynamicisland.track;

import com.shiguangmeng.dynamicisland.bridge.IslandBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 手持物品跟踪：滚轮换物品时把当前物品推给 Windows 侧动态岛。
 *
 * <p>「当前物品」以主手为准，**主手为空时取副手** —— 空手站着时岛上不至于退回默认图标，
 * 副手拿着盾或火把也能照常显示；两手都空才真正回到「无物品」。</p>
 *
 * <p>推送时机有三处：换槽位、按 G 手动唤出、以及**手上物品本身发生变化**
 * （副手捡起东西、主手物品耗尽、按 F 交换等）。最后一种不改变槽位，
 * 只靠槽位检测会让待机图标一直停在旧物品上。</p>
 */
public final class SlotTracker {

    private static int lastSlot = -1;
    private static Item lastShownItem;
    private static long lastIconVersion;

    private SlotTracker() {
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            reset();
            return;
        }

        int slot = player.getInventory().getSelectedSlot();
        if (slot != lastSlot) {
            boolean firstSample = lastSlot < 0;
            lastSlot = slot;
            remember(player);

            if (firstSample) {
                // 进入世界的第一次采样只上报「手上有什么」，用于待机图标，不弹卡片
                announceHeld(player);
            } else {
                announce(player);
            }
            return;
        }

        // 槽位没变但展示的物品换了（副手捡起东西、主手物品耗尽、按 F 交换）：
        // 只刷新待机图标，不打断正在展示的卡片。
        // 只比物品本体、不比数量 —— 待机态只画图标，数量变化（放置方块时每块都变）
        // 不改变岛上的任何像素，为它每 tick 发一条报文纯属浪费。
        ItemStack shown = displayStack(player);
        if (shown.getItem() != lastShownItem) {
            remember(player);
            announceHeld(player);
            return;
        }

        // 图标是后台线程读盘后才有值的：就绪后补发一次，
        // 否则待机态会一直停在 Minecraft 默认图标上，直到下次换物品。
        long iconVersion = ItemIcon.readyVersion();
        if (iconVersion != lastIconVersion) {
            lastIconVersion = iconVersion;
            announceHeld(player);
        }
    }

    /** 手动唤出：按 G 时调用，不看槽位是否变化，直接推一次当前物品。 */
    public static void announce(LocalPlayer player) {
        ItemStack shown = displayStack(player);
        if (shown.isEmpty()) {
            // 两手都空：让插件回到「没有手持物品」的状态，由它显示 Minecraft 图标
            reportEmpty();
            return;
        }

        ItemInfo.Durability durability = ItemInfo.durabilityOf(shown);

        IslandBridge.sendSlot(
                shown.getCount(),
                ItemInfo.displayName(shown),
                ItemInfo.mapColorOf(shown.getItem()),
                ItemIcon.take(shown.getItem()),
                durability == null ? -1 : durability.remaining(),
                durability == null ? -1 : durability.max());
    }

    /** 只更新待机图标，不触发展开。 */
    private static void announceHeld(LocalPlayer player) {
        ItemStack shown = displayStack(player);
        if (shown.isEmpty()) {
            reportEmpty();
            return;
        }

        ItemInfo.Durability durability = ItemInfo.durabilityOf(shown);

        IslandBridge.sendHeld(
                shown.getCount(),
                ItemInfo.displayName(shown),
                ItemInfo.mapColorOf(shown.getItem()),
                ItemIcon.take(shown.getItem()),
                durability == null ? -1 : durability.remaining(),
                durability == null ? -1 : durability.max());
    }

    /**
     * 当前应当展示的物品：优先主手，主手为空时用副手。
     * 两者都为空时返回空栈，插件据此显示 Minecraft 图标。
     */
    private static ItemStack displayStack(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        return main.isEmpty() ? player.getOffhandItem() : main;
    }

    /** 记住本次展示的物品，供「物品本身是否变化」的比对。 */
    private static void remember(LocalPlayer player) {
        ItemStack shown = displayStack(player);
        lastShownItem = shown.getItem();
    }

    /** 两手都空：名称为空、无颜色、无图标，插件据此切换回 Minecraft 图标。 */
    private static void reportEmpty() {
        IslandBridge.sendHeld(0, "", -1, null, -1, -1);
    }

    public static void reset() {
        lastSlot = -1;
        lastShownItem = null;
        lastIconVersion = ItemIcon.readyVersion();
    }
}
