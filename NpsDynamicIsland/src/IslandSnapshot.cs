using System.Globalization;

namespace NpsDynamicIsland;

/// <summary>灵动岛当前呈现的阶段。</summary>
internal enum IslandPhase
{
    /// <summary>待机：没有活动内容，岛上只显示当前手持物品的图标（空手或未连接时显示游戏图标）。</summary>
    Idle,

    /// <summary>挖掘中：环形进度条套在图标外，旁边是方块名与百分比。</summary>
    Mining,

    /// <summary>刚放置方块：显示背包内同类方块的剩余数量，数秒后回到待机。</summary>
    Placed,

    /// <summary>滚轮切换到了别的物品：显示该物品的名称与数量，数秒后回到待机。</summary>
    Slot,
}

/// <summary>
/// 渲染线程可安全读取的不可变快照。每帧取一份，帧内不再回读共享状态。
/// </summary>
internal readonly record struct IslandView(
    IslandPhase Phase,
    float Progress,
    string Label,
    int Count,
    int Color,
    string Icon,
    int Durability,
    int MaxDurability,
    bool Linked,
    long Revision);

/// <summary>
/// 游戏侧状态的落点：UDP 线程写、渲染线程读，全部经 <see cref="_gate"/> 串行化。
///
/// <p>协议为单行 UTF-8 文本，固定八段，缺失的可选段写成空串：</p>
///
/// <pre>
/// DI|TYPE|数值|名称|RRGGBB|图标|耐久当前|耐久上限
/// DI|IDLE
/// DI|PING
/// </pre>
///
/// <p>名称与颜色由模组给出（名称是客户端本地化后的显示名），图标是 16×16 纹理的 Base64。</p>
///
/// <p>★ <see cref="IslandView.Revision"/> 的语义是「宽度版本」，只在**岛上所需宽度可能变化**时递增 ★
/// 渲染侧用它决定要不要请求宿主重测组件宽度。挖掘进度每帧都在变但宽度不变，
/// 若每次进度更新都递增，宿主就会每秒被要求重测二十次，这是此前卡顿的主因。</p>
/// </summary>
internal sealed class IslandSnapshot
{
    /// <summary>
    /// 超过这个时长没收到任何报文即视为与游戏断开。
    ///
    /// 必须明显大于模组的心跳间隔（约 2 秒），否则岛上会在「已连接」与「未连接」之间
    /// 周期性来回切换 —— 8 秒留出了 4 倍余量。
    /// </summary>
    public const int LinkTimeoutMs = 8000;

    /// <summary>放置计数或物品卡片的保留时长，之后自动回到待机。</summary>
    public const int PlacedHoldMs = 3200;

    private readonly object _gate = new();

    private IslandPhase _phase = IslandPhase.Idle;
    private float _progress;
    private string _label = "";
    private int _count = -1;
    private int _color = -1;
    private string _icon = "";
    private int _durability = -1;
    private int _maxDurability = -1;
    private long _revision;
    private long _lastPacketMs = long.MinValue / 4;
    private long _placedUntilMs;
    private bool _linked;

    // 待机态展示的信息：当前手持物品。由 HELD 单独维护，
    // 不参与「活动内容」，因此不会让岛上展开。
    private string _heldLabel = "";
    private int _heldColor = -1;
    private string _heldIcon = "";
    private int _heldDurability = -1;
    private int _heldMaxDurability = -1;

    /// <summary>最近一次报文的到达时刻（<see cref="Environment.TickCount64"/>），供详情页显示连接质量。</summary>
    public long LastPacketMs { get { lock (_gate) return _lastPacketMs; } }

    /// <summary>累计收到的有效报文条数。</summary>
    public long PacketCount { get; private set; }

    /// <summary>解析并落一条报文；格式不符时静默忽略，异常不外溢到接收线程之外。</summary>
    public void Apply(string payload, long nowMs)
    {
        if (string.IsNullOrWhiteSpace(payload)) return;

        var parts = payload.Split('|');
        if (parts.Length < 2) return;
        if (!parts[0].Equals("DI", StringComparison.OrdinalIgnoreCase)) return;

        lock (_gate)
        {
            bool accepted = false;

            switch (parts[1].ToUpperInvariant())
            {
                case "MINE":
                    if (parts.Length >= 3 &&
                        float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out float percent))
                    {
                        bool phaseChanged = _phase != IslandPhase.Mining;
                        string previousLabel = _label;

                        _phase = IslandPhase.Mining;
                        _progress = Math.Clamp(percent / 100f, 0f, 1f);
                        ReadContent(parts, out _label, out _color, out _icon,
                                    out _durability, out _maxDurability);

                        // 进度与宽度无关，但方块名直接决定文字宽度：
                        // 进入挖掘态、或挖掘中途换了目标方块，都要让宿主重测一次。
                        if (phaseChanged || !string.Equals(previousLabel, _label, StringComparison.Ordinal))
                        {
                            _revision++;
                        }
                        accepted = true;
                    }
                    break;

                case "PLACE":
                    if (parts.Length >= 3 &&
                        int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out int placed))
                    {
                        _phase = IslandPhase.Placed;
                        _count = Math.Max(0, placed);
                        ReadContent(parts, out _label, out _color, out _icon,
                                    out _durability, out _maxDurability);
                        _placedUntilMs = nowMs + PlacedHoldMs;
                        _revision++;
                        accepted = true;
                    }
                    break;

                case "SLOT":
                    if (parts.Length >= 3 &&
                        int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out int held))
                    {
                        _phase = IslandPhase.Slot;
                        _count = Math.Max(0, held);
                        ReadContent(parts, out _label, out _color, out _icon,
                                    out _durability, out _maxDurability);
                        _placedUntilMs = nowMs + PlacedHoldMs;

                        // 切换后的物品同时也是新的待机图标
                        _heldLabel = _label;
                        _heldColor = _color;
                        _heldIcon = _icon;
                        _heldDurability = _durability;
                        _heldMaxDurability = _maxDurability;

                        _revision++;
                        accepted = true;
                    }
                    break;

