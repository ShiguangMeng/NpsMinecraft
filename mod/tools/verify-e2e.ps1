# NPS Minecraft - end-to-end runtime verification inside a real client.
#
# Starts (1) the loopback UDP listener on the protocol port, (2) a dedicated server, and
# (3) a real Minecraft client that auto-joins it, then asserts that the mod actually
# pushed protocol datagrams from inside the running game.
#
# This is the only check that proves the whole chain: class loading by the loader, the
# version shim, the trackers, and the wire format the Notepad-Peninsula plugin parses.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameDir,
    [Parameter(Mandatory = $true)][string]$ArgFile,
    [string]$ServerJar = 'C:\npsrt\server2612\server.jar',
    [string]$ServerDir = 'C:\npsrt\server2612',
    [int]$ServerPort = 25565,
    [int]$ListenSeconds = 180,
    [int]$ClientSeconds = 150,
    [int]$ServerTimeoutSeconds = 300,
    [string]$JavaHome = '',
    [switch]$QuickPlay,
    [switch]$NoServer,
    [string]$QuickPlaySingleplayer = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

# Git Bash exports both HTTPS_PROXY and https_proxy; Windows treats them as one variable,
# so the process environment carries duplicate keys and Start-Process refuses to launch.
foreach ($proxyName in @('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')) {
    if (Test-Path ('Env:' + $proxyName)) {
        Remove-Item ('Env:' + $proxyName) -ErrorAction SilentlyContinue
    }
}

# The client and the server must run on a JDK their Minecraft version supports
# (1.20.x -> 17, 1.21+ -> 21). The sniffer stays on the matrix JDK that compiled it.
$java = Get-MatrixJdkPath 'java.exe'
if ($JavaHome) {
    $candidate = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path $candidate)) { throw ('java.exe not found under JavaHome: ' + $JavaHome) }
    $java = $candidate
}
$snifferJava = Get-MatrixJdkPath 'java.exe'
$harnessOut = Join-Path $script:RepoRoot 'tools\runtime\out'

New-Item -ItemType Directory -Force -Path $GameDir | Out-Null


$server = $null
if (-not $NoServer) {
# --- 1) dedicated server --------------------------------------------------
$srvLog = Join-Path $GameDir 'server-e2e.log'
$srvErr = Join-Path $GameDir 'server-e2e.err'
Remove-Item $srvLog, $srvErr -ErrorAction SilentlyContinue

$server = $null
$portOpen = $false
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $async = $client.BeginConnect('127.0.0.1', $ServerPort, $null, $null)
    $portOpen = $async.AsyncWaitHandle.WaitOne(800, $false) -and $client.Connected
    $client.Close()
} catch {
    $portOpen = $false
}
if ($portOpen) {
    Write-Host ('[e2e] a server is already listening on ' + $ServerPort + ', reusing it')
} else {
    if (-not (Test-Path $ServerJar)) { throw "server jar missing: $ServerJar" }
    # Quote the jar path: the repository path contains spaces, and a bare -jar argument
    # would be split at the first space.
    $serverArgs = '-Xmx2G -jar "' + $ServerJar + '" nogui'
    $server = Start-Process -FilePath $java -ArgumentList $serverArgs `
        -WorkingDirectory $ServerDir -RedirectStandardOutput $srvLog -RedirectStandardError $srvErr `
        -PassThru -WindowStyle Hidden
    Write-Host ('[e2e] server pid=' + $server.Id)

    $deadline = (Get-Date).AddSeconds($ServerTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        if ((Test-Path $srvLog) -and (Select-String -Path $srvLog -Pattern 'Done \(' -Quiet -ErrorAction SilentlyContinue)) {
            break
        }
        if ($server.HasExited) { break }
    }
    if (-not (Test-Path $srvLog) -or -not (Select-String -Path $srvLog -Pattern 'Done \(' -Quiet -ErrorAction SilentlyContinue)) {
        Write-Host '[e2e] server did not reach "Done", tail:'
        Get-Content $srvLog -Tail 20 -ErrorAction SilentlyContinue
        exit 1
    }
    Write-Host '[e2e] server ready'
}

}

