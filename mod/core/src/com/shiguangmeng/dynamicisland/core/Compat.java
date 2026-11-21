package com.shiguangmeng.dynamicisland.core;

import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 版本差异收敛点：Minecraft 1.16.5 到 26.3 之间只有这几处 API 真的变了，
 * 全部集中到本接口，由各版本组的 compat 模块实现。
 * <p>core 里的其余代码只使用跨版本稳定的 mojmap API，因此可以一份源码编到所有版本。
 */
public interface Compat {
    /** 当前生效的实现，平台层在初始化时注入。 */
    static Compat get() {
        return Holder.INSTANCE;
    }

    static void install(Compat compat) {
        Holder.INSTANCE = compat;
    }

    /** 玩家背包。1.16.5 没有 {@code getInventory()}，只有 {@code inventory} 字段。 */
    Inventory inventory(Player player);

    /** 当前选中的快捷栏下标。1.21.5 起 {@code Inventory#selected} 变私有，改用 {@code getSelectedSlot()}。 */
    int selectedSlot(Inventory inventory);

    /** 物品注册名，形如 {@code minecraft:stone}。名称类型在 1.21.11 从 ResourceLocation 改成 Identifier。 */
    String idOfItem(Item item);

    /** 方块地图颜色的 RGB。返回类型在 1.20.1 从 MaterialColor 改成 MapColor。 */
    int mapColor(BlockState state, Level level, BlockPos pos);

    /** 创造模式判定。{@code hasInfiniteMaterials()} 只有 1.20.6 起才有。 */
    boolean creativeMode(Player player);

    /**
     * 读取物品或方块的纹理 PNG。
     *
     * @param registryId 形如 {@code minecraft:stone}
     * @param folder     {@code block} 或 {@code item}
     * @return PNG 字节；不存在返回 {@code null}
     */
    byte[] readTexture(String registryId, String folder);

    /**
     * 创建按键映射。分类参数在 1.21.11 从 String 变成 {@code KeyMapping.Category}；
     * 真正的注册动作由平台层负责（各加载器的注册 API 不同）。
     */
    KeyMapping createKeyMapping(String translationKey, int glfwKey);

    /** 把 {@code minecraft:stone} 拆成 {@code ["minecraft", "stone"]}，非法输入返回 {@code null}。 */
    static String[] splitId(String registryId) {
        if (registryId == null) {
            return null;
        }
        int colon = registryId.indexOf(':');
        if (colon <= 0 || colon == registryId.length() - 1) {
            return null;
        }
        return new String[] {registryId.substring(0, colon), registryId.substring(colon + 1)};
    }

    /** 读干一个流。刻意不用 {@code readAllBytes()}，好让 1.16.5 能编成 Java 8 字节码。 */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 关不上就算了，纹理读取失败本身不致命
        }
    }

    final class Holder {
        private static volatile Compat INSTANCE = new UnsupportedCompat();

        private Holder() {
        }
    }
}
