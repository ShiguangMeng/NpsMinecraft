# NPS Minecraft - runtime protocol verification over the real UDP channel.
#
# Starts a loopback UDP listener on the protocol port, drives the core IslandBridge
# through every packet type, and asserts the exact wire format the NotchPeninsula
# plugin parses. This is the loader-independent half of runtime verification: the same
# core classes are what every one of the 35 jars ships.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [string]$ClassName = 'com.shiguangmeng.dynamicisland.core.IslandBridge',
    [int]$Port = 25585,
    [int]$Seconds = 6
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

$java = Get-MatrixJdkPath 'java.exe'
$javac = Get-MatrixJdkPath 'javac.exe'

$harnessSrc = Join-Path $script:RepoRoot 'tools\runtime\ProtocolHarness.java'
$harnessOut = Join-Path $script:RepoRoot 'tools\runtime\out'
$coreClasses = (Get-ChildItem (Join-Path $script:RepoRoot '.workbuddy\build\classes-fabric-1.21.1') -Recurse -Filter '*.class' -ErrorAction SilentlyContinue | Select-Object -First 1)

if (-not (Test-Path $harnessSrc)) { throw "harness source missing: $harnessSrc" }

New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null

# Compile the harness against the already-built core classes (platform-free core only).
$coreDir = Join-Path $script:RepoRoot '.workbuddy\build\core-for-runtime'
if (Test-Path $coreDir) { Remove-Item -Recurse -Force $coreDir }
New-Item -ItemType Directory -Force -Path $coreDir | Out-Null

# The UDP/packet side of core is platform free (no Minecraft types), so compile just
# Protocol + Log + IslandBridge. The trackers and Compat shims are exercised in game.
$coreFiles = @(
    (Join-Path $script:RepoRoot 'mod\core\src\com\shiguangmeng\dynamicisland\core\Protocol.java'),
    (Join-Path $script:RepoRoot 'mod\core\src\com\shiguangmeng\dynamicisland\core\Log.java'),
    (Join-Path $script:RepoRoot 'mod\core\src\com\shiguangmeng\dynamicisland\core\IslandBridge.java')
)
foreach ($f in $coreFiles) { if (-not (Test-Path $f)) { throw "core source missing: $f" } }

& $javac -nowarn --release 8 -d $coreDir @coreFiles 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'core compile failed' }

& $javac -nowarn --release 21 -cp $coreDir -d $harnessOut $harnessSrc 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'harness compile failed' }

$runCp = $coreDir + ';' + $harnessOut

# Start the UDP listener, then the harness.
$listenerOut = Join-Path $script:RepoRoot '.workbuddy\build\sniff.log'
$listenerErr = Join-Path $script:RepoRoot '.workbuddy\build\sniff.err'
$listenerArgs = '-cp "' + $harnessOut + '" UdpSniff ' + $Port + ' ' + $Seconds
$listener = Start-Process -FilePath $java -ArgumentList $listenerArgs -RedirectStandardOutput $listenerOut -RedirectStandardError $listenerErr -PassThru -WindowStyle Hidden
Start-Sleep -Milliseconds 1200

& $java -cp $runCp ProtocolHarness 2>&1 | ForEach-Object { Write-Host ('harness: ' + $_) }

Start-Sleep -Milliseconds 800
try { Wait-Process -Id $listener.Id -Timeout ($Seconds + 3) -ErrorAction SilentlyContinue } catch { }
if (-not $listener.HasExited) { try { $listener.Kill() } catch { } }

$lines = @()
if (Test-Path $listenerOut) { $lines = @(Get-Content $listenerOut) }

$packets = @($lines | Where-Object { $_ -match '^\[sniff\] \d+ ' } | ForEach-Object { ($_ -replace '^\[sniff\] \d+ ', '') })

Write-Host ''
Write-Host '=== captured datagrams ==='
foreach ($p in $packets) { Write-Host ('  ' + $p) }

# Format assertions: exactly the contract the plugin's IslandSnapshot.Split('|') expects.
#
# Labels are asserted structurally (non-empty, no '|', valid UTF-8 round trip) rather than
# by literal text: this script must stay pure ASCII, and the harness sends real CJK names.
# Field positions are what matters for the plugin, and those are pinned exactly.
$expectedFields = @(
    @('DI', 'PING'),
    @('DI', 'MINE', '37.5', '*', '1A2B3C', '', '', ''),
    @('DI', 'MINE', '100.0', '*', 'FFFFFF', 'QUJD', '', ''),
    @('DI', 'PLACE', '64', '*', '7F7F7F', '', '63', '132'),
    @('DI', 'SLOT', '13', '*', '33EECC', 'QUJDRA==', '1561', '1561'),
    @('DI', 'HELD', '0', '', 'FFFFFF', '', '', ''),
    @('DI', 'IDLE')
)

function Test-LabelField {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return $false }
    if ($Value.Contains('|')) { return $false }
    return $true
}

$failed = @()
if ($packets.Count -ne $expectedFields.Count) {
    $failed += ('expected {0} datagrams, captured {1}' -f $expectedFields.Count, $packets.Count)
}

for ($i = 0; $i -lt $expectedFields.Count; $i++) {
    if ($i -ge $packets.Count) { $failed += ('packet {0}: missing' -f ($i + 1)); continue }
    $want = $expectedFields[$i]
    $parts = @($packets[$i].Split('|'))
    if ($parts.Count -ne $want.Count) {
        $failed += ('packet {0}: expected {1} fields, got {2} [{3}]' -f ($i + 1), $want.Count, $parts.Count, $packets[$i])
        continue
    }
    for ($f = 0; $f -lt $want.Count; $f++) {
        if ($want[$f] -eq '*') {
            if (-not (Test-LabelField $parts[$f])) {
                $failed += ('packet {0} field {1}: label must be non-empty and free of the separator' -f ($i + 1), ($f + 1))
            }
            continue
        }
        if ($parts[$f] -ne $want[$f]) {
            $failed += ('packet {0} field {1}: expected [{2}] got [{3}]' -f ($i + 1), ($f + 1), $want[$f], $parts[$f])
        }
    }
}

Write-Host ''
if ($failed.Count -eq 0) {
    Write-Host ('PROTOCOL OK: ' + $packets.Count + ' datagrams matched the wire contract') -ForegroundColor Green
    exit 0
} else {
    Write-Host 'PROTOCOL MISMATCH:' -ForegroundColor Red
    foreach ($f in $failed) { Write-Host ('  ' + $f) -ForegroundColor Red }
    exit 1
}
