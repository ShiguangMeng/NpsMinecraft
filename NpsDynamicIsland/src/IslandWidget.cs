using System.Globalization;
using System.Text;
using NotchPeninsula.Plugins;
using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 岛内组件。
///
/// <p>绘制在**宿主自己的背景**上：不铺深色底，内容直接贴着岛体，
/// 因此在深浅两种主题下都与宿主原生内容融合。</p>
///
/// <p>各阶段的内容：</p>
/// <list type="bullet">
/// <item>待机 —— 只画一个图标：手持物品的原版纹理，空手或未连接时画 Minecraft 图标；</item>
/// <item>挖掘 —— 环形进度条套在图标外，右侧依次是方块名与百分比；</item>
/// <item>放置 / 换物品 —— 图标 + 名称与数量，可损坏物品再带一条耐久条与剩余值。</item>
/// </list>
///
/// <p>宽度走 <b>AutoWidth 协议</b>（宿主的 <see cref="IAutoWidthWidget"/>）：本组件只描述
/// 「由哪几块组成」，槽位宽度由宿主量（内容向上取整 + 两侧 <see cref="WidgetKit.AutoPadX"/>）。
/// 绘制因此以 <see cref="WidgetKit.ContentRect"/> 为原点，盒子与画布严格同一份口径。</p>
///
/// <p>布局一律按局部坐标（原点 = 内容区左上角）计算，绘制时整体平移一次；
/// 这样 HitTest 与 Draw 用的是同一套坐标，不会出现「画在这里、点在别处」。</p>
/// </summary>
internal sealed class IslandWidget : IAutoWidthWidget
{
    // ── 布局常量（逻辑像素）─────────────────────────────────────────────
    // PadX 是**视觉留白**，不是对宿主留白的补偿：宿主在槽位两侧各留 AutoPadX（1px），
    // 两者相加仍是迁移前的 6px，观感不变 —— 口径归宿主，插件不再自己留那一份。
    private const float PadX = 5f;
    private const float IconBox = 22f;
    private const float IconSize = 16f;
    private const float Gap = 5f;
    private const float LabelGap = 4f;
    private const float RingStroke = 2.5f;
    private const float TextSize = 11.5f;
    private const float LabelBudget = 96f;
    private const float DuraBarWidth = 26f;
    private const float DuraBarHeight = 4f;

    /// <summary>百分比按三位数预留宽度，避免 9% → 10% → 100% 时槽位跟着跳。</summary>
    private const string PercentLimit = "100%";

    /// <summary>耐久读数按上限预留宽度，理由同上。</summary>
    private const string DurabilityLimit = "9999/9999";

    // ── 配色：只保留内容自身的颜色，底色交给宿主 ───────────────────────
    private static readonly SKColor RingTrack = new(255, 255, 255, 58);
    private static readonly SKColor RingFill = new(255, 255, 255, 240);
    private static readonly SKColor BarTrack = new(255, 255, 255, 58);
    private static readonly SKColor BarFill = new(255, 255, 255, 236);
    private static readonly SKColor BarLow = new(232, 104, 92, 245);
    private static readonly SKColor MutedText = new(255, 255, 255, 200);

    // ── 绘制资源 ─────────────────────────────────────────────────────────
    // SKPaint 是原生对象。宿主在同一个 UI 线程上串行绘制，把这些画笔按线程复用，
    // 就不必每帧新建若干个原生对象 —— 分配与析构成本原先直接摊在渲染路径上。
    [ThreadStatic] private static SKPaint? _geometryPaint;
    [ThreadStatic] private static SKPaint? _textPaint;
    [ThreadStatic] private static SKPaint? _itemPaint;
    [ThreadStatic] private static SKPaint? _iconPaint;

    /// <summary>
    /// 字形过滤结果缓存：键是「字体面句柄 + 原文」，值是剔除掉当前字体画不出的字符后的文本。
    ///
    /// <p>探测字形是这条渲染路径上最贵的一步，而待机时每帧都要画同一段文字；
    /// 键带字体面句柄，宿主换字体后自然失效，不需要额外清理。</p>
    /// </summary>
    private static readonly Dictionary<(IntPtr Face, string Text), string> FilteredText = new();

    private readonly IslandSnapshot _snapshot;
    private readonly IslandDetailPage _detail;
    private IPluginHost? _host;
    private long _lastRevision = long.MinValue;

    public IslandWidget(IslandSnapshot snapshot, IslandDetailPage detail)
    {
        _snapshot = snapshot;
        _detail = detail;
    }

