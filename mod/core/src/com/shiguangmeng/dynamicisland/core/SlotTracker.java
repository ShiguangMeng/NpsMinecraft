package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 手持物品。
 * <p>协议区分 SLOT 与 HELD：切快捷栏要展开桌面卡片，物品本体变化只换待机图标。
 * 所以首次采样、切栏、图标异步就绪补发这三种情况走的报文类型并不相同。
 */
public final class SlotTracker {
    private static int lastSlot = -1;
    private static Item lastShownItem;
    private static long lastIconVersion;

    private SlotTracker() {
    }

    public static void tick(Minecraft client) {
        Player player = client.player;
        if (player == null) {
            return;
        }
        Inventory inventory = Compat.get().inventory(player);
        int slot = Compat.get().selectedSlot(inventory);
        if (slot != lastSlot) {
            boolean firstSample = lastSlot < 0;
            lastSlot = slot;
            remember(player);
            if (firstSample) {
                announceHeld(player);
            } else {
                announce(player);
            }
            return;
        }
        ItemStack shown = displayStack(player);
        if (shown.getItem() != lastShownItem) {
            remember(player);
            announceHeld(player);
            return;
        }
        long version = ItemIcon.readyVersion();
        if (version != lastIconVersion) {
            lastIconVersion = version;
            announceHeld(player);
        }
    }

    public static void announce(Player player) {
        try {
            ItemStack shown = displayStack(player);
            Item item = shown.getItem();
            if (item == null || shown.isEmpty()) {
                reportEmpty();
                return;
            }
            ItemInfo.Durability durability = ItemInfo.durabilityOf(shown);
            IslandBridge.sendSlot(
                    shown.getCount(),
                    ItemInfo.displayName(shown),
                    ItemInfo.mapColorOf(item),
                    ItemIcon.take(item),
                    durability == null ? -1 : durability.remaining,
                    durability == null ? -1 : durability.max);
        } catch (Throwable ignored) {
            reportEmpty();
        }
    }

    private static void announceHeld(Player player) {
        try {
            ItemStack shown = displayStack(player);
            Item item = shown.getItem();
            if (item == null || shown.isEmpty()) {
                reportEmpty();
                return;
            }
            ItemInfo.Durability durability = ItemInfo.durabilityOf(shown);
            IslandBridge.sendHeld(
                    shown.getCount(),
                    ItemInfo.displayName(shown),
                    ItemInfo.mapColorOf(item),
                    ItemIcon.take(item),
                    durability == null ? -1 : durability.remaining,
                    durability == null ? -1 : durability.max);
        } catch (Throwable ignored) {
            reportEmpty();
        }
    }

    private static void remember(Player player) {
        lastShownItem = displayStack(player).getItem();
        lastIconVersion = ItemIcon.readyVersion();
    }

    /** 主手优先，空手时用副手——桌面端展示「手上真正拿的东西」。 */
    private static ItemStack displayStack(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main != null && !main.isEmpty()) {
            return main;
        }
        ItemStack offhand = player.getOffhandItem();
        return offhand == null ? ItemStack.EMPTY : offhand;
    }

    private static void reportEmpty() {
        IslandBridge.sendHeld(0, "", -1, null, -1, -1);
    }

    public static void reset() {
        lastSlot = -1;
        lastShownItem = null;
        lastIconVersion = ItemIcon.readyVersion();
    }
}
