package com.shiguangmeng.dynamicisland.track;

import com.shiguangmeng.dynamicisland.bridge.IslandBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 方块放置跟踪。
 *
 * <p>两条判定路径并存，各自覆盖一种游戏模式：</p>
 * <ul>
 *   <li><b>使用键按下的瞬间</b>（准星指向方块、手持方块物品）—— 创造模式不消耗物品，
 *       只能靠这个信号；</li>
 *   <li><b>主手数量减少</b>（同时按着使用键）—— 生存模式下按住不放连续放置时，
 *       靠它把每一次消耗都反映出来。</li>
 * </ul>
 *
 * <p>数量按整个背包统计，而不是只看主手：玩家搭桥时方块会从主手耗尽后自动从背包补位，
 * 只看主手会在切换的那一 tick 报出 0，与「背包里还有多少」这个语义不符。</p>
 */
public final class PlacementTracker {

    private static Item lastItem;
    private static int lastCount;
    private static boolean lastUsing;

    private PlacementTracker() {
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            reset();
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
                        durability == null ? -1 : durability.remaining(),
                        durability == null ? -1 : durability.max());
            }
        }

        lastUsing = using;
        lastItem = item;
        lastCount = count;
    }

    /** 离开世界时清掉基线，避免下个世界沿用上一份计数。 */
    public static void reset() {
        lastItem = null;
        lastCount = 0;
        lastUsing = false;
    }
}
