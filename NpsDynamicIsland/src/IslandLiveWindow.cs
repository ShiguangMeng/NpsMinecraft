using NotchPeninsula;
using NotchPeninsula.Plugins;
using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 实况窗（岛左侧圆形气泡）：常驻显示当前手持物品，进度弧平时是**耐久比例**，
/// 挖掘时切换为**挖掘进度**，方块被破坏时满圈亮一下（约 0.8 秒）再回到耐久。
///
/// <para>位置、排序、进出动画、展开面板都由宿主负责；<see cref="DetailPage"/> 返回 null，
/// 宿主会自动复用本插件组件的详情页，点气泡看到的就是同一个 Minecraft 详情面板。</para>
/// </summary>
internal sealed class IslandLiveWindow : ILiveWindow
{
    /// <summary>方块破坏后的满圈停留时长。</summary>
    private const long CompletedHoldMs = 800;

    private readonly IslandSnapshot _snapshot;

    /// <summary>宿主插件级开关（「设置 → 实况窗管理」可关掉本插件的气泡）。</summary>
    private volatile bool _hostEnabled = true;

    /// <summary>上一帧阶段：用于识别「挖掘 → 非挖掘」这一瞬间。</summary>
    private IslandPhase _lastPhase = IslandPhase.Idle;

    private long _completedUntilMs;

    public IslandLiveWindow(IslandSnapshot snapshot) => _snapshot = snapshot;

    public string Id => "island";

    /// <summary>null = 复用组件原有的详情页，不为实况窗单独做一套。</summary>
    public IDetailPage? DetailPage => null;

    public LiveWindowVisual GetVisual()
    {
        long now = Environment.TickCount64;
        var view = _snapshot.Read(now);

        // 挖掘结束（方块破坏）的那一帧：让进度弧满圈亮一下，随后回落到耐久
        if (_lastPhase == IslandPhase.Mining && view.Phase != IslandPhase.Mining)
        {
            _completedUntilMs = now + CompletedHoldMs;
        }
        _lastPhase = view.Phase;

        if (!_hostEnabled || !view.Linked)
        {
            return new LiveWindowVisual
            {
                Progress = -1f,           // 不确定态：断开时进度弧改为持续旋转
                Status = "未连接",
                Title = "Minecraft",
                PercentText = null,
                SpinInner = false,
            };
        }

        bool completed = now < _completedUntilMs;
        float progress;
        string status;

        if (completed)
        {
            progress = 1f;
            status = "已破坏";
        }
        else if (view.Phase == IslandPhase.Mining)
        {
            progress = Math.Clamp(view.Progress, 0f, 1f);
            status = "挖掘中";
        }
        else if (view.MaxDurability > 0)
        {
            progress = Math.Clamp((float)view.Durability / view.MaxDurability, 0f, 1f);
            status = "耐久";
        }
        else
        {
            progress = -1f;
            status = "手持";
        }

        return new LiveWindowVisual
        {
            Progress = progress,
            Status = status,
            Title = view.Label.Length > 0 ? view.Label : "Minecraft",
            PercentText = null,          // null = 确定进度由宿主生成 NN%
            ProgressColor = null,        // 宿主默认绿
            SpinInner = false,           // 内圈是物品图标，不跟着转
            Completed = completed,
        };
    }

    /// <summary>内圈全量自绘：当前物品纹理（未连接时画 Minecraft 图标）。</summary>
    public void DrawInner(SKCanvas canvas, SKRect rect, WidgetFrame frame)
    {
        var view = _snapshot.Read(Environment.TickCount64);
        var bitmap = view.Linked ? IconCache.Resolve(view.Label, view.Icon) : null;
        bool isItem = bitmap != null;
        bitmap ??= McIcon.Resolve();
        if (bitmap == null) return;

        // 16×16 像素纹理：最近邻放大才是「方块」的观感，线性插值会糊成一团
        using var paint = new SKPaint
        {
            IsAntialias = false,
            FilterQuality = isItem ? SKFilterQuality.None : SKFilterQuality.High,
        };
        canvas.DrawBitmap(bitmap, rect, paint);
    }

    /// <summary>组件没有详情页时才会用到；本插件有，展开由宿主复用。</summary>
    public void OnExpand() { }

    public void OnRemoved()
    {
        try { Logger.Info("[NpsMinecraft] 实况窗：已由宿主注销"); } catch { }
    }

    /// <summary>插件级开关变化：气泡被挂起时主岛仍照常显示，重新启用后继续更新。</summary>
    public void OnHostEnabledChanged(bool enabled)
    {
        _hostEnabled = enabled;
        try { Logger.Info($"[NpsMinecraft] 实况窗：宿主{(enabled ? "启用" : "挂起")}本插件的气泡"); } catch { }
    }
}
