using System.Reflection;
using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 逐元素文字取色：按**元素自己身后的真实底色**决定用深字还是浅字。
///
/// <para>宿主的 <c>RenderTheme.TextColor</c> 是整岛统一值（玻璃材质下按整块壁纸的平均亮度翻转），
/// 但同一个面板里，左侧可能压在浅色封面上、右侧压着深色壁纸 —— 统一色必然有一半看不清。
/// 这里直接读宿主自己抓的背板 <c>Renderer.AcrylicBackdrop</c>（岛体背后的桌面截图，
/// 岛体本身已被抹掉），按 <c>AcrylicBackdropSrc</c> 缩成 16×16 网格缓存，</para>
///
/// <para>每个元素用**面板内归一化坐标**（0~1）取自己那一点的颜色，再用对比度挑黑白。
/// 取不到（实体 / 亚克力材质、首帧、宿主改动）时原样返回主题色，行为与以前一致。</para>
/// </summary>
internal static class Ink
{
    private const int Grid = 16;
    private const int CacheMs = 300;
    private const float Smooth = 0.25f;

    /// <summary>深字 / 浅字（与宿主玻璃态用色一致）。</summary>
    private static readonly SKColor Dark = new(0x18, 0x18, 0x1A);
    private static readonly SKColor Light = new(0xF4, 0xF4, 0xF6);

    private static readonly BindingFlags Static = BindingFlags.Public | BindingFlags.Static;

    private static bool _resolved;
    private static PropertyInfo? _pImage;
    private static PropertyInfo? _pSrc;

    /// <summary>⚠️ 哨兵必须是 0：<c>now - long.MinValue</c> 会溢出成负数，让「缓存命中」恒成立。</summary>
    private static long _tick;

    private static readonly float[] CellR = new float[Grid * Grid];
    private static readonly float[] CellG = new float[Grid * Grid];
    private static readonly float[] CellB = new float[Grid * Grid];
    private static bool _ready;

    /// <summary>面板内归一化位置处的真实底色；取不到返回 null。</summary>
    public static SKColor? At(float nx, float ny)
    {
        if (!EnsureFresh()) return null;
        int x = 1 + (int)MathF.Round(Math.Clamp(nx, 0f, 1f) * (Grid - 3));
        int y = 1 + (int)MathF.Round(Math.Clamp(ny, 0f, 1f) * (Grid - 3));
        int i = y * Grid + x;
        return new SKColor((byte)CellR[i], (byte)CellG[i], (byte)CellB[i]);
    }

    /// <summary>
    /// 按元素背后底色挑文字色：底色亮 → 深字，底色暗 → 浅字。
    /// <paramref name="preferred"/> 在对比度足够时原样保留（不破坏主题配色）。
    /// </summary>
    public static SKColor Pick(SKColor preferred, float nx, float ny)
        => Pick(preferred, At(nx, ny));

    /// <inheritdoc cref="Pick(SKColor,float,float)"/>
    public static SKColor Pick(SKColor preferred, SKColor? background)
    {
        if (background is not { } bg) return preferred;
        if (Contrast(preferred, bg) >= 3.0f) return preferred;

        float lum = Luminance(bg);
        SKColor alt = lum > 0.55f ? Dark : Light;
        return Contrast(alt, bg) > Contrast(preferred, bg) ? alt : preferred;
    }

    /// <summary>WCAG 相对亮度。</summary>
    public static float Luminance(SKColor c)
    {
        static float Ch(byte v)
        {
            float s = v / 255f;
            return s <= 0.03928f ? s / 12.92f : MathF.Pow((s + 0.055f) / 1.055f, 2.4f);
        }
        return 0.2126f * Ch(c.Red) + 0.7152f * Ch(c.Green) + 0.0722f * Ch(c.Blue);
    }

    /// <summary>WCAG 对比度（1~21）。</summary>
    public static float Contrast(SKColor a, SKColor b)
    {
        float la = Luminance(a), lb = Luminance(b);
        float hi = MathF.Max(la, lb), lo = MathF.Min(la, lb);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    private static bool EnsureFresh()
    {
        long now = Environment.TickCount64;
        if (_ready && now - _tick < CacheMs) return true;
        _tick = now;
        if (!ResolveHost()) return _ready;
        return Sample();
    }

    private static bool ResolveHost()
    {
        if (_resolved) return _pImage != null && _pSrc != null;
        _resolved = true;
        try
        {
            // RenderTheme 与 Renderer 在同一个宿主程序集里；按类型取程序集，避免写死命名空间。
            var asm = typeof(NotchPeninsula.Plugins.RenderTheme).Assembly;
            var t = asm.GetType("NotchPeninsula.Renderer");
            _pImage = t?.GetProperty("AcrylicBackdrop", Static);
            _pSrc = t?.GetProperty("AcrylicBackdropSrc", Static);
        }
        catch { }
        return _pImage != null && _pSrc != null;
    }

    private static bool Sample()
    {
        SKBitmap? bmp = null;
        try
        {
            if (_pImage?.GetValue(null) is not SKImage img) return _ready;

            SKRect src = default;
            try { if (_pSrc?.GetValue(null) is SKRect r && r.Width > 2f && r.Height > 2f) src = r; }
            catch { src = default; }
            if (src.IsEmpty) src = new SKRect(0f, 0f, img.Width, img.Height);

            bmp = new SKBitmap(new SKImageInfo(Grid, Grid, SKColorType.Bgra8888, SKAlphaType.Premul));
            using (var canvas = new SKCanvas(bmp))
            {
                canvas.Clear(SKColors.Transparent);
                // 只取「岛体那一块」（src），缩成 16×16 —— 归一化坐标才对得上元素位置
                canvas.Scale(Grid / src.Width, Grid / src.Height);
                canvas.Translate(-src.Left, -src.Top);
                using var paint = new SKPaint { FilterQuality = SKFilterQuality.High, IsAntialias = true };
                canvas.DrawImage(img, 0f, 0f, paint);
                canvas.Flush();
            }

            if (bmp.GetPixel(Grid / 2, Grid / 2).Alpha == 0) return _ready;

            for (int y = 0; y < Grid; y++)
            {
                for (int x = 0; x < Grid; x++)
                {
                    var c = bmp.GetPixel(x, y);
                    int i = y * Grid + x;
                    if (!_ready)
                    {
                        CellR[i] = c.Red; CellG[i] = c.Green; CellB[i] = c.Blue;
                    }
                    else
                    {
                        CellR[i] += (c.Red - CellR[i]) * Smooth;
                        CellG[i] += (c.Green - CellG[i]) * Smooth;
                        CellB[i] += (c.Blue - CellB[i]) * Smooth;
                    }
                }
            }
            _ready = true;
            return true;
        }
        catch { return _ready; }
        finally { bmp?.Dispose(); }
    }
}
