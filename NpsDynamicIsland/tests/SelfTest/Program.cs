using NpsDynamicIsland;

// 协议解析与状态超时的自检：不依赖宿主、不依赖 Skia，只验证 mod → 插件这条链路的语义。

int pass = 0, fail = 0;

void Check(string name, bool ok)
{
    if (ok)
    {
        pass++;
        Console.WriteLine($"  PASS  {name}");
    }
    else
    {
        fail++;
        Console.WriteLine($"  FAIL  {name}");
    }
}

var snapshot = new IslandSnapshot();
const long now = 1_000L;

Console.WriteLine("协议解析");

snapshot.Apply("DI|MINE|46|橡木原木|6B4E2E|U0lERQ==||", now);
var view = snapshot.Read(now);
Check("挖掘进度 46% → 0.46", Math.Abs(view.Progress - 0.46f) < 0.001f);
Check("方块名原样保留", view.Label == "橡木原木");
Check("颜色解析为 RGB", view.Color == 0x6B4E2E);
Check("图标解析", view.Icon == "U0lERQ==");
Check("阶段为 Mining", view.Phase == IslandPhase.Mining);

Console.WriteLine();
Console.WriteLine("宽度版本（性能关键）");

long miningRev = view.Revision;

snapshot.Apply("DI|MINE|47|橡木原木|6B4E2E", now);
Check("挖掘进度变化不递增版本号（宽度没变，不该让宿主重测）",
      snapshot.Read(now).Revision == miningRev);
Check("但进度本身已更新", Math.Abs(snapshot.Read(now).Progress - 0.47f) < 0.001f);

snapshot.Apply("DI|MINE|48|石头|808080", now);
long afterRename = snapshot.Read(now).Revision;
Check("挖掘中途换了方块时递增版本号（方块名直接决定文字宽度）", afterRename > miningRev);

snapshot.Apply("DI|MINE|49|石头|808080", now);
Check("换名之后单纯涨进度仍不递增版本号", snapshot.Read(now).Revision == afterRename);

snapshot.Apply("DI|PING", now);
Check("心跳不递增版本号", snapshot.Read(now).Revision == afterRename);

snapshot.Apply("DI|PLACE|64|橡木木板|B08B4F", now);
view = snapshot.Read(now);
Check("进入放置态时递增版本号", view.Revision > afterRename);
Check("余量 64", view.Count == 64);
Check("阶段为 Placed", view.Phase == IslandPhase.Placed);

Console.WriteLine();
Console.WriteLine("耐久与图标");

snapshot.Apply("DI|SLOT|1|钻石镐|-1|U0lERQ==|823|1561", now);
view = snapshot.Read(now);
Check("阶段为 Slot", view.Phase == IslandPhase.Slot);
Check("耐久剩余 823", view.Durability == 823);
Check("耐久上限 1561", view.MaxDurability == 1561);
Check("非方块物品颜色为 -1", view.Color == -1);

snapshot.Apply("DI|IDLE", now);
snapshot.Apply("DI|HELD|1|钻石镐|-1|U0lERQ==|823|1561", now);
view = snapshot.Read(now);
Check("HELD 不改变阶段", view.Phase == IslandPhase.Idle);
Check("待机时展示手持物品名", view.Label == "钻石镐");
Check("待机时带上手持物品图标", view.Icon == "U0lERQ==");
Check("待机时带上耐久", view.Durability == 823 && view.MaxDurability == 1561);

long beforeHeld = snapshot.Read(now).Revision;
snapshot.Apply("DI|HELD|1|钻石镐|-1|U0lERQ==|823|1561", now);
Check("HELD 内容未变时不递增版本号", snapshot.Read(now).Revision == beforeHeld);

snapshot.Apply("DI|HELD|1|石头|808080|U0lERQ==", now);
Check("HELD 换了物品时递增版本号", snapshot.Read(now).Revision > beforeHeld);

snapshot.Apply("DI|HELD|0||", now);
view = snapshot.Read(now);
Check("空手时清空图标", string.IsNullOrEmpty(view.Icon) && string.IsNullOrEmpty(view.Label));
Check("空手时无耐久", view.MaxDurability <= 0);

Console.WriteLine();
Console.WriteLine("超时规则");

snapshot.Apply("DI|PLACE|64|橡木木板|B08B4F", now);
view = snapshot.Read(now + IslandSnapshot.PlacedHoldMs + 1);
Check("放置计数到期后回到待机", view.Phase == IslandPhase.Idle);

snapshot.Apply("DI|SLOT|1|石头|808080", now);
Check("物品卡片到期后回到待机",
      snapshot.Read(now + IslandSnapshot.PlacedHoldMs + 1).Phase == IslandPhase.Idle);

snapshot.Apply("DI|MINE|50|石头|808080", now);
snapshot.Apply("DI|IDLE", now);
Check("IDLE 立即清空", snapshot.Read(now).Phase == IslandPhase.Idle);

snapshot.Apply("DI|PING", now);
Check("收到任意报文即视为已连接", snapshot.Read(now + 100).Linked);
Check("断连窗口覆盖模组心跳（约 2 秒）的 4 倍",
      snapshot.Read(now + 3000).Linked && IslandSnapshot.LinkTimeoutMs >= 8000);
Check("超过窗口未收到报文视为断开", !snapshot.Read(now + IslandSnapshot.LinkTimeoutMs + 1).Linked);

Console.WriteLine();
Console.WriteLine("连接状态");

var fresh = new IslandSnapshot();
Check("从未收到报文时视为未连接", !fresh.Read(now).Linked);

fresh.Apply("DI|PING", now);
long connectedRev = fresh.Read(now).Revision;
Check("收到报文后变为已连接", fresh.Read(now).Linked);
Check("未连接 → 已连接时递增版本号", connectedRev > 0);

long steadyRev = fresh.Read(now + 100).Revision;
Check("连接保持时不重复递增版本号", fresh.Read(now + 200).Revision == steadyRev);

Check("超时后回到未连接", !fresh.Read(now + IslandSnapshot.LinkTimeoutMs + 1).Linked);
Check("已连接 → 未连接时再次递增版本号（整块组件要让位）",
      fresh.Read(now + IslandSnapshot.LinkTimeoutMs + 2).Revision > steadyRev);

Console.WriteLine();
Console.WriteLine("异常输入");

long before = snapshot.PacketCount;
snapshot.Apply("", now);
snapshot.Apply("HELLO|WORLD", now);
snapshot.Apply("DI", now);
snapshot.Apply("DI|MINE|abc", now);
snapshot.Apply("DI|PLACE|not-a-number", now);
view = snapshot.Read(now);
Check("非法报文不改变岛内容", view.Phase == IslandPhase.Idle);
Check("非法报文不计入报文数", snapshot.PacketCount == before);

snapshot.Apply("DI|MINE|120|基岩|555555", now);
Check("超过 100% 的进度被夹到 1.0", Math.Abs(snapshot.Read(now).Progress - 1.0f) < 0.001f);

snapshot.Apply("DI|PLACE|-5|泥土|79553A", now);
Check("负数余量被夹到 0", snapshot.Read(now).Count == 0);

snapshot.Apply("di|mine|30|沙子|DBD3A0", now);
Check("首段大小写不敏感", snapshot.Read(now).Phase == IslandPhase.Mining);

Console.WriteLine();
Console.WriteLine($"通过 {pass}，失败 {fail}");
return fail == 0 ? 0 : 1;