    public string Id => "com.nps.dynamicisland.widget";

    public string DisplayName => "NPS · Minecraft";

    public IDetailPage? DetailPage => _detail;

    /// <summary>
    /// 宽度固定与否：保持默认 <c>false</c>。
    ///
    /// <para>置 <c>true</c> 会让宿主对「变窄」挂 600 ms 迟滞 —— 本组件的收窄只发生在
    /// 卡片到期（3.2 s）与断连这两个时刻，迟滞只会让卡片收起后多撑一拍，不如让弹簧动画直接回位。</para>
    /// </summary>
    public bool WidthVaries => false;

    /// <summary>
    /// AutoWidth 协议：只描述「我由哪几块组成」，槽位宽度交给宿主
    /// （内容向上取整 + 两侧 <see cref="WidgetKit.AutoPadX"/>，见 <see cref="WidgetKit.Measure"/>）。
    ///
    /// <para>文字块用固定宽度盒子，而不是 <see cref="WidgetKit.Text"/>：绘制前本组件会剔除
    /// 宿主字体画不出的字符、并按 <see cref="LabelBudget"/> 截断补省略号，
    /// 报出去的必须是「实际画出来的宽度」—— 否则槽位会比内容宽一截，右侧多一条空白。</para>
    ///
    /// <para>与游戏断开时返回 <c>null</c>：宿主按 0 宽处理，整块让位。</para>
    /// </summary>
    public Box? Layout(float availableHeight)
    {
        var view = _snapshot.Read(Environment.TickCount64);
        if (!view.Linked) return null;

        var face = PluginFont.Resolve();
        var parts = new List<Box>(8)
        {
            WidgetKit.Gap(PadX),
            WidgetKit.Icon(IconBox),
        };

        switch (view.Phase)
        {
            case IslandPhase.Mining:
            {
                parts.Add(WidgetKit.Gap(Gap));

                float labelWidth = TextWidth(view.Label, LabelBudget, face);
                if (labelWidth > 0f)
                {
                    parts.Add(WidgetKit.Icon(labelWidth));
                    parts.Add(WidgetKit.Gap(LabelGap));
                }
                parts.Add(WidgetKit.Icon(Measure(PercentLimit, face)));
                break;
            }

            case IslandPhase.Placed:
                parts.Add(WidgetKit.Gap(Gap));
                parts.Add(WidgetKit.Icon(TextWidth(PlacedText(view), LabelBudget, face)));
                break;

            case IslandPhase.Slot:
            {
                parts.Add(WidgetKit.Gap(Gap));
                parts.Add(WidgetKit.Icon(TextWidth(SlotText(view), LabelBudget, face)));
                if (view.MaxDurability > 0)
                {
                    parts.Add(WidgetKit.Gap(Gap));
                    parts.Add(WidgetKit.Icon(DuraBarWidth));
                    parts.Add(WidgetKit.Gap(LabelGap));
                    parts.Add(WidgetKit.Icon(Measure(DurabilityLimit, face)));
                }
                break;
            }
        }

        parts.Add(WidgetKit.Gap(PadX));
        return WidgetKit.Row(parts.ToArray());
    }

    /// <summary>
    /// 老协议兜底：宿主只在 <see cref="Layout"/> 抛异常时才会问到它（见宿主的 MeasureWidgetWidth），
    /// 与 AutoWidth 共用同一份口径，避免两条路给出两个宽度。
    /// </summary>
    public float MeasureWidth(float availableHeight) => WidgetKit.Measure(Layout(availableHeight));

    public void Draw(SKCanvas canvas, SKRect rect, WidgetFrame frame)
    {
        long now = Environment.TickCount64;
        var view = _snapshot.Read(now);

        // 只有「宽度可能变化」时才请求重测：宿主按注册表版本缓存宽度，不通知就会停在旧值上。
        // 注意 revision 在挖掘期间不会随进度递增 —— 进度改变内容但不改变宽度。
        if (view.Revision != _lastRevision)
        {
            _lastRevision = view.Revision;
            try { _host?.InvalidateWidgetLayout(); } catch { }
        }

        // 与游戏断开时不绘制任何内容 —— 岛体上不留空壳
        if (!view.Linked) return;

        if (frame.Alpha == 0 || rect.Width <= 1f || rect.Height <= 1f) return;

        // 宿主给 AutoWidth 槽位统一加了左右各 AutoPadX 的留白，排版一律以「内容区」为原点：
        // 这样 Layout 里的盒子与这里画出来的东西是同一份口径，左右也对称。
        var content = WidgetKit.ContentRect(rect);

        canvas.Save();
        canvas.Translate(content.Left, content.Top);
        DrawLocal(canvas, content.Width, content.Height, frame, view);
        canvas.Restore();
    }

