using System.Globalization;
using System.Reflection;
using Microsoft.Win32;
using NotchPeninsula;
using NotchPeninsula.Plugins;

namespace NpsDynamicIsland;

/// <summary>
/// 插件入口：把 Minecraft 侧的状态搬到灵动岛上。
///
/// <p>数据由 Fabric 模组经本地回环 UDP 推来（协议见 <see cref="IslandSnapshot"/>），
/// 本插件只做接收与呈现，不读取游戏进程、不改动宿主任何源码。</p>
///
/// <p>资源分两层收尾，各自对应宿主的一个生命周期节点：</p>
/// <list type="bullet">
/// <item><b>关闭</b>（禁用 / 重载 / 宿主退出）—— <see cref="Dispose"/> 交还运行期占用的东西：
/// UDP 端口与监听线程、Skia 位图、绘制画笔、静态缓存。</item>
/// <item><b>卸载</b>——通过 <c>RegisterUninstallAction</c> 额外清掉宿主看不见的残留：
/// 本插件写在宿主设置里的值。宿主只在用户真正卸载时执行一次，禁用与重载都不触发。</item>
/// </list>
/// </summary>
public sealed class NpsDynamicIslandPlugin : INotchPlugin, IDisposable
{
    /// <summary>
    /// 插件标识。宿主以它为前缀保存设置键（<c>Plugin.&lt;id&gt;.&lt;项&gt;</c>），
    /// 也是卸载清理时唯一的定位依据，因此只在这里定义一处。
    /// </summary>
    private const string PluginId = "com.nps.dynamicisland";

    /// <summary>宿主存放插件设置的注册表位置，与宿主的 RegistryBase 保持一致。</summary>
    private const string HostRegistryPath = @"SOFTWARE\NotchPeninsula";

    /// <summary>宿主设置键的统一前缀。</summary>
    private const string HostSettingPrefix = "Plugin.";

    public string Id => PluginId;

    public string DisplayName => "NPS · Minecraft";

    /// <summary>版本号取自程序集元数据，csproj 的 Version 是唯一真源。</summary>
    public string Version => ResolveVersion();

    public string Author => "拾光梦";

    private IPluginHost? _host;
    private IslandSnapshot? _snapshot;
    private IslandLinkServer? _server;
    private IslandWidget? _widget;
    private IDisposable? _liveReg;

    public void Initialize(IPluginHost host)
    {
        _host = host;

        var snapshot = new IslandSnapshot();
        _snapshot = snapshot;

        var detail = new IslandDetailPage(snapshot, () => _server);
        _widget = new IslandWidget(snapshot, detail);
        host.RegisterWidget(_widget);

        // 实况窗：岛左侧常驻气泡（耐久 / 挖掘进度）。宿主版本较旧时接口不存在，失败不影响插件运行。
        try
        {
            _liveReg = host.RegisterLiveWindow(new IslandLiveWindow(snapshot));
            Log(_liveReg == null ? "实况窗：宿主未创建气泡（插件级开关已关）" : "实况窗：已注册");
        }
        catch (Exception ex)
        {
            Log($"实况窗注册失败（宿主可能不支持该接口）：{ex.GetType().Name}");
        }

        var server = new IslandLinkServer(snapshot, ReadPort(host), Log);
        _server = server;
        server.Start();

        RegisterUninstallCleanup(host);

        Log($"已加载 v{Version}｜UDP 监听 {(server.Running ? $"127.0.0.1:{server.BoundPort}" : "失败：" + server.LastError)}");
    }

    /// <summary>
    /// 宿主在禁用、重载与退出时都会调用这里，把运行期占用的资源全部交还。
    ///
    /// <p>此时不会再有人进来绘制：宿主的顺序是「先 Dispose、再注销组件、最后卸载程序集」，
    /// 三步都在同一个 UI 线程上串行完成，因此这里释放位图与画笔不会撞上正在进行的绘制。</p>
    /// </summary>
    public void Dispose()
    {
        try { _liveReg?.Dispose(); } catch { }
        _liveReg = null;

        StopLink();
        ReleaseRenderResources();

        _snapshot = null;
        _widget = null;
        _host = null;
    }

