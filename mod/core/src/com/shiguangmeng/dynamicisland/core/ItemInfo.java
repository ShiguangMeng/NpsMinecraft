package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 从游戏对象里抠出协议需要的字段。
 * <p>所有方法都吞掉异常：这些数据只用于桌面展示，取不到就发空值，绝不能因为某个
 * 资源包里的怪名字把游戏搞崩。
 */
final class ItemInfo {
    private ItemInfo() {
    }

    static final class Durability {
        final int remaining;
        final int max;

        Durability(int remaining, int max) {
            this.remaining = remaining;
            this.max = max;
        }
    }

    static String displayName(ItemStack stack) {
        try {
            return stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString();
        } catch (Throwable error) {
            return "";
        }
    }

    static String blockDisplayName(BlockState state) {
        try {
            return state == null ? "" : state.getBlock().getName().getString();
        } catch (Throwable error) {
            return "";
        }
    }

    static int mapColorOf(BlockState state, Minecraft client, BlockPos pos) {
        try {
            return Compat.get().mapColor(state, client.level, pos);
        } catch (Throwable error) {
            return -1;
        }
    }

    static int mapColorOf(Item item) {
        try {
            if (!(item instanceof BlockItem)) {
                return -1;
            }
            BlockItem blockItem = (BlockItem) item;
            int color = Compat.get().mapColor(blockItem.getBlock().defaultBlockState(), null, null);
            return color;
        } catch (Throwable error) {
            return -1;
        }
    }

    static Durability durabilityOf(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
                return null;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                return null;
            }
            return new Durability(max - stack.getDamageValue(), max);
        } catch (Throwable error) {
            return null;
        }
    }

    static int countInInventory(Player player, Item item) {
        try {
            net.minecraft.world.entity.player.Inventory inventory = Compat.get().inventory(player);
            int size = inventory.getContainerSize();
            int total = 0;
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    total += stack.getCount();
                }
            }
            return total;
        } catch (Throwable error) {
            return 0;
        }
    }
}
