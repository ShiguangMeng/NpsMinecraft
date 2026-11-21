# NPS Minecraft - build a ready-to-run java argument file for a runtime verification client.
#
# Produces an @argfile next to the game directory so a real client can be started headless
# and the mod can be verified end to end (mod loads, protocol datagrams arrive).
#
# Only Forge 1.20.1 is currently covered: it is the only loader installed under
# PCL2/.minecraft/versions, and everything it needs (MC client jar, Forge universal,
# FML core modules, LWJGL natives) is already present locally.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [string]$OutFile = '',
    [string]$GameDir = '',
    [string]$UserName = 'Dev'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$mcRoot = 'D:\Meng\Documents\PCL2\.minecraft'
$libRoot = Join-Path $mcRoot 'libraries'
$versionId = '1.20.1-Forge_47.4.28'
$versionDir = Join-Path $mcRoot ('versions\' + $versionId)
$versionJson = Join-Path $versionDir ($versionId + '.json')
$versionJar = Join-Path $versionDir ($versionId + '.jar')

if (-not $OutFile) { $OutFile = Join-Path $GameDir 'launch.args' }

if (-not (Test-Path $versionJson)) { throw "version json missing: $versionJson" }
if (-not (Test-Path $versionJar)) { throw "version jar missing: $versionJar" }

$spec = Get-Content $versionJson -Raw | ConvertFrom-Json

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
[void]$classpath.Add($versionJar)
$nativeJars = New-Object System.Collections.ArrayList

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

# Extract Windows natives so LWJGL can find them.
$nativesDir = Join-Path $GameDir 'natives'
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in $nativeJars) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.Name -like '*.dll' -or $e.Name -like '*.so' -or $e.Name -like '*.dylib') {
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

New-Item -ItemType Directory -Force -Path $GameDir | Out-Null

# Module path: BootStrapLauncher and securejarhandler must live on -p, not -cp.
$modulePath = @(
    (Join-Path $libRoot 'cpw\mods\bootstraplauncher\1.1.2\bootstraplauncher-1.1.2.jar'),
    (Join-Path $libRoot 'cpw\mods\securejarhandler\2.1.10\securejarhandler-2.1.10.jar'),
    (Join-Path $libRoot 'org\ow2\asm\asm\9.9.1\asm-9.9.1.jar'),
    (Join-Path $libRoot 'org\ow2\asm\asm-commons\9.9.1\asm-commons-9.9.1.jar'),
    (Join-Path $libRoot 'org\ow2\asm\asm-util\9.9.1\asm-util-9.9.1.jar'),
    (Join-Path $libRoot 'org\ow2\asm\asm-analysis\9.9.1\asm-analysis-9.9.1.jar'),
    (Join-Path $libRoot 'org\ow2\asm\asm-tree\9.9.1\asm-tree-9.9.1.jar')
) | Where-Object { Test-Path $_ }

$ignoreList = 'bootstraplauncher,securejarhandler,asm-commons,asm-util,asm-analysis,asm-tree,asm,JarJarFileSystems,client-extra,fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-,' + $versionId + '.jar'

$lines = @()
$lines += '-Xmx2G'
$lines += '-Djava.library.path=' + $nativesDir
$lines += '-Djna.tmpdir=' + $nativesDir
$lines += '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + $nativesDir
$lines += '-Dio.netty.native.workdir=' + $nativesDir
$lines += '-Dminecraft.launcher.brand=PCL2'
$lines += '-Dminecraft.launcher.version=2'
$lines += '-DignoreList=' + $ignoreList
$lines += '-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar'
$lines += '-DlibraryDirectory=' + $libRoot
$lines += '-Djava.net.preferIPv6Addresses=system'
# BootStrapLauncher / securejarhandler need these module opens on modern JDKs.
$lines += '--add-modules'
$lines += 'ALL-MODULE-PATH'
$lines += '--add-opens'
$lines += 'java.base/java.util.jar=cpw.mods.securejarhandler'
$lines += '--add-opens'
$lines += 'java.base/java.lang.invoke=cpw.mods.securejarhandler'
$lines += '--add-exports'
$lines += 'java.base/sun.security.util=cpw.mods.securejarhandler'
$lines += '--add-exports'
$lines += 'jdk.naming.dns/com.sun.jndi.dns=java.naming'
$lines += '-p'
$lines += ($modulePath -join ';')
$lines += '-cp'
$lines += (($classpath.ToArray()) -join ';')
$lines += $spec.mainClass
$lines += '--username'
$lines += $UserName
$lines += '--version'
$lines += '1.20.1'
$lines += '--gameDir'
$lines += $GameDir
$lines += '--assetsDir'
$lines += (Join-Path $mcRoot 'assets')
$lines += '--assetIndex'
$lines += '5'
$lines += '--uuid'
$lines += '00000000-0000-0000-0000-000000000000'
$lines += '--accessToken'
$lines += '0'
$lines += '--userType'
$lines += 'legacy'
$lines += '--versionType'
$lines += 'release'
$lines += '--launchTarget'
$lines += 'forgeclient'
$lines += '--fml.forgeVersion'
$lines += '47.4.28'
$lines += '--fml.mcVersion'
$lines += '1.20.1'
$lines += '--fml.forgeGroup'
$lines += 'net.minecraftforge'
$lines += '--fml.mcpVersion'
$lines += '20230612.114412'

# java @argfile wants one argument per line, quoting only when the value has spaces.
$rendered = foreach ($line in $lines) {
    if ($line -match '\s') { '"' + $line + '"' } else { $line }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutFile, ($rendered -join "`n"), $utf8)

Write-Host ('argfile: ' + $OutFile)
Write-Host ('gameDir: ' + $GameDir)
Write-Host ('classpath entries: ' + $classpath.Count)
Write-Host ('natives: ' + $nativesDir)
