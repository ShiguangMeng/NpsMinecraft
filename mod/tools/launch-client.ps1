# NPS Minecraft - launch a runtime verification client from a generated argfile.
#
# Java @argfiles are read with the platform charset, which mangles the non-ASCII
# characters in this repository path. Instead this reads the argfile, joins it into one
# command line, and hands that to CreateProcess, which is Unicode safe.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameDir,
    [int]$WarmupSeconds = 90,
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

$java = Get-MatrixJdkPath 'java.exe'
$argFile = Join-Path $GameDir 'launch.args'
if (-not (Test-Path $argFile)) { throw "argfile missing: $argFile" }

$lines = @(Get-Content $argFile -Encoding UTF8 | Where-Object { $_.Trim() -ne '' })
$commandLine = ($lines -join ' ')

$sniffOut = Join-Path $GameDir 'sniff.log'
$sniffErr = Join-Path $GameDir 'sniff.err'
Remove-Item $sniffOut, $sniffErr -ErrorAction SilentlyContinue

$harnessOut = Join-Path $script:RepoRoot 'tools\runtime\out'
$sniffer = Start-Process -FilePath $java `
    -ArgumentList ('-cp "' + $harnessOut + '" UdpSniff 25585 ' + ($WarmupSeconds + 120)) `
    -RedirectStandardOutput $sniffOut -RedirectStandardError $sniffErr `
    -PassThru -WindowStyle Hidden
Write-Host ('sniffer pid=' + $sniffer.Id)
Start-Sleep -Milliseconds 1500

$mcLog = Join-Path $GameDir 'console.log'
$mcErr = Join-Path $GameDir 'console.err'
Remove-Item $mcLog, $mcErr -ErrorAction SilentlyContinue

$mc = Start-Process -FilePath $java -ArgumentList $commandLine -WorkingDirectory $GameDir `
    -RedirectStandardOutput $mcLog -RedirectStandardError $mcErr -PassThru -WindowStyle Hidden
Write-Host ('client pid=' + $mc.Id + ' warmup=' + $WarmupSeconds + 's')

$waited = 0
while ($waited -lt $WarmupSeconds) {
    Start-Sleep -Seconds 5
    $waited += 5
    if ($mc.HasExited) { break }
}

Write-Host ('clientAlive=' + (-not $mc.HasExited) + ' after ' + $waited + 's')

Write-Host ''
Write-Host '=== console.err (tail) ==='
Get-Content $mcErr -Tail 30 -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=== console.log (mod-relevant lines) ==='
Get-Content $mcLog -ErrorAction SilentlyContinue | Where-Object { $_ -match 'dynamicisland|DynamicIsland|NPS|FML|ModLauncher|ERROR' } | Select-Object -First 40

Write-Host ''
Write-Host '=== captured datagrams ==='
Get-Content $sniffOut -ErrorAction SilentlyContinue | Select-Object -First 15

if (-not $KeepRunning) {
    if (-not $mc.HasExited) { try { $mc.Kill() } catch { } }
    if (-not $sniffer.HasExited) { try { $sniffer.Kill() } catch { } }
}
