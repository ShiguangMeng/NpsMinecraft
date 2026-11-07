using SkiaSharp;

namespace NpsDynamicIsland;

/// <summary>
/// 把模组下发的 Base64 PNG 解码成物品图标，并按物品缓存。
///
/// <p>挖掘时每秒会收到多条报文，每帧都解码会直接拖垮渲染 ——
/// 因此这里用「物品名」作缓存键，内容变了才重新解码。</p>
///
/// <p>★ 模组侧的图标是节流下发的（同一物品几秒才随报文带一次纹理）★
/// 因此**纹理段为空时同样按物品名命中缓存**：空段只表示「这次没带」，
/// 不表示「这个物品没有图标」。若按空段就直接放弃，挖掘期间除了第一条报文，
/// 其余时刻都会退回默认图标。空段返回缓存还有一个好处 —— 这是每帧都会走的路径，
/// 不拼签名、不解码，全程零分配。</p>
///
/// <p>缓存里的位图在**运行期不主动释放**：绘制线程可能正拿着上一帧取到的引用，
/// 释放会造成 use-after-free。条目数到上限时整体清空，旧位图交给 GC。</p>
///
/// <p>插件关闭时例外 —— 那时宿主已不会再调用绘制，统一由 <see cref="Release"/> 释放并清空。</p>
/// </summary>
internal static class IconCache
{
    private const int MaxEntries = 48;

    private sealed class Entry
    {
        /// <summary>该条目对应的纹理 Base64 原文，用于判断同名物品是否换了图。</summary>
        public required string Source { get; init; }

        public required SKBitmap Bitmap { get; init; }
    }

    private static readonly Dictionary<string, Entry> Cache = new(StringComparer.Ordinal);

    /// <summary>
    /// 取某个物品的图标位图。
    /// </summary>
    /// <param name="name">物品显示名，即缓存键；为空时直接返回 null。</param>
    /// <param name="base64">本条报文携带的纹理；为空表示模组这次省略了图标段。</param>
    /// <returns>拿不到图标时返回 null，调用方退回 Minecraft 图标。</returns>
    public static SKBitmap? Resolve(string name, string base64)
    {
        if (string.IsNullOrEmpty(name))
        {
            return null;
        }

        lock (Cache)
        {
            if (Cache.TryGetValue(name, out var hit) &&
                (base64.Length == 0 || string.Equals(hit.Source, base64, StringComparison.Ordinal)))
            {
                return hit.Bitmap;
            }
        }

        // 纹理段为空又查不到同名条目：这个物品确实没有可用图标
        if (base64.Length == 0)
        {
            return null;
        }

        var bitmap = Decode(base64);
        if (bitmap == null)
        {
            return null;
        }

        lock (Cache)
        {
            if (Cache.Count >= MaxEntries)
            {
                Cache.Clear();
            }
            Cache[name] = new Entry { Source = base64, Bitmap = bitmap };
        }

        return bitmap;
    }

    private static SKBitmap? Decode(string base64)
    {
        try
        {
            byte[] bytes = Convert.FromBase64String(base64);
            var bitmap = SKBitmap.Decode(bytes);
            return bitmap != null && !bitmap.Handle.Equals(IntPtr.Zero) ? bitmap : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 释放缓存里的全部位图并清空缓存。插件被禁用、重载或卸载时调用。
    ///
    /// <p>那些位图之后不会有人再画，留着只会让原生内存挂到 GC 下一次回收为止 ——
    /// 重载几次就攒下一批没人再用的句柄。</p>
    ///
    /// <p>先清字典再释放：清空之后调用方再也取不到这些位图，也就不会有人往已释放的句柄上画。</p>
    /// </summary>
    public static void Release()
    {
        List<SKBitmap>? bitmaps;

        lock (Cache)
        {
            if (Cache.Count == 0) return;

            bitmaps = new List<SKBitmap>(Cache.Count);
            foreach (var entry in Cache.Values)
            {
                bitmaps.Add(entry.Bitmap);
            }
            Cache.Clear();
        }

        foreach (var bitmap in bitmaps)
        {
            try { bitmap.Dispose(); } catch { }
        }
    }
}
