package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

/**
 * 平台层没有注入实现时的兜底：所有能力都退化为「无」，模组不会崩，只是不输出。
 * <p>刻意不调用任何跨版本有差异的 API，保证它自己能在所有版本上编过。
 */
final class UnsupportedCompat implements Compat {
    @Override
    public Inventory inventory(Player player) {
        return null;
    }

    @Override
    public int selectedSlot(Inventory inventory) {
        return 0;
    }

    @Override
    public String idOfItem(Item item) {
        return "";
    }

    @Override
    public int mapColor(BlockState state, Level level, BlockPos pos) {
        return -1;
    }

    @Override
    public boolean creativeMode(Player player) {
        return false;
    }

    @Override
    public byte[] readTexture(String registryId, String folder) {
        return null;
    }

    @Override
    public KeyMapping createKeyMapping(String translationKey, int glfwKey) {
        return null;
    }
}
