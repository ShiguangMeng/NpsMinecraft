# NPS Minecraft - build a java argument file for a Fabric runtime verification client.
#
# Mirrors make-forge-args.ps1 but without the module path: Fabric Loader is a plain
# classpath launch (KnotClient), so the version jar plus its libraries is enough.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$VersionId,
    [Parameter(Mandatory = $true)][string]$GameDir,
    [Parameter(Mandatory = $true)][string]$McVersion,
    [string]$OutFile = '',
    [string]$UserName = 'Dev'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$mcRoot = 'D:\Meng\Documents\PCL2\.minecraft'
$libRoot = Join-Path $mcRoot 'libraries'
$versionDir = Join-Path $mcRoot ('versions\' + $VersionId)
$versionJson = Join-Path $versionDir ($VersionId + '.json')

if (-not (Test-Path $versionJson)) { throw ('version json missing: ' + $versionJson) }
if (-not $OutFile) { $OutFile = Join-Path $GameDir 'launch.args' }

$spec = Get-Content $versionJson -Raw | ConvertFrom-Json
if (-not $spec.mainClass) { throw 'version json has no mainClass' }
if ($spec.mainClass -notmatch 'fabric') { throw ('not a Fabric version: ' + $spec.mainClass) }

function Test-Rules {
    param($Rules)
    if (-not $Rules) { return $true }
    $allow = $false
    foreach ($r in $Rules) {
        $match = $true
        if ($r.os) {
            if ($r.os.PSObject.Properties['name'] -and $r.os.name -ne 'windows') { $match = $false }
            if ($r.os.PSObject.Properties['arch'] -and $r.os.arch -ne 'x86_64') { $match = $false }
        }
        if ($match) { $allow = ($r.action -eq 'allow') }
    }
    return $allow
}

$classpath = New-Object System.Collections.ArrayList
$nativeJars = New-Object System.Collections.ArrayList

# Fabric keeps the patched game jar next to the version json.
$versionJar = Join-Path $versionDir ($VersionId + '.jar')
if (-not (Test-Path $versionJar)) {
    $versionJar = Join-Path $mcRoot ('versions\' + $McVersion + '\' + $McVersion + '.jar')
}
if (-not (Test-Path $versionJar)) { throw ('game jar missing for ' + $VersionId) }
[void]$classpath.Add($versionJar)

foreach ($l in $spec.libraries) {
    if ($l.PSObject.Properties['rules'] -and -not (Test-Rules $l.rules)) { continue }

    $artifactPath = $null
    if ($l.PSObject.Properties['downloads'] -and $l.downloads -and $l.downloads.PSObject.Properties['artifact'] -and $l.downloads.artifact) {
        $artifactPath = $l.downloads.artifact.path
    }
    if (-not $artifactPath) { continue }

    $full = Join-Path $libRoot $artifactPath
    if (-not (Test-Path $full)) { continue }
    [void]$classpath.Add($full)

    if ($l.PSObject.Properties['natives'] -and $l.natives -and $l.natives.PSObject.Properties['windows'] -and $l.natives.windows) {
        $classifier = $l.natives.windows -replace '\$\{arch\}', '64'
        if ($l.downloads.PSObject.Properties['classifiers'] -and $l.downloads.classifiers.PSObject.Properties[$classifier]) {
            $nativePath = Join-Path $libRoot $l.downloads.classifiers.$classifier.path
            if (Test-Path $nativePath) { [void]$nativeJars.Add($nativePath) }
        }
    }
}

$nativesDir = Join-Path $GameDir 'natives'
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in $nativeJars) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.Name -like '*.dll') {
                $dest = Join-Path $nativesDir $e.Name
                if (-not (Test-Path $dest)) {
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
                }
            }
        }
    } finally {
        $zip.Dispose()
    }
}

$assetIndex = '17'
if ($spec.PSObject.Properties['assetIndex'] -and $spec.assetIndex -and $spec.assetIndex.PSObject.Properties['id']) {
    $assetIndex = $spec.assetIndex.id
}

$lines = @()
$lines += '-Xmx2G'
$lines += '-Djava.library.path=' + $nativesDir
$lines += '-Djna.tmpdir=' + $nativesDir
$lines += '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + $nativesDir
$lines += '-Dminecraft.launcher.brand=PCL2'
$lines += '-Dminecraft.launcher.version=2'
$lines += '-cp'
$lines += (($classpath.ToArray()) -join ';')
$lines += $spec.mainClass
$lines += '--username'
$lines += $UserName
$lines += '--version'
$lines += $McVersion
$lines += '--gameDir'
$lines += $GameDir
$lines += '--assetsDir'
$lines += (Join-Path $mcRoot 'assets')
$lines += '--assetIndex'
$lines += $assetIndex
$lines += '--uuid'
$lines += '00000000-0000-0000-0000-000000000000'
$lines += '--accessToken'
$lines += '0'
$lines += '--userType'
$lines += 'legacy'
$lines += '--versionType'
$lines += 'release'

$rendered = foreach ($line in $lines) {
    if ($line -match '\s') { '"' + $line + '"' } else { $line }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutFile, ($rendered -join "`n"), $utf8)

Write-Host ('argfile: ' + $OutFile)
Write-Host ('mainClass: ' + $spec.mainClass)
Write-Host ('classpath entries: ' + $classpath.Count)
