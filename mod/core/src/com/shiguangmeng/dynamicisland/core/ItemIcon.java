package com.shiguangmeng.dynamicisland.core;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.item.Item;

/**
 * 物品图标缓存。
 * <p>图标是 Base64 编码的 PNG，体量比报文本身大一个数量级，所以：
 * <ul>
 *   <li>同一物品 3 秒内不重复携带图标，让插件沿用缓存；</li>
 *   <li>编码后超过 1100 字符的图标直接丢弃，宁可回退到方块默认色；</li>
 *   <li>读盘与 Base64 全在后台线程，游戏线程只查表。</li>
 * </ul>
 */
public final class ItemIcon {
    static final long REPEAT_INTERVAL_MS = 3000L;
    static final int MaxEncodedLength = 1100;
    static final int MaxEntries = 512;
    static final int MaxQueued = 64;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(MaxQueued);
    private static final AtomicLong READY_VERSION = new AtomicLong();

    private static volatile Thread worker;
    private static Log log = Log.NOOP;

    private ItemIcon() {
    }

    public static void setLog(Log sink) {
        log = sink == null ? Log.NOOP : sink;
    }

    /** 缓存版本号：每次有新图标就位就 +1，供 SlotTracker 判断是否需要补发。 */
    public static long readyVersion() {
        return READY_VERSION.get();
    }

    /**
     * 取图标 Base64。返回 {@code null} 有两种含义：本次被节流，或该物品没有可用纹理。
     * <p>调用方不能把 {@code null} 当成「没有图标」写空串——协议里空图标段本身就承载这两种语义，
     * 插件会按同名缓存回退，这个歧义是设计的一部分。
     */
    public static String take(Item item) {
        try {
            Entry entry = entryFor(item);
            if (entry == null) {
                return null;
            }
            String encoded = entry.encoded;
            if (encoded == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - entry.sentAtMs < REPEAT_INTERVAL_MS) {
                return null;
            }
            entry.sentAtMs = now;
            return encoded;
        } catch (Throwable error) {
            return null;
        }
    }

    private static Entry entryFor(Item item) {
        if (item == null) {
            return null;
        }
        String registryId;
        try {
            registryId = Compat.get().idOfItem(item);
        } catch (Throwable error) {
            return null;
        }
        if (registryId == null || registryId.isEmpty()) {
            return null;
        }
        Entry existing = CACHE.get(registryId);
        if (existing != null) {
            return existing;
        }
        if (CACHE.size() >= MaxEntries) {
            return null;
        }
        Entry created = new Entry();
        Entry previous = CACHE.putIfAbsent(registryId, created);
        if (previous != null) {
            return previous;
        }
        if (QUEUE.offer(registryId)) {
            ensureWorker();
        }
        return created;
    }

    private static void ensureWorker() {
        Thread existing = worker;
        if (existing != null && existing.isAlive()) {
            return;
        }
        synchronized (ItemIcon.class) {
            existing = worker;
            if (existing != null && existing.isAlive()) {
                return;
            }
            Thread thread = new Thread(ItemIcon::pump, "DynamicIsland.IconLoader");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            worker = thread;
            thread.start();
        }
    }

    private static void pump() {
        while (true) {
            String registryId;
            try {
                registryId = QUEUE.poll(1L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (registryId == null) {
                // 队列空了就退出，下次有新物品再拉起；常驻线程没必要
                synchronized (ItemIcon.class) {
                    if (QUEUE.isEmpty()) {
                        worker = null;
                        return;
                    }
                }
                continue;
            }
            try {
                String encoded = load(registryId);
                Entry entry = CACHE.get(registryId);
                if (entry == null) {
                    continue;
                }
                // 先写内容再置 ready，读方看到 ready 时内容一定已就位
                entry.encoded = encoded;
                entry.ready = true;
                READY_VERSION.incrementAndGet();
            } catch (Throwable error) {
                log.debug("[DynamicIsland] 图标加载失败：" + registryId + " " + error);
            }
        }
    }

    private static String load(String registryId) {
        Compat compat = Compat.get();
        String encoded = encode(compat.readTexture(registryId, "block"));
        if (encoded != null) {
            return encoded;
        }
        return encode(compat.readTexture(registryId, "item"));
    }

    private static String encode(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(png);
        return base64.length() > MaxEncodedLength ? null : base64;
    }

    static final class Entry {
        /** 后台线程写完才置 ready，读方靠 ready 判断内容是否可用。 */
        volatile String encoded;
        volatile boolean ready;
        long sentAtMs;
    }
}