    // ── 资源收尾 ─────────────────────────────────────────────────────────

    /// <summary>停掉 UDP 监听并释放端口。幂等 —— 卸载动作与 <see cref="Dispose"/> 都会调它。</summary>
    private void StopLink()
    {
        try { _server?.Dispose(); } catch { }
        _server = null;
    }

    /// <summary>交还绘制侧占用的原生内存与静态缓存。</summary>
    private static void ReleaseRenderResources()
    {
        try { IconCache.Release(); } catch { }
        try { McIcon.Release(); } catch { }
        try { IslandWidget.ReleaseResources(); } catch { }
    }

    /// <summary>
    /// 登记「真正卸载时」执行的一次性清理。
    ///
    /// <p>宿主对这条钩子的语义是：只在用户卸载插件时跑一次，禁用 / 重载 / 程序退出都不跑，
    /// 也不弹任何窗；执行时插件实例还活着、文件还没删。重复注册以最后一次为准。</p>
    ///
    /// <p>宿主版本较旧时接口里没有这个方法，调用会以 <see cref="MissingMethodException"/> 失败 ——
    /// 这不该影响插件运行，因此整体吞掉，只在日志里留一行。</p>
    /// </summary>
    private void RegisterUninstallCleanup(IPluginHost host)
    {
        try
        {
            host.RegisterUninstallAction(CleanupOnUninstall);
            Log("已登记卸载清理");
        }
        catch (Exception ex)
        {
            Log($"登记卸载清理失败（宿主可能不支持该接口）：{ex.GetType().Name}");
        }
    }

    /// <summary>
    /// 卸载时的清理：运行期资源再兜底收一次（幂等），并删掉本插件留在宿主设置里的值。
    /// </summary>
    private void CleanupOnUninstall()
    {
        StopLink();
        ReleaseRenderResources();
        DeleteRemainingSettings();

        Log("已卸载：联动通道关闭，渲染缓存与设置项已清理");
    }

    /// <summary>
    /// 删除本插件在宿主设置里留下的值。
    ///
    /// <p>宿主把插件设置统一存在 <c>HKCU\SOFTWARE\NotchPeninsula</c> 下，键名形如
    /// <c>Plugin.&lt;插件 id&gt;.&lt;项&gt;</c>。这里只删本插件前缀，同一键下其他插件的值一律不动。</p>
    /// </summary>
    private static void DeleteRemainingSettings()
    {
        try
        {
            using var baseKey = Registry.CurrentUser.OpenSubKey(HostRegistryPath, writable: true);
            if (baseKey == null) return;

            string prefix = HostSettingPrefix + PluginId + ".";
            foreach (string name in baseKey.GetValueNames())
            {
                if (name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                {
                    baseKey.DeleteValue(name, throwOnMissingValue: false);
                }
            }
        }
        catch
        {
            // 清理失败不该挡住卸载：宿主对卸载钩子的异常也只会记一条日志
        }
    }

    // ── 配置 ─────────────────────────────────────────────────────────────

    /// <summary>监听端口，可用宿主设置键 <c>port</c> 覆盖（默认 25585，与模组侧一致）。</summary>
    private static int ReadPort(IPluginHost host)
    {
        try
        {
            string fallback = IslandLinkServer.DefaultPort.ToString(CultureInfo.InvariantCulture);
            string raw = host.GetSetting("port", fallback);
            if (int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int port) &&
                port > 0 && port <= 65535)
            {
                return port;
            }
        }
        catch
        {
            // 读设置失败不影响插件运行，退回默认端口
        }

        return IslandLinkServer.DefaultPort;
    }

    private static void Log(string message)
    {
        try { Logger.Info("[NpsMinecraft] " + message); } catch { }
    }

    private static string ResolveVersion()
    {
        try
        {
            var assembly = typeof(NpsDynamicIslandPlugin).Assembly;
            var informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
            if (!string.IsNullOrEmpty(informational))
            {
                int plus = informational.IndexOf('+');
                return plus > 0 ? informational[..plus] : informational;
            }

            return assembly.GetName().Version?.ToString(3) ?? "0.0.0";
        }
        catch { return "0.0.0"; }
    }
}