    public WidgetHit HitTest(float x, float y, SKRect rect)
    {
        // 组件在未连接时是隐藏的，此时不应命中任何交互
        if (!_snapshot.Read(Environment.TickCount64).Linked) return WidgetHit.None;

        return x >= 0 && x <= rect.Width && y >= 0 && y <= rect.Height
            ? new WidgetHit("detail")
            : WidgetHit.None;
    }

    public void OnLeftClick(string? action, float x, float y)
    {
        try { _host?.ToggleDetailPage(Id); } catch { }
    }

    public void OnRightClick() { }

    public void OnActivate(IPluginHost host) => _host = host;

    public void OnDeactivate() => _host = null;

    // ── 绘制 ─────────────────────────────────────────────────────────────

    private void DrawLocal(SKCanvas canvas, float width, float height, WidgetFrame frame, IslandView view)
    {
        float alpha = frame.Alpha / 255f;
        var face = PluginFont.Resolve();
        float cy = height * 0.5f;
        float boxTop = cy - IconBox * 0.5f;
        float inset = (IconBox - IconSize) * 0.5f;
        float iconX = PadX + inset;
        float iconY = boxTop + inset;

        // 图标：手持物品的原版纹理；未连接、空手或拿不到纹理时退回 Minecraft 图标
        var bitmap = view.Linked ? IconCache.Resolve(view.Label, view.Icon) : null;
        if (bitmap != null)
        {
            DrawBitmap(canvas, bitmap, iconX, iconY, IconSize, alpha, useNearest: true);
        }
        else
        {
            DrawBitmap(canvas, McIcon.Resolve(), iconX, iconY, IconSize, alpha, useNearest: false);
        }

        if (view.Phase == IslandPhase.Mining)
        {
            DrawRing(canvas, PadX, boxTop, IconBox, view.Progress, alpha);
        }

        float x = PadX + IconBox + Gap;
        float baseline = cy + TextSize * 0.36f + frame.TextOffsetY;

        // 文字按自己所在的底色取色：浅色壁纸 / 浅色窗口下自动转深字，深色下保持浅字
        float inkNx = width > 1f ? Math.Clamp((x + 36f) / width, 0f, 1f) : 0.5f;
        var textInk = Ink.Pick(RingFill, inkNx, 0.5f);
        var mutedInk = Ink.Pick(MutedText, inkNx, 0.5f);

        switch (view.Phase)
        {
            case IslandPhase.Mining:
            {
                // 方块名一个字都画不出来时（整名缺字）不留 LabelGap，与 Layout 的盒子一致
                float labelWidth = DrawText(canvas, view.Label, x, baseline, textInk, alpha, LabelBudget, face);
                if (labelWidth > 0f) x += labelWidth + LabelGap;
                DrawText(canvas, Percent(view.Progress), x, baseline, textInk, alpha, null, face);
                break;
            }

            case IslandPhase.Placed:
                DrawText(canvas, PlacedText(view), x, baseline, textInk, alpha, LabelBudget, face);
                break;

            case IslandPhase.Slot:
                x += DrawText(canvas, SlotText(view), x, baseline, textInk, alpha, LabelBudget, face) + Gap;
                DrawDurability(canvas, x, cy, baseline, view, alpha, face);
                break;

            default:
                // 待机：只保留图标，不占任何文字宽度
                break;
        }
    }

    /// <summary>环形进度条：底环铺满一周，进度弧从正上方顺时针推进。</summary>
    private static void DrawRing(SKCanvas canvas, float x, float y, float size, float progress, float alpha)
    {
        var paint = GeometryPaint();
        float inset = RingStroke * 0.5f;
        var rect = new SKRect(x + inset, y + inset, x + size - inset, y + size - inset);

        paint.Style = SKPaintStyle.Stroke;
        paint.StrokeWidth = RingStroke;
        paint.StrokeCap = SKStrokeCap.Round;
        paint.Color = RingTrack.WithAlpha((byte)(RingTrack.Alpha * alpha));
        canvas.DrawArc(rect, 0f, 360f, false, paint);

        float sweep = Math.Clamp(progress, 0f, 1f) * 360f;
        if (sweep < 1f) return;

        paint.Color = RingFill.WithAlpha((byte)(RingFill.Alpha * alpha));
        canvas.DrawArc(rect, -90f, sweep, false, paint);
    }

