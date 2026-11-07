using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 未连接或空手时显示的 Minecraft 图标。
///
/// <p>图标取自客户端资源包自带的 <c>pack.png</c>（128×128 的草方块，即游戏标志本身），
/// 已作为嵌入资源打进插件 dll —— 插件不依赖任何外部文件，也不需要在运行时去读游戏目录。</p>
///
/// <p>解码与缩放只做一次：原图远大于岛上需要的图标尺寸，
/// 每帧让 Skia 从 128×128 采样到二十几个像素是纯粹的浪费，
/// 因此解码后立刻缩到 {@link Size} 见方常驻，绘制时只做一次小图采样。</p>
/// </summary>
internal static class McIcon
{
    private const string ResourceName = "NpsMinecraft.minecraft.png";

    /// <summary>预缩放边长（像素）。岛上图标约 16 逻辑像素，留到三倍高 DPI 仍有余量。</summary>
    private const int Size = 64;

    private static SKBitmap? _bitmap;
    private static bool _loaded;

    /// <summary>取预缩放后的图标位图；嵌入资源缺失或解码失败时返回 null。</summary>
    public static SKBitmap? Resolve()
    {
        if (_loaded) return _bitmap;

        _loaded = true;
        _bitmap = Load();
        return _bitmap;
    }

    private static SKBitmap? Load()
    {
        try
        {
            using var stream = typeof(McIcon).Assembly.GetManifestResourceStream(ResourceName);
            if (stream == null) return null;

            using var raw = SKBitmap.Decode(stream);
            if (raw == null || raw.Handle.Equals(IntPtr.Zero)) return null;

            var scaled = new SKBitmap(new SKImageInfo(Size, Size, SKColorType.Rgba8888, SKAlphaType.Premul));
            using (var canvas = new SKCanvas(scaled))
            using (var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High })
            {
                canvas.DrawBitmap(raw, new SKRect(0f, 0f, Size, Size), paint);
            }

            return scaled;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 释放预缩放位图并复位加载标记。插件被禁用、重载或卸载时调用。
    ///
    /// <p>复位之后若插件被重新启用，下次绘制会自动重新解码 —— 关闭时不留内存，
    /// 重新打开也不会退化成没有图标。</p>
    /// </summary>
    public static void Release()
    {
        var bitmap = _bitmap;
        _bitmap = null;
        _loaded = false;

        try { bitmap?.Dispose(); } catch { }
    }
}
