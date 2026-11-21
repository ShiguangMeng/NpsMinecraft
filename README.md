# NPS · Minecraft — Minecraft 1.21.5 分支

本分支是 NPS · Minecraft 模组在 **Minecraft 1.21.5** 上的专用源码树：只保留该版本用得到的
版本垫片、加载器平台层与元数据，构建矩阵钉死为单一版本。多版本一体化源码与完整说明在
[`main`](https://github.com/ShiguangMeng/NpsMinecraft) 分支。

## 支持范围

| 加载器 | 依赖 | 产物 |
| --- | --- | --- |
| Fabric | Loader >=0.16.0, Fabric API `fabric-key-binding-api-v1` | `nps-minecraft-1.1.0-1.21.5-fabric.jar` |
| Forge | 1.21.5-55.1.18 | `nps-minecraft-1.1.0-1.21.5-forge.jar` |
| NeoForge | 21.5.101 | `nps-minecraft-1.1.0-1.21.5-neoforge.jar` |

Quilt 不单独出包：Quilt Loader 可直接加载 Fabric 模组，前提是 Quilt 端提供 Fabric API
兼容层（QFAPI）。

## 安装

1. 从 [Releases](https://github.com/ShiguangMeng/NpsMinecraft/releases) 下载对应加载器与本版本的 jar。
2. 放进游戏目录的 `mods/`。
3. Fabric 还需要同版本的 Fabric API；Forge / NeoForge 无需额外前置。
4. 桌面端安装 NPS 插件（`NpsMinecraft.dll`），在 NotchPeninsula 的「显示内容」中启用。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File mod\tools\build.ps1
```

产物落在 `dist\<loader>\`。编译是离线的，需要 `.workbuddy\probe` 下的该版本客户端 jar、
映射文件与加载器构件（不入库）；`mod\tools\matrix.ps1` 是这些路径的唯一事实源。

- 版本垫片：`Compat1215`
- 目标字节码：Java 21
- 源码基线：`main` @ d8516e3

## 校验

构建脚本对每个制品依次做：编译 → 命名空间重映射 → 打包 → 成员引用链接校验（LinkCheck）
→ jar 自检（入口、唯一垫片、语言文件、字节码版本、命名空间残留）。

`LinkCheck` 把编译产物里的每个 Minecraft 成员引用拿到该版本**运行时命名空间**中解析，
能把 `NoSuchMethodError` / `NoClassDefFoundError` 这类只在启动时才暴露的问题提前到构建期。

## 目录

```
mod/core/        版本无关主体：协议、采集、通道
mod/compat/      本版本唯一垫片 Compat1215
mod/platforms/   加载器胶水（common 为共享引导）
mod/resources/   本版本元数据与语言文件
mod/tools/       矩阵、构建、重映射、验证脚本
tools/runtime/   LinkCheck 与 UDP 抓包
```

> 本分支由 `main` 的 `mod/tools/branchify.ps1` 生成（2026-11-21）。
> 修复请优先落在 `main`，再同步到各版本分支；直接在本分支改共享代码会让分支间漂移。
