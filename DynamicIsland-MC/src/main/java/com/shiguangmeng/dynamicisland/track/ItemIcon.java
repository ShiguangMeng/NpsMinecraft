package com.shiguangmeng.dynamicisland.track;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 物品图标的取用与编码。
 *
 * <p>读客户端资源包里的纹理 PNG（16×16，实测 150–330 字节），Base64 后随状态报文下发，
 * 由 Windows 侧解码绘制 —— 两端看到的是同一张原版纹理，不需要在插件里自带任何图标资源。</p>
 *
 * <p>方块物品的纹理在 {@code block} 目录，少数（门、床、工具之类）在 {@code item} 目录，
 * 取用时先试前者、再试后者。</p>
 *
 * <p>★ 为什么必须异步 ★
 * 读取纹理要打开资源包、解压 zip 条目，是一次磁盘 I/O。它原先发生在客户端 tick 里，
 * 也就是**渲染线程**上：第一次遇到某个物品时，这一次读盘就足够让画面卡一帧 ——
 * 换物品、挖新方块时那种「偶发一顿」正是它。现在 tick 线程只查内存缓存，
 * 未命中就登记一次请求并返回「暂无图标」，由后台线程读盘并编码。</p>
 *
 * <p>★ 节流 ★ 图标是报文体积的大头，而挖掘时每秒会发多条报文。
 * 同一个物品的图标在 {@link #REPEAT_INTERVAL_MS} 内只随报文重发一次，
 * 其余报文省略图标段、由 Windows 侧沿用同一物品的缓存位图 ——
 * 这既压住了带宽，也省掉了插件侧每秒重复解码同一张 PNG 的开销。
 * 周期性重发是为了让后启动的插件也能在几秒内补齐缓存。</p>
 */
final class ItemIcon {

    /** 同一物品的图标重发间隔。 */
    private static final long REPEAT_INTERVAL_MS = 3000L;

    /** 单张纹理 Base64 的长度上限，超过说明不是常规 16×16 小图，直接放弃。 */
    private static final int MaxEncodedLength = 1100;

    /** 缓存条数上限。物品类型再多也就几百，超出后不再新增（避免极小概率的无限增长）。 */
    private static final int MaxEntries = 512;

    /** 待读列表上限：满了就丢弃新请求，下次遇到这个物品会重新排队。 */
    private static final int MaxQueued = 64;

    private static final class Entry {
        /** 该物品的纹理 Base64；{@code null} 且 {@link #ready} 为真表示这个物品取不到纹理。 */
        volatile String encoded;

        /** 读盘已经结束（无论成败）。 */
        volatile boolean ready;

        /** 上次随报文发出的时刻，只由客户端线程读写。 */
        long sentAtMs;
    }

    private static final Map<Identifier, Entry> Cache = new ConcurrentHashMap<>();
    private static final BlockingQueue<Identifier> Queue = new LinkedBlockingQueue<>(MaxQueued);

    /** 每就绪一张图标自增一次：调用方据此补发一次报文，让图标尽快落到岛上。 */
    private static final AtomicLong ReadyVersion = new AtomicLong();

    private static Thread worker;

    private ItemIcon() {
    }

    /** 已就绪的图标数量版本号，只增不减。 */
    static long readyVersion() {
        return ReadyVersion.get();
    }

    /**
     * 取本次应当随报文下发的图标。
     *
     * <p>返回 {@code null} 有三种含义，对调用方是一样的：这个物品取不到纹理、
     * 图标还在后台读、或者刚发过、这次省略。</p>
     */
    static String take(Item item) {
        Entry entry = entryFor(item);
        if (entry == null) {
            return null;
        }

        String encoded = entry.encoded;
        if (encoded == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (entry.sentAtMs != 0L && now - entry.sentAtMs < REPEAT_INTERVAL_MS) {
            return null;
        }

        entry.sentAtMs = now;
        return encoded;
    }

    /** 取（必要时登记）某个物品的缓存条目；拿不到注册名或缓存已满时返回 {@code null}。 */
    private static Entry entryFor(Item item) {
        if (item == null) {
            return null;
        }

        Identifier registryId;
        try {
            registryId = BuiltInRegistries.ITEM.getKey(item);
        } catch (Throwable t) {
            return null;
        }
        if (registryId == null) {
            return null;
        }

        Entry entry = Cache.get(registryId);
        if (entry != null) {
            return entry;
        }
        if (Cache.size() >= MaxEntries) {
            return null;
        }

        Entry created = new Entry();
        Entry previous = Cache.putIfAbsent(registryId, created);
        if (previous != null) {
            return previous;
        }

        // 队列满说明积压，直接放弃这次登记：下次遇到同一物品会再来一次
        if (Queue.offer(registryId)) {
            ensureWorker();
        }
        return created;
    }

    private static void ensureWorker() {
        if (worker != null) {
            return;
        }
        synchronized (ItemIcon.class) {
            if (worker != null) {
                return;
            }
            Thread thread = new Thread(ItemIcon::pump, "DynamicIsland.IconLoader");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
            worker = thread;
        }
    }

    /** 后台读盘线程：这里才允许出现资源包 I/O 与 Base64 编码。 */
    private static void pump() {
        while (true) {
            Identifier registryId;
            try {
                registryId = Queue.poll(1L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (registryId == null) {
                continue;
            }

            Entry entry = Cache.get(registryId);
            if (entry == null || entry.ready) {
                continue;
            }

            String encoded;
            try {
                encoded = load(registryId);
            } catch (Throwable t) {
                encoded = null;
            }

            // 先写内容再置标志：客户端线程看到 ready 时 encoded 必然已经可见
            entry.encoded = encoded;
            entry.ready = true;
            ReadyVersion.incrementAndGet();
        }
    }

    private static String load(Identifier registryId) {
        String encoded = encode(read(registryId, "block"));
        if (encoded == null) {
            encoded = encode(read(registryId, "item"));
        }
        return encoded;
    }

    private static String encode(byte[] png) {
        if (png == null) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(png);
        return base64.length() > MaxEncodedLength ? null : base64;
    }

    private static byte[] read(Identifier registryId, String folder) {
        Identifier textureId = Identifier.fromNamespaceAndPath(
                registryId.getNamespace(),
                "textures/" + folder + "/" + registryId.getPath() + ".png");

        try {
            var stack = Minecraft.getInstance().getResourceManager().getResourceStack(textureId);
            if (stack.isEmpty()) {
                return null;
            }
            try (InputStream in = stack.get(0).open()) {
                return in.readAllBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
