using System.Globalization;
using NotchPeninsula.Plugins;
using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 详情页：右键岛内组件展开，显示联动状态与最近一次事件，便于排查「没反应」这类问题。
/// 排版固定按 <see cref="MeasureWidth"/> / <see cref="MeasureHeight"/> 的目标尺寸计算，
/// 绘制时再整体缩放到当前 rect —— 展开动画期间 rect 是插值尺寸，拿它当排版基准会让文字每帧重排。
/// </summary>
internal sealed class IslandDetailPage : IDetailPage
{
    private const float LayoutW = 360f;
    private const float LayoutH = 132f;

    private const float PadX = 16f;
    private const float TitleSize = 13.5f;
    private const float BodySize = 11.5f;

    private static readonly SKColor TitleColor = new(245, 245, 248);
    private static readonly SKColor BodyColor = new(198, 200, 208);
    private static readonly SKColor HintColor = new(150, 152, 160);
    private static readonly SKColor OkColor = new(64, 200, 128);
    private static readonly SKColor BadColor = new(226, 106, 96);

    private readonly IslandSnapshot _snapshot;
    private readonly Func<IslandLinkServer?> _server;

    public IslandDetailPage(IslandSnapshot snapshot, Func<IslandLinkServer?> server)
    {
        _snapshot = snapshot;
        _server = server;
    }

    /// <summary>鼠标移开后面板保留一段时间，够看清一行状态。</summary>
    public TimeSpan AutoCollapseDelay => TimeSpan.FromSeconds(4);

    public float MeasureWidth() => LayoutW;

    public float MeasureHeight() => LayoutH;

    public WidgetHit HitTest(float x, float y, SKRect rect) => WidgetHit.None;

    public void OnAction(string? action, float x, float y) { }

    public void Draw(SKCanvas canvas, SKRect rect, WidgetFrame frame)
    {
        if (frame.Alpha == 0) return;

        long now = Environment.TickCount64;
        var view = _snapshot.Read(now);

        float layoutW = LayoutW, layoutH = LayoutH;
        float scale = Math.Min(rect.Height / layoutH, 1f);

        canvas.Save();
        canvas.Translate(rect.MidX, rect.MidY);
        canvas.Scale(scale);
        canvas.Translate(-layoutW * 0.5f, -layoutH * 0.5f);

        float alpha = frame.Alpha / 255f;
        float y = 20f;

        DrawLine(canvas, frame, "NPS · Minecraft", PadX, y, TitleSize, TitleColor);
        y += 22f;

        var server = _server();
        int port = server?.BoundPort ?? -1;
        string listen = port > 0 ? $"127.0.0.1:{port}" : "未监听";

        DrawDot(canvas, frame, PadX + 3f, y - 3.5f, view.Linked ? OkColor : BadColor);
        DrawLine(canvas, frame,
            view.Linked ? $"已连接 · {listen}" : $"等待游戏连接 · {listen}",
            PadX + 12f, y, BodySize, BodyColor);
        y += 20f;

        DrawLine(canvas, frame, DescribeContent(view), PadX, y, BodySize, BodyColor);
        y += 20f;

        DrawLine(canvas, frame, $"已接收报文 {_snapshot.PacketCount} 条", PadX, y, BodySize, BodyColor);
        y += 22f;

        string hint = view.Linked
            ? "游戏内挖掘、放置方块或滚轮换物品都会同步到这里。"
            : "请确认 Fabric 模组已安装并已进入世界。";
        DrawLine(canvas, frame, hint, PadX, y, BodySize, HintColor, sub: true);

        canvas.Restore();
    }

    private static string DescribeContent(IslandView view) => view.Phase switch
    {
        IslandPhase.Mining =>
            $"挖掘中 · {(string.IsNullOrWhiteSpace(view.Label) ? "方块" : view.Label)} " +
            $"{Math.Round(view.Progress * 100f).ToString("0", CultureInfo.InvariantCulture)}%",
        IslandPhase.Placed =>
            $"已放置 · {(string.IsNullOrWhiteSpace(view.Label) ? "方块" : view.Label)} 剩余 {view.Count}",
        IslandPhase.Slot =>
            $"手持 · {(string.IsNullOrWhiteSpace(view.Label) ? "物品" : view.Label)} ×{view.Count}" +
            (view.MaxDurability > 0 ? $" · 耐久 {view.Durability}/{view.MaxDurability}" : ""),
        _ => string.IsNullOrWhiteSpace(view.Label)
            ? "当前无活动 · 空手"
            : $"当前无活动 · 手持 {(string.IsNullOrWhiteSpace(view.Label) ? "物品" : view.Label)}",
    };

    private static void DrawLine(SKCanvas canvas, WidgetFrame frame, string text, float x, float baseline,
                                 float size, SKColor color, bool sub = false)
    {
        using var paint = new SKPaint
        {
            IsAntialias = true,
            TextSize = size,
            Typeface = PluginFont.Resolve(),
            Color = color,
        };
        // 逐元素判黑白：文字按自己的包围盒取身后的底色（PluginTone）
        PluginTone.DrawText(canvas, frame.Theme, text, x, baseline, paint, sub, frame.Alpha);
    }

    private static void DrawDot(SKCanvas canvas, WidgetFrame frame, float cx, float cy, SKColor color)
    {
        var area = new SKRect(cx - 3.5f, cy - 3.5f, cx + 3.5f, cy + 3.5f);
        using var paint = new SKPaint
        {
            IsAntialias = true,
            // 状态点保留语义色（绿 / 红），只在对比度不够时压暗或提亮
            Color = PluginTone.AccentColor(canvas, area, color).WithAlpha(frame.Alpha),
        };
        canvas.DrawCircle(cx, cy, 3.5f, paint);
    }
}
