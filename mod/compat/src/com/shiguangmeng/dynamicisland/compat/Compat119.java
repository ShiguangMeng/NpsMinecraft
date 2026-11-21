package com.shiguangmeng.dynamicisland.compat;

import com.shiguangmeng.dynamicisland.core.Compat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 1.19.2：纹理改走 getResourceStack + Resource#open()，颜色仍是 MaterialColor。
 */
public final class Compat119 implements Compat {
    @Override
    public Inventory inventory(Player player) {
        return player.getInventory();
    }

    @Override
    public int selectedSlot(Inventory inventory) {
        return inventory.selected;
    }

    @Override
    public String idOfItem(Item item) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.Registry.ITEM.getKey(item);
        return id == null ? "" : id.toString();
    }

    @Override
    public int mapColor(BlockState state, Level level, BlockPos pos) {
        net.minecraft.world.level.material.MaterialColor color = state.getMapColor(level, pos);
        return color == null ? -1 : (color.col & 0xFFFFFF);
    }

    @Override
    public boolean creativeMode(Player player) {
        return player.isCreative();
    }

    @Override
    public byte[] readTexture(String registryId, String folder) {
        String[] parts = Compat.splitId(registryId);
        if (parts == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation id = new net.minecraft.resources.ResourceLocation(parts[0], "textures/" + folder + "/" + parts[1] + ".png");
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (manager == null) {
            return null;
        }
        try {
            List<Resource> resources = manager.getResourceStack(id);
            if (resources == null || resources.isEmpty()) {
                return null;
            }
            Resource resource = resources.get(0);
            InputStream in = resource.open();
            try {
                return Compat.readAll(in);
            } finally {
                Compat.closeQuietly(in);
                closeResource(resource);
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public KeyMapping createKeyMapping(String translationKey, int glfwKey) {
        return new KeyMapping(translationKey, glfwKey, KeyMapping.CATEGORY_MISC);
    }

    private static void closeResource(Resource resource) {
        if (resource instanceof Closeable) {
            Compat.closeQuietly((Closeable) resource);
        }
    }
}
