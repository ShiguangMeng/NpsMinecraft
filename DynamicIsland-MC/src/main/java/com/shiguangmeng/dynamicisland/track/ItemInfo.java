package com.shiguangmeng.dynamicisland.track;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块与物品的展示信息：名称、代表色、背包计数、耐久。
 *
 * <p>名称取客户端本地化后的显示名，直接下发给 Windows 侧，插件不做翻译。
 * 颜色取方块的 MapColor，作为拿不到纹理时的自绘立方体配色。耐久只在可损坏物品上提供。</p>
 */
final class ItemInfo {

    /** 耐久信息；不可损坏或上限为 0 时调用方会拿到 null。 */
    record Durability(int remaining, int max) {
    }

    private ItemInfo() {
    }

    /** 物品在客户端语言下的显示名。 */
    static String displayName(ItemStack stack) {
        try {
            return stack.getHoverName().getString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 方块在客户端语言下的显示名。 */
    static String blockDisplayName(BlockState state) {
        try {
            return state.getBlock().getName().getString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 方块在指定位置的代表色（部分方块如草方块会随位置变化）；取不到返回 -1。 */
    static int mapColorOf(BlockState state, Minecraft client, BlockPos pos) {
        try {
            var color = state.getMapColor(client.level, pos);
            return color == null ? -1 : (color.col & 0xFFFFFF);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 物品的代表色：只有方块物品有 MapColor，其余返回 -1。 */
    static int mapColorOf(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return -1;
        }

        try {
            var color = blockItem.getBlock().defaultBlockState().getMapColor(null, null);
            return color == null ? -1 : (color.col & 0xFFFFFF);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 物品的耐久。只有「可损坏且上限大于 0」的物品才有值 ——
     * 工具、武器、盔甲走这条；方块、食物等返回 null，岛上就不画耐久条。
     */
    static Durability durabilityOf(ItemStack stack) {
        try {
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                return null;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                return null;
            }
            return new Durability(max - stack.getDamageValue(), max);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 统计整个背包内该类物品的总数（含主手、快捷栏与背包槽位）。 */
    static int countInInventory(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        int total = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }

        return total;
    }
}
