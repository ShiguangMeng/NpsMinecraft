import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/** 抓动态岛协议报文：绑 25585，逐条打印时间戳与原始行。 */
public final class UdpSniff {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 25585;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 120;
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(1000);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        byte[] buffer = new byte[4096];
        int count = 0;
        System.out.println("[sniff] listening on " + port + " for " + seconds + "s");
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (java.net.SocketTimeoutException timeout) {
                continue;
            }
            String line = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            count++;
            System.out.println("[sniff] " + count + " " + line);
        }
        socket.close();
        System.out.println("[sniff] done, packets=" + count);
    }
}