                case "HELD":
                    // 只更新待机展示：不动阶段、也不让岛上展开。
                    // 名称为空表示空手，此时清掉图标，由绘制侧回到游戏图标。
                    ReadContent(parts, out string heldName, out int heldColor, out string heldIcon,
                                out int heldDur, out int heldMaxDur);

                    if (_heldLabel != heldName || _heldColor != heldColor || _heldIcon != heldIcon ||
                        _heldDurability != heldDur || _heldMaxDurability != heldMaxDur)
                    {
                        _heldLabel = heldName;
                        _heldColor = heldColor;
                        _heldIcon = heldIcon;
                        _heldDurability = heldDur;
                        _heldMaxDurability = heldMaxDur;
                        _revision++;
                    }
                    accepted = true;
                    break;

                case "PING":
                    // 心跳只用来维持「已连接」判定，不改变岛上的任何呈现，
                    // 因此也不递增版本号 —— 否则每 2 秒就会白白重测一次宽度。
                    accepted = true;
                    break;

                case "IDLE":
                    if (_phase != IslandPhase.Idle)
                    {
                        _phase = IslandPhase.Idle;
                        _revision++;
                    }
                    accepted = true;
                    break;

                default:
                    break;
            }

            if (accepted)
            {
                _lastPacketMs = nowMs;
                PacketCount++;
            }
        }
    }

    /// <summary>
    /// 取一帧的只读视图。
    ///
    /// ★ 过期在这里「惰性落实」：一旦放置计数或物品卡片到期，就把阶段真的改回待机
    /// 并递增一次修订号。修订号是渲染侧判断「要不要请求宿主重测宽度」的唯一依据 ——
    /// 到期若只改返回的 phase 而不动修订号，组件就收不到变化通知，
    /// 表现就是卡片收起后宽度停在展开态不回收。
    /// </summary>
    public IslandView Read(long nowMs)
    {
        lock (_gate)
        {
            if ((_phase == IslandPhase.Placed || _phase == IslandPhase.Slot) && nowMs > _placedUntilMs)
            {
                _phase = IslandPhase.Idle;
                _revision++;
            }

            bool linked = nowMs - _lastPacketMs <= LinkTimeoutMs;

            // 断开时整个组件让位（不占宽度、不绘制任何内容），
            // 因此连接状态一翻转就必须递增版本号，否则宿主收不到「要重测宽度」的通知，
            // 表现就是游戏退出后岛上的内容一直挂着不走。
            if (linked != _linked)
            {
                _linked = linked;
                _revision++;
            }

            bool idle = _phase == IslandPhase.Idle;

            return new IslandView(
                _phase,
                _progress,
                idle ? _heldLabel : _label,
                idle ? 0 : _count,
                idle ? _heldColor : _color,
                idle ? _heldIcon : _icon,
                idle ? _heldDurability : _durability,
                idle ? _heldMaxDurability : _maxDurability,
                linked,
                _revision);
        }
    }

    /// <summary>按固定下标取「名称 / 颜色 / 图标 / 耐久」这组公共字段。</summary>
    private static void ReadContent(string[] parts, out string label, out int color,
                                    out string icon, out int durability, out int maxDurability)
    {
        label = At(parts, 3);
        color = ParseColor(At(parts, 4));
        icon = At(parts, 5);
        durability = ParseInt(At(parts, 6));
        maxDurability = ParseInt(At(parts, 7));
    }

    private static string At(string[] parts, int index)
        => parts.Length > index ? parts[index] : "";

    private static int ParseInt(string raw)
        => int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int value) ? value : -1;

    /// <summary>把 <c>RRGGBB</c> 解析成 0xRRGGBB；缺失或非法时返回 -1。</summary>
    private static int ParseColor(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return -1;

        string trimmed = raw.Trim().TrimStart('#');
        return int.TryParse(trimmed, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out int rgb) ? rgb : -1;
    }
}
