package com.shiguangmeng.dynamicisland.bridge;

import com.shiguangmeng.dynamicisland.DynamicIslandMod;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 通往 Windows 侧 NotchPeninsula 插件的单向通道。
 *
 * <p>走本地回环 UDP：无连接、丢一帧也无所谓（下一 tick 会有新状态），因此不需要重传与握手。</p>
 *
 * <p>状态报文固定八段，缺失的可选段写成空串 —— 定长分段让插件侧按固定下标取值，
 * 不必写一堆「第几段存在吗」的分支，空段的代价只有一字节。</p>
 *
 * <pre>
 * DI|TYPE|数值|名称|RRGGBB|图标|耐久当前|耐久上限
 * </pre>
 *
 * <p>★ 为什么要一条发送线程 ★
 * 客户端 tick 跑在渲染线程上，任何一次系统调用都在赌它的延迟：回环 UDP 通常几微秒，
 * 但 Windows 过滤平台（杀软 / 防火墙对回环的检查）偶尔会把 {@code send} 拖到几毫秒 ——
 * 那一刻就是画面上的一个顿挫。因此 tick 线程只做「拼字符串 + 入队」，
 * 真正的 {@code send} 全部交给后台线程；队列满时**丢新报文**而不是阻塞游戏。</p>
 */
public final class IslandBridge {

    /** 与插件侧 {@code IslandLinkServer.DefaultPort} 保持一致。 */
    public static final int PORT = 25585;

    private static final String PREFIX = "DI";

    /**
     * 待发队列长度。
     *
     * <p>正常稳态只有 0~1 条在途；持续积压说明接收端没在消费（插件未开、端口换了），
     * 此时丢新报文比让游戏线程等着更有价值 —— UDP 本来就没有投递保证。</p>
     */
    private static final int QUEUE_CAPACITY = 32;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static volatile boolean usable;

    private static DatagramSocket socket;
    private static InetSocketAddress destination;
    private static BlockingQueue<byte[]> queue;
    private static Thread sender;

    private IslandBridge() {
    }

    /** 建立回环 socket 与发送线程；失败时整条通道静默降级。 */
    public static void init() {
        try {
            socket = new DatagramSocket();
            InetAddress loopback = InetAddress.getLoopbackAddress();
            destination = new InetSocketAddress(loopback, PORT);
            queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            usable = true;

            sender = new Thread(IslandBridge::pump, "DynamicIsland.Sender");
            sender.setDaemon(true);
            sender.setPriority(Thread.MIN_PRIORITY);
            sender.start();
        } catch (Exception e) {
            usable = false;
            socket = null;
            queue = null;
            DynamicIslandMod.LOGGER.warn("[DynamicIsland] 联动通道不可用：{}", e.toString());
        }
    }

    /** 挖掘进度；{@code percent} 为 0-100。 */
    public static void sendMining(float percent, String label, int rgb, String icon) {
        send(compose("MINE", null, percent,
                label, rgb, icon, -1, -1));
    }

    /** 放置方块后的背包余量。 */
    public static void sendPlaced(int remaining, String label, int rgb,
                                  String icon, int durRemaining, int durMax) {
        send(compose("PLACE", Integer.toString(remaining), -1f,
                label, rgb, icon, durRemaining, durMax));
    }

    /** 当前选中的物品：滚轮换物品或按 G 手动唤出时发出。 */
    public static void sendSlot(int count, String label, int rgb,
                                String icon, int durRemaining, int durMax) {
        send(compose("SLOT", Integer.toString(count), -1f,
                label, rgb, icon, durRemaining, durMax));
    }

    /**
     * 当前手持物品：只用于更新 Windows 侧待机态的那个图标，不会让岛上展开卡片。
     *
     * <p>没有它，插件在待机时就不知道玩家手里拿的是什么。</p>
     */
    public static void sendHeld(int count, String label, int rgb,
                                String icon, int durRemaining, int durMax) {
        send(compose("HELD", Integer.toString(count), -1f,
                label, rgb, icon, durRemaining, durMax));
    }

