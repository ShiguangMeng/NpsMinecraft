import com.shiguangmeng.dynamicisland.core.IslandBridge;

/**
 * 纯 JVM 协议自测：不加载 Minecraft，只驱动 core 的 IslandBridge，
 * 检验八个字段的拼装、节流之外的格式（一位小数、六位大写 HEX、空图标段、耐久空段）。
 */
public final class ProtocolHarness {
    public static void main(String[] args) throws Exception {
        IslandBridge.init();
        Thread.sleep(300L);
        IslandBridge.sendPing();
        IslandBridge.sendMining(37.5f, "远古残骸", 0x1A2B3C, null);
        IslandBridge.sendMining(100.0f, "石头", 0xFFFFFF, "QUJD");
        IslandBridge.sendPlaced(64, "圆石", 0x7F7F7F, null, 63, 132);
        IslandBridge.sendSlot(13, "钻石镐", 0x33EECC, "QUJDRA==", 1561, 1561);
        IslandBridge.sendHeld(0, "", -1, null, -1, -1);
        IslandBridge.sendIdle();
        Thread.sleep(800L);
        IslandBridge.close();
        System.out.println("[harness] sent 7 datagrams");
    }
}
