using NotchPeninsula;
using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 字体解析器：直接用宿主的字体，插件不自带任何字体文件。
///
/// 每次现取、绝不缓存 —— 宿主换字体会 Dispose 旧的字体面，缓存下来就是 use-after-dispose。
/// 借来的字体面只能借不能还：它是进程级共享资源，插件释放它会让整个岛体在下一帧崩溃。
/// </summary>
internal static class PluginFont
{
    /// <summary>取一个当前可用的字体面；拿不到时返回 null，调用方保持 Skia 默认字体。</summary>
    public static SKTypeface? Resolve()
    {
        try { return FontConfig.Normal; } catch { }
        try { return FontConfig.Fallback; } catch { return null; }
    }
}