    /** 回到待机。 */
    public static void sendIdle() {
        send(PREFIX + "|IDLE");
    }

    /** 心跳：只用来维持「已连接」判定。 */
    public static void sendPing() {
        send(PREFIX + "|PING");
    }

    /**
     * 关闭通道：先让发送线程把已入队的报文吐完（例如退出世界那条 IDLE），再释放 socket。
     * 最多等 200 ms，不值得为了几条 UDP 报文拖住退出流程。
     */
    public static void close() {
        usable = false;

        Thread worker = sender;
        sender = null;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关闭失败无需处理，进程退出时由系统回收
            }
            socket = null;
        }
        queue = null;
    }

    /** 后台发送线程：独占 {@code send} 这个系统调用，游戏线程永不阻塞在这里。 */
    private static void pump() {
        DatagramPacket packet = new DatagramPacket(new byte[0], 0);
        BlockingQueue<byte[]> sink = queue;
        if (sink == null) {
            return;
        }

        while (true) {
            byte[] data;
            try {
                // 通道可用时等 500 ms 醒一次；关闭后转为非阻塞，把残余报文吐完即退出
                data = usable ? sink.poll(500L, TimeUnit.MILLISECONDS) : sink.poll();
            } catch (InterruptedException e) {
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

            DatagramSocket out = socket;
            if (out == null) {
                return;
            }

            try {
                packet.setData(data, 0, data.length);
                packet.setSocketAddress(destination);
                out.send(packet);
            } catch (Exception ignored) {
                // 单次发送失败不影响后续：UDP 本身无可靠投递保证
            }
        }
    }

    /**
     * 拼一条报文。整条路径刻意不用 {@code String.format} ——
     * {@code Formatter} 每次都要解析格式串并新建若干临时对象，而挖掘时这条路径每秒走十次。
     */
    private static String compose(String type, String value, float percent, String label, int rgb,
                                  String icon, int durRemaining, int durMax) {
        StringBuilder builder = new StringBuilder(icon == null ? 96 : icon.length() + 64);

        builder.append(PREFIX).append('|').append(type).append('|');
        if (value != null) {
            builder.append(value);
        } else {
            appendPercent(builder, percent);
        }

        builder.append('|').append(sanitize(label)).append('|');
        appendHex(builder, rgb & 0xFFFFFF);
        builder.append('|').append(icon == null ? "" : icon).append('|')
                .append(durRemaining < 0 ? "" : Integer.toString(durRemaining)).append('|')
                .append(durMax < 0 ? "" : Integer.toString(durMax));

        return builder.toString();
    }

    /** 一位小数的百分比，与插件侧 {@code float.TryParse} 的解析口径一致。 */
    private static void appendPercent(StringBuilder builder, float percent) {
        int tenths = Math.round(percent * 10.0f);
        builder.append(tenths / 10).append('.').append(Math.abs(tenths % 10));
    }

    /** 六位大写十六进制，等价于 {@code "%06X"}。 */
    private static void appendHex(StringBuilder builder, int rgb) {
        for (int shift = 20; shift >= 0; shift -= 4) {
            builder.append(HEX[(rgb >>> shift) & 0xF]);
        }
    }

    private static void send(String payload) {
        if (!usable) {
            return;
        }

        BlockingQueue<byte[]> sink = queue;
        if (sink == null) {
            return;
        }

        // 队列满 = 接收端积压，直接丢掉这条：阻塞游戏线程换一条 UDP 报文不划算
        sink.offer(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 物品显示名可能含分隔符或换行，落进协议前统一替换成本身安全的字符。 */
    private static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 绝大多数名称是干净的，先探一次再决定要不要走替换（每次替换都会新建字符串）
        if (text.indexOf('|') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
