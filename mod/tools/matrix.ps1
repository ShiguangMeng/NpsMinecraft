# NPS Minecraft - build matrix definition (single source of truth).
#
# Build scripts read the version tables from here only, so compile / remap / package /
# verify can never drift apart.
#
# Namespace field decides whether a namespace remap is needed. Getting it wrong shows up
# at runtime as NoSuchMethodError / NoClassDefFoundError:
#   mojmap   - compiled artifact already uses Mojang official names, package as is
#   inter    - Fabric runtime is intermediary, remap mojmap -> intermediary
#   srg1165  - Forge 1.16.5 runtime uses the old func_/field_ SRG generation
#   srg_m    - Forge / NeoForge 1.18.2 ~ 1.20.4 runtime uses the m_/f_ SRG generation
#
# NOTE: pure ASCII on purpose. Windows PowerShell 5.1 parses BOM-less scripts as ANSI,
# so any non-ASCII byte here would break the parser.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ModVersion = '1.1.0'

# Newest first; this order drives build order and the generated report.
$script:McVersions = @('1.21.8')

$script:VersionInfo = @{
    '26.3'    = @{ JavaRelease = '25'; JavaMin = '>=25'; Compat = 'Compat1211'; ClientJar = '.workbuddy\probe\ladder\26.3\client-26.3.jar'; Proguard = '' }
    '26.1.2'  = @{ JavaRelease = '25'; JavaMin = '>=25'; Compat = 'Compat1211'; ClientJar = 'D:\Meng\Documents\PCL2\.minecraft\versions\26.1.2-Fabric 0.19.3\26.1.2-Fabric 0.19.3.jar'; Proguard = '' }
    '1.21.11' = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1211'; ClientJar = '.workbuddy\probe\moj\client-1.21.11-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.21.11\client-1.21.11.txt' }
    '1.21.8'  = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1215'; ClientJar = '.workbuddy\probe\moj\client-1.21.8-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.21.8\client-1.21.8.txt' }
    '1.21.5'  = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1215'; ClientJar = '.workbuddy\probe\moj\client-1.21.5-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.21.5\client-1.21.5.txt' }
    '1.21.4'  = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1214'; ClientJar = '.workbuddy\probe\moj\client-1.21.4-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.21.4\client-1.21.4.txt' }
    '1.21.1'  = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1214'; ClientJar = '.workbuddy\probe\moj\client-1.21.1-moj.jar'; Proguard = '.workbuddy\probe\net\client-1.21.1.txt' }
    '1.20.6'  = @{ JavaRelease = '21'; JavaMin = '>=21'; Compat = 'Compat1206'; ClientJar = '.workbuddy\probe\moj\client-1.20.6-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.20.6\client-1.20.6.txt' }
    '1.20.4'  = @{ JavaRelease = '17'; JavaMin = '>=17'; Compat = 'Compat120'; ClientJar = '.workbuddy\probe\moj\client-1.20.4-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.20.4\client-1.20.4.txt' }
    '1.20.1'  = @{ JavaRelease = '17'; JavaMin = '>=17'; Compat = 'Compat120'; ClientJar = '.workbuddy\probe\moj\client-1.20.1-moj.jar'; Proguard = '.workbuddy\probe\net\client-1.20.1.txt' }
    '1.19.2'  = @{ JavaRelease = '17'; JavaMin = '>=17'; Compat = 'Compat119'; ClientJar = '.workbuddy\probe\moj\client-1.19.2-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.19.2\client-1.19.2.txt' }
    '1.18.2'  = @{ JavaRelease = '17'; JavaMin = '>=17'; Compat = 'Compat118'; ClientJar = '.workbuddy\probe\moj\client-1.18.2-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.18.2\client-1.18.2.txt' }
    '1.16.5'  = @{ JavaRelease = '8';  JavaMin = '>=8';  Compat = 'Compat116'; ClientJar = '.workbuddy\probe\moj\client-1.16.5-moj.jar'; Proguard = '.workbuddy\probe\ladder\1.16.5\client-1.16.5.txt' }
}

# Which MC versions each loader can actually run.
$script:FabricVersions = @('1.21.8')
$script:ForgeVersions = @('1.21.8')
$script:NeoForgeVersions = @('1.21.8')

