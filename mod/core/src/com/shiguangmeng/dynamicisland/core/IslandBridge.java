package com.shiguangmeng.dynamicisland.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 单向 UDP 通道：模组 -> NotchPeninsula 插件。
 * <p>发送路径绝不阻塞游戏线程：调用方只是把已经拼好的字节串塞进有界队列，真正的
 * {@code send} 由一个最低优先级后台线程独占。队列满就丢包——丢一帧进度远好过卡一帧画面。
 */
public final class IslandBridge {
    private static final int QUEUE_CAPACITY = 32;

    private static volatile boolean usable;
    private static DatagramSocket socket;
    private static InetSocketAddress destination;
    private static BlockingQueue<byte[]> queue;
    private static Thread sender;

    private static Log log = Log.NOOP;

    private IslandBridge() {
    }

    public static void setLog(Log sink) {
        log = sink == null ? Log.NOOP : sink;
    }

    public static void init() {
        if (usable) {
            return;
        }
        try {
            DatagramSocket created = new DatagramSocket();
            InetAddress loopback = InetAddress.getLoopbackAddress();
            socket = created;
            destination = new InetSocketAddress(loopback, Protocol.PORT);
            queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            usable = true;

            Thread thread = new Thread(IslandBridge::pump, "DynamicIsland.Sender");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            sender = thread;
            thread.start();
        } catch (Throwable error) {
            usable = false;
            socket = null;
            queue = null;
            log.warn("[DynamicIsland] 联动通道不可用：" + error, error);
        }
    }

    public static void sendMining(float percent, String label, int rgb, String icon) {
        send(Protocol.mining(percent, label, rgb, icon));
    }

    public static void sendPlaced(int remaining, String label, int rgb, String icon, int durRemaining, int durMax) {
        send(Protocol.placed(remaining, label, rgb, icon, durRemaining, durMax));
    }

    public static void sendSlot(int count, String label, int rgb, String icon, int durRemaining, int durMax) {
        send(Protocol.slot(count, label, rgb, icon, durRemaining, durMax));
    }

    public static void sendHeld(int count, String label, int rgb, String icon, int durRemaining, int durMax) {
        send(Protocol.held(count, label, rgb, icon, durRemaining, durMax));
    }

    public static void sendIdle() {
        send(Protocol.idle());
    }

    public static void sendPing() {
        send(Protocol.ping());
    }

    private static void send(String payload) {
        if (!usable) {
            return;
        }
        BlockingQueue<byte[]> sink = queue;
        if (sink == null) {
            return;
        }
        sink.offer(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void pump() {
        DatagramSocket out = socket;
        if (out == null) {
            return;
        }
        BlockingQueue<byte[]> sink = queue;
        if (sink == null) {
            return;
        }
        DatagramPacket packet = new DatagramPacket(new byte[0], 0);
        while (true) {
            byte[] data;
            try {
                data = usable ? sink.poll(500L, TimeUnit.MILLISECONDS) : sink.poll();
            } catch (InterruptedException interrupted) {
                if (!usable) {
                    data = sink.poll();
                } else {
                    continue;
                }
            }
            if (data == null) {
                if (!usable) {
                    return;
                }
                continue;
            }
            try {
                packet.setData(data, 0, data.length);
                packet.setSocketAddress(destination);
                out.send(packet);
            } catch (Throwable error) {
                // 插件没开时端口无人接收，这里会刷屏，只在首次降级处理
                if (usable) {
                    log.debug("[DynamicIsland] 报文发送失败：" + error);
                }
            }
        }
    }

    public static void close() {
        usable = false;
        Thread worker = sender;
        sender = null;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable ignored) {
                // 关闭失败无所谓，进程即将退出
            }
        }
        queue = null;
    }
}
