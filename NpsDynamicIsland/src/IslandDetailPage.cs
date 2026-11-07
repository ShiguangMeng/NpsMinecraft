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

        DrawLine(canvas, "NPS · Minecraft", PadX, y, TitleSize, Ink.Pick(TitleColor, PadX / LayoutW, y / LayoutH), alpha);
        y += 22f;

        var server = _server();
        int port = server?.BoundPort ?? -1;
        string listen = port > 0 ? $"127.0.0.1:{port}" : "未监听";

        DrawDot(canvas, PadX + 3f, y - 3.5f, view.Linked ? OkColor : BadColor, alpha);
        DrawLine(canvas,
            view.Linked ? $"已连接 · {listen}" : $"等待游戏连接 · {listen}",
            PadX + 12f, y, BodySize, Ink.Pick(BodyColor, PadX / LayoutW, y / LayoutH), alpha);
        y += 20f;

        DrawLine(canvas, DescribeContent(view), PadX, y, BodySize, Ink.Pick(BodyColor, PadX / LayoutW, y / LayoutH), alpha);
        y += 20f;

        DrawLine(canvas, $"已接收报文 {_snapshot.PacketCount} 条", PadX, y, BodySize, Ink.Pick(BodyColor, PadX / LayoutW, y / LayoutH), alpha);
        y += 22f;

        string hint = view.Linked
            ? "游戏内挖掘、放置方块或滚轮换物品都会同步到这里。"
            : "请确认 Fabric 模组已安装并已进入世界。";
        DrawLine(canvas, hint, PadX, y, BodySize, Ink.Pick(HintColor, PadX / LayoutW, y / LayoutH), alpha);

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

    private static void DrawLine(SKCanvas canvas, string text, float x, float baseline,
                                 float size, SKColor color, float alpha)
    {
        using var paint = new SKPaint
        {
            IsAntialias = true,
            TextSize = size,
            Typeface = PluginFont.Resolve(),
            Color = color.WithAlpha((byte)(color.Alpha * alpha)),
        };
        canvas.DrawText(text, x, baseline, paint);
    }

    private static void DrawDot(SKCanvas canvas, float cx, float cy, SKColor color, float alpha)
    {
        using var paint = new SKPaint
        {
            IsAntialias = true,
            Color = color.WithAlpha((byte)(color.Alpha * alpha)),
        };
        canvas.DrawCircle(cx, cy, 3.5f, paint);
    }
}