$script:FabricInfo = @{
    '26.3'    = @{ Platform = 'fabric-modern'; Namespace = 'mojmap'; Loader = '>=0.19.3'; LoaderJar = '0.19.3' }
    '26.1.2'  = @{ Platform = 'fabric-modern'; Namespace = 'mojmap'; Loader = '>=0.19.3'; LoaderJar = '0.19.3' }
    '1.21.11' = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.17.0'; LoaderJar = '0.19.3' }
    '1.21.8'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.16.0'; LoaderJar = '0.18.4' }
    '1.21.5'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.16.0'; LoaderJar = '0.18.4' }
    '1.21.4'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.16.0'; LoaderJar = '0.18.4' }
    '1.21.1'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.15.11'; LoaderJar = '0.18.4' }
    '1.20.6'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.15.11'; LoaderJar = '0.18.4' }
    '1.20.4'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.15.0'; LoaderJar = '0.18.4' }
    '1.20.1'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.14.0'; LoaderJar = '0.18.4' }
    '1.19.2'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.14.0'; LoaderJar = '0.18.4' }
    '1.18.2'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.14.0'; LoaderJar = '0.18.4' }
    '1.16.5'  = @{ Platform = 'fabric-legacy'; Namespace = 'inter';  Loader = '>=0.11.0'; LoaderJar = '0.18.4' }
}

# Forge: 1.16.5 is the func_ SRG generation, 1.18.2~1.20.4 is m_ SRG, 1.20.6+ runs mojmap directly.
$script:ForgeInfo = @{
    '26.3'    = @{ Platform = 'forge-new';    Namespace = 'mojmap';  Univ = '26.3-66.1.9';    Bus = '7.0.6' }
    '26.1.2'  = @{ Platform = 'forge-new';    Namespace = 'mojmap';  Univ = '26.1.2-64.1.6';  Bus = '7.0.6' }
    '1.21.11' = @{ Platform = 'forge-new';    Namespace = 'mojmap';  Univ = '1.21.11-61.2.5'; Bus = '7.0.6' }
    '1.21.8'  = @{ Platform = 'forge-new';    Namespace = 'mojmap';  Univ = '1.21.8-58.1.26'; Bus = '7.0.6' }
    '1.21.5'  = @{ Platform = 'forge-mid';    Namespace = 'mojmap';  Univ = '1.21.5-55.1.18'; Bus = '6.2.33' }
    '1.21.4'  = @{ Platform = 'forge-mid';    Namespace = 'mojmap';  Univ = '1.21.4-54.1.22'; Bus = '6.2.33' }
    '1.21.1'  = @{ Platform = 'forge-mid';    Namespace = 'mojmap';  Univ = '1.21.1-52.1.21'; Bus = '6.2.33' }
    '1.20.6'  = @{ Platform = 'forge-mid';    Namespace = 'mojmap';  Univ = '1.20.6-50.2.15'; Bus = '6.2.33' }
    '1.20.4'  = @{ Platform = 'forge-mid';    Namespace = 'srg_m';   Univ = '1.20.4-49.2.13'; Bus = '6.2.33' }
    '1.20.1'  = @{ Platform = 'forge-mid';    Namespace = 'srg_m';   Univ = '1.20.1-47.4.29'; Bus = '6.2.33' }
    '1.19.2'  = @{ Platform = 'forge-mid';    Namespace = 'srg_m';   Univ = '1.19.2-43.5.2';  Bus = '6.2.33' }
    '1.18.2'  = @{ Platform = 'forge-legacy'; Namespace = 'srg_m';   Univ = '1.18.2-40.3.12'; Bus = '6.2.33' }
    '1.16.5'  = @{ Platform = 'forge-legacy'; Namespace = 'srg1165'; Univ = '1.16.5-36.2.42'; Bus = '6.2.33' }
}

# NeoForge: since 21.1 the runtime is mojmap; 20.4 still uses m_ SRG.
$script:NeoForgeInfo = @{
    '26.3'    = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '12.0.12'; Univ = '26.3.0.113' }
    '26.1.2'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '11.0.15'; Univ = '26.1.2.127' }
    '1.21.11' = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '10.0.36'; Univ = '21.11.48' }
    '1.21.8'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '9.0.18';  Univ = '21.8.57' }
    '1.21.5'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '7.0.13';  Univ = '21.5.101' }
    '1.21.4'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '6.0.18';  Univ = '21.4.161' }
    '1.21.1'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '4.0.45';  Univ = '21.1.267' }
    '1.20.6'  = @{ Platform = 'neoforge-mid';    Namespace = 'mojmap'; Loader = '4.0.45';  Univ = '20.6.144' }
    '1.20.4'  = @{ Platform = 'neoforge-legacy'; Namespace = 'srg_m';  Loader = '2.0.17';  Univ = '20.4.253' }
}

$script:SrgMap = @{
    '1.16.5' = '.workbuddy\probe\tsrg1165\joined.tsrg'
    '1.18.2' = '.workbuddy\probe\tsrg1182\joined.tsrg'
    '1.19.2' = '.workbuddy\probe\tsrg1192\joined.tsrg'
    '1.20.1' = '.workbuddy\probe\tsrg\config\joined.tsrg'
    '1.20.4' = '.workbuddy\probe\tsrg1204\joined.tsrg'
}

