package com.shiguangmeng.dynamicisland.core;

/**
 * 联动报文组装。
 * <p>线路格式（单行 UTF-8，{@code |} 分隔固定八段）：
 * <pre>DI|TYPE|数值|名称|RRGGBB|图标Base64|耐久当前|耐久上限</pre>
 * IDLE / PING 只有两段。
 * <p>这是与 NotchPeninsula 插件之间的契约，任何字符都不能改。
 */
public final class Protocol {
    public static final int PORT = 25585;

    static final String PREFIX = "DI";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Protocol() {
    }

    public static String mining(float percent, String label, int rgb, String icon) {
        return compose("MINE", null, percent, label, rgb, icon, -1, -1);
    }

    public static String placed(int remaining, String label, int rgb, String icon, int durRemaining, int durMax) {
        return compose("PLACE", Integer.toString(remaining), -1.0f, label, rgb, icon, durRemaining, durMax);
    }

    public static String slot(int count, String label, int rgb, String icon, int durRemaining, int durMax) {
        return compose("SLOT", Integer.toString(count), -1.0f, label, rgb, icon, durRemaining, durMax);
    }

    public static String held(int count, String label, int rgb, String icon, int durRemaining, int durMax) {
        return compose("HELD", Integer.toString(count), -1.0f, label, rgb, icon, durRemaining, durMax);
    }

    public static String idle() {
        return PREFIX + "|IDLE";
    }

    public static String ping() {
        return PREFIX + "|PING";
    }

    /**
     * 手工拼串，刻意不用 {@code String.format}：这条路径每次挖掘进度变化都会走，格式化开销不值得。
     */
    static String compose(String type, String value, float percent, String label, int rgb,
                          String icon, int durRemaining, int durMax) {
        StringBuilder builder = new StringBuilder(icon == null ? 96 : icon.length() + 64);
        builder.append(PREFIX).append('|').append(type).append('|');
        if (value != null) {
            builder.append(value);
        } else {
            appendPercent(builder, percent);
        }
        builder.append('|').append(sanitize(label)).append('|');
        appendHex(builder, rgb);
        builder.append('|');
        if (icon != null) {
            builder.append(icon);
        }
        builder.append('|');
        if (durRemaining >= 0) {
            builder.append(durRemaining);
        }
        builder.append('|');
        if (durMax >= 0) {
            builder.append(durMax);
        }
        return builder.toString();
    }

    /** 百分比固定一位小数，例如 {@code 12.5}。 */
    private static void appendPercent(StringBuilder builder, float percent) {
        int tenths = Math.round(percent * 10.0f);
        builder.append(tenths / 10).append('.').append(Math.abs(tenths % 10));
    }

    /** 颜色固定六位大写十六进制。 */
    private static void appendHex(StringBuilder builder, int rgb) {
        for (int shift = 20; shift >= 0; shift -= 4) {
            builder.append(HEX[(rgb >>> shift) & 0xF]);
        }
    }

    /**
     * 名称里不能出现分隔符与换行：物品名来自语言文件，理论上可能被资源包塞进任意字符。
     * 先探测再替换，绝大多数名称不含这些字符，可以原样返回。
     */
    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('|') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