    /// <summary>耐久条 + 剩余值。剩余低于两成时条变告警色。</summary>
    private static void DrawDurability(SKCanvas canvas, float x, float cy, float baseline,
                                       IslandView view, float alpha, SKTypeface? face)
    {
        if (view.MaxDurability <= 0) return;

        float ratio = Math.Clamp(view.Durability / (float)view.MaxDurability, 0f, 1f);
        float barTop = cy - DuraBarHeight * 0.5f;
        float radius = DuraBarHeight * 0.5f;

        var paint = GeometryPaint();
        paint.Style = SKPaintStyle.Fill;
        paint.Color = BarTrack.WithAlpha((byte)(BarTrack.Alpha * alpha));
        canvas.DrawRoundRect(new SKRect(x, barTop, x + DuraBarWidth, barTop + DuraBarHeight), radius, radius, paint);

        float filled = DuraBarWidth * ratio;
        if (filled >= 1f)
        {
            var color = ratio <= 0.2f ? BarLow : BarFill;
            paint.Color = color.WithAlpha((byte)(color.Alpha * alpha));
            canvas.DrawRoundRect(new SKRect(x, barTop, x + filled, barTop + DuraBarHeight), radius, radius, paint);
        }

        DrawText(canvas, $"{view.Durability}/{view.MaxDurability}",
                 x + DuraBarWidth + LabelGap, baseline,
                 Ink.Pick(MutedText, 0.75f, 0.5f), alpha, null, face);
    }

    private static void DrawBitmap(SKCanvas canvas, SKBitmap? bitmap, float x, float y, float size,
                                   float alpha, bool useNearest)
    {
        if (bitmap == null || bitmap.Handle.Equals(IntPtr.Zero)) return;

        // 物品纹理是 16×16 的像素图，用最近邻保住方块像素的硬边；
        // Minecraft 图标已预缩过一次，用平滑采样才不会有锯齿。
        var paint = useNearest ? ItemPaint() : IconPaint();
        paint.Color = SKColors.White.WithAlpha((byte)Math.Clamp(255f * alpha, 0f, 255f));
        canvas.DrawBitmap(bitmap, new SKRect(x, y, x + size, y + size), paint);
    }

    // ── 文字 ─────────────────────────────────────────────────────────────

    /// <summary>
    /// 画一段文字并返回它的实际宽度。
    ///
    /// <p>绘制前会剔除当前字体画不出的字符 —— 缺字在 Skia 里会渲染成豆腐块，
    /// 而物品名里出现生僻字或特殊符号是常事。缺少的字符直接不占位。</p>
    /// </summary>
    private static float DrawText(SKCanvas canvas, string text, float x, float baseline,
                                  SKColor color, float alpha, float? maxWidth, SKTypeface? face)
    {
        if (string.IsNullOrEmpty(text)) return 0f;

        var paint = TextPaint(face);
        paint.Color = color.WithAlpha((byte)(color.Alpha * alpha));

        string shown = FitText(paint, text, maxWidth);
        if (shown.Length == 0) return 0f;

        float width = paint.MeasureText(shown);
        canvas.DrawText(shown, x, baseline, paint);
        return width;
    }

    /// <summary>
    /// 真正会被画到画布上的文本：先剔除字体画不出的字符，超宽再截断补省略号。
    ///
    /// <para>量宽（<see cref="TextWidth"/>）与绘制都走这一个函数，
    /// 于是「报给宿主的宽度」与「实际画出来的宽度」不可能不一致。</para>
    /// </summary>
    private static string FitText(SKPaint paint, string text, float? maxWidth)
    {
        string shown = FilterSupported(paint, text);
        if (shown.Length == 0) return "";

        if (maxWidth is float budget && budget > 0f && paint.MeasureText(shown) > budget)
        {
            int keep = shown.Length;
            while (keep > 0 && paint.MeasureText(shown[..keep] + "…") > budget)
            {
                keep--;
            }
            if (keep == 0) return "";
            shown = shown[..keep] + "…";
        }

        return shown;
    }

    /// <summary>实际会被画出来的文字有多宽；空文本或缺字到画不出任何字形时返回 0。</summary>
    private static float TextWidth(string text, float budget, SKTypeface? face)
    {
        if (string.IsNullOrWhiteSpace(text)) return 0f;

        var paint = TextPaint(face);
        string shown = FitText(paint, text, budget);
        return shown.Length == 0 ? 0f : paint.MeasureText(shown);
    }