$script:InterMap = @{
    '1.16.5'  = '.workbuddy\probe\maps\intermediary-1.16.5.jar'
    '1.18.2'  = '.workbuddy\probe\maps\intermediary-1.18.2.jar'
    '1.19.2'  = '.workbuddy\probe\maps\intermediary-1.19.2.jar'
    '1.20.1'  = '.workbuddy\probe\maps\intermediary-1.20.1.jar'
    '1.20.4'  = '.workbuddy\probe\maps\intermediary-1.20.4.jar'
    '1.20.6'  = '.workbuddy\probe\maps\intermediary-1.20.6.jar'
    '1.21.1'  = '.workbuddy\probe\maps\intermediary-1.21.1.jar'
    '1.21.4'  = '.workbuddy\probe\maps\intermediary-1.21.4.jar'
    '1.21.5'  = '.workbuddy\probe\maps\intermediary-1.21.5.jar'
    '1.21.8'  = '.workbuddy\probe\maps\intermediary-1.21.8.jar'
    '1.21.11' = '.workbuddy\probe\maps\intermediary-1.21.11.jar'
}

# Fabric API modules on the compile classpath. Older versions are single files;
# 26.x ships them split per module, so those rows use a directory.
$script:FabricApi = @{
    '1.16.5'  = @{ Base = '.workbuddy\probe\fapimoj\1.16.5-base.jar';  Key = '.workbuddy\probe\fapimoj\1.16.5-key.jar';  Life = '.workbuddy\probe\fapimoj\1.16.5-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.18.2'  = @{ Base = '.workbuddy\probe\fapimoj\1.18.2-base.jar';  Key = '.workbuddy\probe\fapimoj\1.18.2-key.jar';  Life = '.workbuddy\probe\fapimoj\1.18.2-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.19.2'  = @{ Base = '.workbuddy\probe\fapimoj\1.19.2-base.jar';  Key = '.workbuddy\probe\fapimoj\1.19.2-key.jar';  Life = '.workbuddy\probe\fapimoj\1.19.2-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.20.1'  = @{ Base = '.workbuddy\probe\fapimoj\1.20.1-base.jar';  Key = '.workbuddy\probe\fapimoj\1.20.1-key.jar';  Life = '.workbuddy\probe\fapimoj\1.20.1-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.20.4'  = @{ Base = '.workbuddy\probe\fapimoj\1.20.4-base.jar';  Key = '.workbuddy\probe\fapimoj\1.20.4-key.jar';  Life = '.workbuddy\probe\fapimoj\1.20.4-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.20.6'  = @{ Base = '.workbuddy\probe\fapimoj\1.20.6-base.jar';  Key = '.workbuddy\probe\fapimoj\1.20.6-key.jar';  Life = '.workbuddy\probe\fapimoj\1.20.6-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.21.1'  = @{ Base = '.workbuddy\probe\fapimoj\1.21.1-base.jar';  Key = '.workbuddy\probe\fapimoj\1.21.1-key.jar';  Life = '.workbuddy\probe\fapimoj\1.21.1-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.21.4'  = @{ Base = '.workbuddy\probe\fapimoj\1.21.4-base.jar';  Key = '.workbuddy\probe\fapimoj\1.21.4-key.jar';  Life = '.workbuddy\probe\fapimoj\1.21.4-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.21.5'  = @{ Base = '.workbuddy\probe\fapimoj\1.21.5-base.jar';  Key = '.workbuddy\probe\fapimoj\1.21.5-key.jar';  Life = '.workbuddy\probe\fapimoj\1.21.5-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.21.8'  = @{ Base = '.workbuddy\probe\fapimoj\1.21.8-base.jar';  Key = '.workbuddy\probe\fapimoj\1.21.8-key.jar';  Life = '.workbuddy\probe\fapimoj\1.21.8-life.jar';  KeyModule = 'fabric-key-binding-api-v1' }
    '1.21.11' = @{ Base = '.workbuddy\probe\fapimoj\1.21.11-base.jar'; Key = '.workbuddy\probe\fapimoj\1.21.11-key.jar'; Life = '.workbuddy\probe\fapimoj\1.21.11-life.jar'; KeyModule = 'fabric-key-binding-api-v1' }
    '26.1.2'  = @{ Dir = '.workbuddy\probe\fapimods\26.1.2'; KeyModule = 'fabric-key-mapping-api-v1' }
    '26.3'    = @{ Dir = '.workbuddy\probe\fapimods\26.3';   KeyModule = 'fabric-key-mapping-api-v1' }
}

# Non-Minecraft compile dependencies. Kept on old versions so --release 8 still compiles.
$script:CommonLibs = @(
    'com\mojang\brigadier\1.1.8\brigadier-1.1.8.jar',
    'com\mojang\datafixerupper\6.0.8\datafixerupper-6.0.8.jar',
    'com\mojang\authlib\4.0.43\authlib-4.0.43.jar',
    'org\joml\joml\1.10.5\joml-1.10.5.jar',
    'org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar',
    'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar',
    'com\google\guava\guava\32.1.2-jre\guava-32.1.2-jre.jar',
    'it\unimi\dsi\fastutil\8.5.12\fastutil-8.5.12.jar',
    'org\apache\commons\commons-lang3\3.14.0\commons-lang3-3.14.0.jar',
    'commons-io\commons-io\2.15.1\commons-io-2.15.1.jar',
    'com\mojang\logging\1.2.7\logging-1.2.7.jar',
    'org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar',
    'org\jspecify\jspecify\1.0.0\jspecify-1.0.0.jar'
)

$script:ToolRoot = $PSScriptRoot
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:LibRoot = 'D:\Meng\Documents\PCL2\.minecraft\libraries'
$script:JdkBin = Join-Path $script:RepoRoot '.workbuddy\tools\jdk-25.0.4.1+1\bin'

function Get-MatrixJdkPath {
    param([string]$Tool)
    return (Join-Path $script:JdkBin $Tool)
}

function Get-MatrixRepoPath {
    param([string]$Relative)
    if ([string]::IsNullOrWhiteSpace($Relative)) { return '' }
    if ([System.IO.Path]::IsPathRooted($Relative)) { return $Relative }
    return (Join-Path $script:RepoRoot $Relative)
}

function Get-CommonLibPaths {
    $out = @()
    foreach ($rel in $script:CommonLibs) {
        $p = Join-Path $script:LibRoot $rel
        if (Test-Path $p) { $out += $p }
    }
    return $out
}

function Get-FabricApiPaths {
    param([string]$Version)
    $info = $script:FabricApi[$Version]
    if ($info.ContainsKey('Dir')) {
        $dir = Get-MatrixRepoPath $info.Dir
        $out = @()
        foreach ($prefix in @('fabric-api-base-', ($info.KeyModule + '-'), 'fabric-lifecycle-events-v1-')) {
            $hit = Get-ChildItem $dir -Filter ($prefix + '*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($hit) { $out += $hit.FullName }
        }
        return $out
    }
    $out = @()
    foreach ($rel in @($info.Base, $info.Key, $info.Life)) {
        $p = Get-MatrixRepoPath $rel
        if (Test-Path $p) { $out += $p }
    }
    return $out
}

function Get-ModSources {
    param([string]$PlatformSrc, [string]$Compat)
    $root = $script:RepoRoot
    $list = @()
    $list += @(Get-ChildItem (Join-Path $root 'mod\core\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    $list += (Join-Path $root ('mod\compat\src\com\shiguangmeng\dynamicisland\compat\' + $Compat + '.java'))
    $list += @(Get-ChildItem (Join-Path $root 'mod\platforms\common\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    $list += @(Get-ChildItem (Join-Path $root ('mod\platforms\' + $PlatformSrc + '\src')) -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    return $list
}

function Get-AllRows {
    $rows = @()
    foreach ($v in $script:McVersions) {
        $vi = $script:VersionInfo[$v]
        if ($script:FabricVersions -contains $v) {
            $fi = $script:FabricInfo[$v]
            $rows += New-Object psobject -Property @{
                Loader = 'fabric'; McVersion = $v; Platform = $fi.Platform; Namespace = $fi.Namespace
                Compat = $vi.Compat; JavaRelease = $vi.JavaRelease; JavaMin = $vi.JavaMin
                LoaderDep = $fi.Loader; KeyModule = $script:FabricApi[$v].KeyModule
            }
        }
        if ($script:ForgeVersions -contains $v) {
            $fo = $script:ForgeInfo[$v]
            $rows += New-Object psobject -Property @{
                Loader = 'forge'; McVersion = $v; Platform = $fo.Platform; Namespace = $fo.Namespace
                Compat = $vi.Compat; JavaRelease = $vi.JavaRelease; JavaMin = $vi.JavaMin
                LoaderDep = ''; KeyModule = ''
            }
        }
        if ($script:NeoForgeVersions -contains $v) {
            $nf = $script:NeoForgeInfo[$v]
            $rows += New-Object psobject -Property @{
                Loader = 'neoforge'; McVersion = $v; Platform = $nf.Platform; Namespace = $nf.Namespace
                Compat = $vi.Compat; JavaRelease = $vi.JavaRelease; JavaMin = $vi.JavaMin
                LoaderDep = $nf.Loader; KeyModule = ''
            }
        }
    }
    return $rows
}
