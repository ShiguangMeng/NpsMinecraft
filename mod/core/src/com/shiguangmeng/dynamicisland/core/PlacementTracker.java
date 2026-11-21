package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 方块放置余量：手持方块放下一个时，把背包里同类方块的总数推过去。
 * <p>判定用两条线索：按键刚按下，或手上数量确实减少了。只看按键会在对空右键时误报。
 */
public final class PlacementTracker {
    private static Item lastItem;
    private static int lastCount;
    private static boolean lastUsing;

    private PlacementTracker() {
    }

    public static void tick(Minecraft client) {
        Player player = client.player;
        if (player == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        Item item = held.getItem();
        int count = held.getCount();
        boolean using = client.options.keyUse.isDown();
        boolean aimingBlock = client.hitResult instanceof BlockHitResult;

        if (item instanceof BlockItem && aimingBlock) {
            boolean pressedNow = using && !lastUsing;
            boolean consumed = item == lastItem && count < lastCount && using;
            if (pressedNow || consumed) {
                ItemInfo.Durability durability = ItemInfo.durabilityOf(held);
                IslandBridge.sendPlaced(
                        ItemInfo.countInInventory(player, item),
                        ItemInfo.displayName(held),
                        ItemInfo.mapColorOf(item),
                        ItemIcon.take(item),
                        durability == null ? -1 : durability.remaining,
                        durability == null ? -1 : durability.max);
            }
        }

        lastUsing = using;
        lastItem = item;
        lastCount = count;
    }

    public static void reset() {
        lastItem = null;
        lastCount = 0;
        lastUsing = false;
    }
}