# --- 2) protocol listener -------------------------------------------------
$sniffLog = Join-Path $GameDir 'sniff-e2e.log'
$sniffErr = Join-Path $GameDir 'sniff-e2e.err'
Remove-Item $sniffLog, $sniffErr -ErrorAction SilentlyContinue
$sniffer = Start-Process -FilePath $snifferJava `
    -ArgumentList ('-cp "' + $harnessOut + '" UdpSniff ' + 25585 + ' ' + $ListenSeconds) `
    -RedirectStandardOutput $sniffLog -RedirectStandardError $sniffErr -PassThru -WindowStyle Hidden
Write-Host ('[e2e] listener pid=' + $sniffer.Id + ' for ' + $ListenSeconds + 's')
Start-Sleep -Milliseconds 1200

# --- 3) real client -------------------------------------------------------
# Window PowerShell 5.1 mangles non-ASCII characters on its way to CreateProcess, and this
# repository path contains a middle dot. The game directory is therefore handed over as the
# process working directory (a Unicode-safe ProcessStartInfo field) and referenced as "." on
# the command line, so no non-ASCII byte ever has to survive argument encoding.
$lines = @(Get-Content $ArgFile -Encoding UTF8 | Where-Object { $_.Trim() -ne '' })

$rewritten = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -eq '--gameDir' -and ($i + 1) -lt $lines.Count) {
        [void]$rewritten.Add($line)
        [void]$rewritten.Add('.')
        $i++
        continue
    }
    [void]$rewritten.Add($line)
}
if ($QuickPlay) {
    [void]$rewritten.Add('--quickPlayMultiplayer')
    [void]$rewritten.Add('127.0.0.1:' + $ServerPort)
}
if ($QuickPlaySingleplayer) {
    [void]$rewritten.Add('--quickPlaySingleplayer')
    [void]$rewritten.Add($QuickPlaySingleplayer)
}
$commandLine = ($rewritten.ToArray() -join ' ')

$mcLog = Join-Path $GameDir 'client-e2e.log'
$mcErr = Join-Path $GameDir 'client-e2e.err'
Remove-Item $mcLog, $mcErr -ErrorAction SilentlyContinue

$client = Start-Process -FilePath $java -ArgumentList $commandLine -WorkingDirectory $GameDir `
    -RedirectStandardOutput $mcLog -RedirectStandardError $mcErr -PassThru -WindowStyle Hidden
Write-Host ('[e2e] client pid=' + $client.Id)

$waited = 0
while ($waited -lt $ClientSeconds) {
    Start-Sleep -Seconds 5
    $waited += 5
    $nonPing = @()
    if (Test-Path $sniffLog) {
        $nonPing = @(Get-Content $sniffLog -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\[sniff\] \d+ ' -and $_ -notmatch 'DI\|PING' })
    }
    if ($nonPing.Count -gt 0 -and $waited -ge 60) { break }
    if ($client.HasExited) { break }
}

Write-Host ('[e2e] clientAlive=' + (-not $client.HasExited) + ' waited=' + $waited + 's')

$packets = @()
if (Test-Path $sniffLog) {
    $packets = @(Get-Content $sniffLog | Where-Object { $_ -match '^\[sniff\] \d+ ' } | ForEach-Object { ($_ -replace '^\[sniff\] \d+ ', '') })
}
$nonPingPackets = @($packets | Where-Object { $_ -notmatch '^DI\|PING$' })

Write-Host ''
Write-Host ('[e2e] captured ' + $packets.Count + ' datagrams, ' + $nonPingPackets.Count + ' non-PING')
$nonPingPackets | Select-Object -First 12 | ForEach-Object { Write-Host ('   ' + $_) }

if (-not $client.HasExited) { try { $client.Kill() } catch { } }
if ($server -and -not $server.HasExited) { try { $server.Kill() } catch { } }
if (-not $sniffer.HasExited) { try { $sniffer.Kill() } catch { } }

if ($packets.Count -eq 0) {
    Write-Host ''
    Write-Host '[e2e] client log (mod lines):' -ForegroundColor Yellow
    Get-Content $mcLog -ErrorAction SilentlyContinue | Where-Object { $_ -match 'dynamicisland|DynamicIsland|ERROR|Exception' } | Select-Object -First 20
    Write-Host '[e2e] FAIL: no datagrams reached the listener' -ForegroundColor Red
    exit 1
}

if ($nonPingPackets.Count -eq 0) {
    Write-Host '[e2e] FAIL: only heartbeats arrived; the mod never reported game state' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host ('[e2e] PASS: ' + $nonPingPackets.Count + ' state datagrams originated inside the running client') -ForegroundColor Green
exit 0