    /// <summary>剔除字体不支持的字符；结果按「字体面 + 原文」缓存，避免每帧重复探测字形。</summary>
    private static string FilterSupported(SKPaint paint, string text)
    {
        var key = (FaceHandle(paint), text);

        lock (FilteredText)
        {
            if (FilteredText.TryGetValue(key, out string? cached)) return cached;
        }

        string shown = ComputeSupported(paint, text);

        lock (FilteredText)
        {
            if (FilteredText.Count < 512) FilteredText[key] = shown;
        }
        return shown;
    }

    private static string ComputeSupported(SKPaint paint, string text)
    {
        try
        {
            if (paint.ContainsGlyphs(text)) return text;
        }
        catch
        {
            return text;
        }

        var builder = new StringBuilder(text.Length);
        foreach (char ch in text)
        {
            if (ch < ' ') continue;

            try
            {
                if (paint.ContainsGlyphs(ch.ToString())) builder.Append(ch);
            }
            catch
            {
                builder.Append(ch);
            }
        }
        return builder.ToString();
    }

    private static IntPtr FaceHandle(SKPaint paint)
    {
        try { return paint.Typeface?.Handle ?? IntPtr.Zero; } catch { return IntPtr.Zero; }
    }

    // ── 画笔 ─────────────────────────────────────────────────────────────

    private static SKPaint GeometryPaint()
    {
        var paint = _geometryPaint;
        if (paint == null)
        {
            paint = new SKPaint { IsAntialias = true };
            _geometryPaint = paint;
        }
        return paint;
    }

    private static SKPaint TextPaint(SKTypeface? face)
    {
        var paint = _textPaint;
        if (paint == null)
        {
            paint = new SKPaint { IsAntialias = true, TextSize = TextSize };
            _textPaint = paint;
        }
        paint.TextSize = TextSize;
        paint.Typeface = face;
        return paint;
    }

    private static SKPaint ItemPaint()
    {
        var paint = _itemPaint;
        if (paint == null)
        {
            paint = new SKPaint { IsAntialias = false, FilterQuality = SKFilterQuality.None };
            _itemPaint = paint;
        }
        return paint;
    }

    private static SKPaint IconPaint()
    {
        var paint = _iconPaint;
        if (paint == null)
        {
            paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High };
            _iconPaint = paint;
        }
        return paint;
    }

    // ── 尺寸与文案 ───────────────────────────────────────────────────────

    /// <summary>按给定文本实测宽度（不做过滤与截断，用于「100%」「9999/9999」这类定长占位）。</summary>
    private static float Measure(string text, SKTypeface? face)
        => text.Length == 0 ? 0f : TextPaint(face).MeasureText(text);

    private static string Percent(float progress)
        => Math.Round(progress * 100f).ToString("0", CultureInfo.InvariantCulture) + "%";

    private static string PlacedText(IslandView view)
    {
        string name = string.IsNullOrWhiteSpace(view.Label) ? "方块" : view.Label;
        return $"{name} ×{view.Count}";
    }

    private static string SlotText(IslandView view)
    {
        string name = string.IsNullOrWhiteSpace(view.Label) ? "物品" : view.Label;
        return view.Count > 1 ? $"{name} ×{view.Count}" : name;
    }

    // ── 关闭时释放 ───────────────────────────────────────────────────────

    /// <summary>
    /// 释放按线程缓存的画笔，并清空字形过滤缓存。插件被禁用、重载或卸载时调用。
    ///
    /// <p>画笔是原生对象，不释放就只能等 GC 兜底；字形缓存则是静态字段，不主动清就一直攥着。
    /// 释放之后若插件被重新启用，下一次绘制会重新创建画笔、重新探测字形，不影响功能。</p>
    ///
    /// <p>必须与绘制在同一个线程上调用 —— 宿主正是这样安排的：同一个 UI 线程依次完成
    /// Dispose 与绘制，所以这里释放的就是刚刚还在用的那几支。</p>
    /// </summary>
    public static void ReleaseResources()
    {
        try { _geometryPaint?.Dispose(); } catch { }
        _geometryPaint = null;

        try { _textPaint?.Dispose(); } catch { }
        _textPaint = null;

        try { _itemPaint?.Dispose(); } catch { }
        _itemPaint = null;

        try { _iconPaint?.Dispose(); } catch { }
        _iconPaint = null;

        lock (FilteredText)
        {
            FilteredText.Clear();
        }
    }
}
