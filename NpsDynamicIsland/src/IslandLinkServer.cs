using System.Net;
using System.Net.Sockets;
using System.Text;

namespace NpsDynamicIsland;

/// <summary>
/// 本地回环 UDP 监听：接收 Fabric mod 推来的状态报文。
///
/// 只绑定 <see cref="IPAddress.Loopback"/>，不接受来自局域网的连接 —— 这些数据只在本机两端之间流动。
/// 接收线程为后台线程，随插件卸载一起退出。
/// </summary>
internal sealed class IslandLinkServer : IDisposable
{
    /// <summary>默认端口，与 mod 侧 <c>IslandBridge.PORT</c> 保持一致。</summary>
    public const int DefaultPort = 25585;

    private const int ReceiveTimeoutMs = 500;

    private readonly IslandSnapshot _snapshot;
    private readonly Action<string> _log;
    private readonly int _port;

    private UdpClient? _client;
    private Thread? _thread;
    private volatile bool _running;

    public IslandLinkServer(IslandSnapshot snapshot, int port, Action<string> log)
    {
        _snapshot = snapshot;
        _port = port;
        _log = log;
    }

    /// <summary>监听是否处于运行状态（不代表已收到过报文）。</summary>
    public bool Running => _running;

    /// <summary>实际绑定的端口；启动失败时为 -1。</summary>
    public int BoundPort { get; private set; } = -1;

    /// <summary>最近一次启动 / 接收失败的原因，供详情页如实展示。</summary>
    public string LastError { get; private set; } = "";

    /// <summary>
    /// 启动监听。端口被占用等失败情况只记录原因，不抛出 —— 插件其余功能照常可用。
    /// </summary>
    public void Start()
    {
        if (_running) return;

        try
        {
            var client = new UdpClient(new IPEndPoint(IPAddress.Loopback, _port));
            client.Client.ReceiveTimeout = ReceiveTimeoutMs;
            _client = client;
            BoundPort = _port;
            _running = true;
            LastError = "";
        }
        catch (Exception ex)
        {
            LastError = $"{ex.GetType().Name}: {ex.Message}";
            _log($"UDP 监听启动失败（端口 {_port}）：{LastError}");
            return;
        }

        _thread = new Thread(Loop)
        {
            IsBackground = true,
            Name = "NpsDynamicIsland.Link",
        };
        _thread.Start();

        _log($"UDP 监听已启动 127.0.0.1:{_port}");
    }

    private void Loop()
    {
        var remote = new IPEndPoint(IPAddress.Any, 0);

        while (_running)
        {
            try
            {
                byte[] data = _client!.Receive(ref remote);
                if (data.Length == 0) continue;

                string text = Encoding.UTF8.GetString(data);
                _snapshot.Apply(text, Environment.TickCount64);
            }
            catch (SocketException sex) when (sex.SocketErrorCode == SocketError.TimedOut)
            {
                // 正常的空闲超时，回到循环顶部重新检查退出标志
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                LastError = $"{ex.GetType().Name}: {ex.Message}";
                _log($"UDP 接收异常：{LastError}");
                Thread.Sleep(200);
            }
        }
    }

    public void Dispose()
    {
        _running = false;

        // 关掉 socket 会让阻塞中的 Receive 立即抛异常并退出循环，不必等满接收超时
        try { _client?.Close(); } catch { }
        try { _client?.Dispose(); } catch { }
        _client = null;

        // 等一个很短的窗口确认线程真的走完：端口要在下一次加载前彻底释放，
        // 否则热重载时新实例可能撞上「地址已在使用」。200 ms 是上限，正常情况下是毫秒级。
        try { _thread?.Join(200); } catch { }
        _thread = null;

        BoundPort = -1;
    }
}
