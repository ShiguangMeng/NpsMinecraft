# NPS Minecraft - cross-loader / cross-version build pipeline.
#
# For every matrix row: javac -> (optional) namespace remap -> package -> self-check.
# Fully offline; dependencies already live in .workbuddy/probe and the local PCL2 libraries.
#
# NOTE: this file is intentionally pure ASCII. Windows PowerShell 5.1 parses
# BOM-less scripts as ANSI, so any non-ASCII byte here would break the parser.
#
# Usage (Windows PowerShell 5.1):
#   powershell -NoProfile -ExecutionPolicy Bypass -File mod\tools\build.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File mod\tools\build.ps1 -Loader fabric
#   powershell -NoProfile -ExecutionPolicy Bypass -File mod\tools\build.ps1 -McVersion 1.20.1
#   powershell -NoProfile -ExecutionPolicy Bypass -File mod\tools\build.ps1 -VerifyOnly

[CmdletBinding()]
param(
    [ValidateSet('all', 'fabric', 'forge', 'neoforge')]
    [string]$Loader = 'all',

    [string]$McVersion = '',

    [switch]$SkipRemap,

    [switch]$SkipLinkCheck,

    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

$javac = Get-MatrixJdkPath 'javac.exe'
$java = Get-MatrixJdkPath 'java.exe'
$jarTool = Get-MatrixJdkPath 'jar.exe'

if (-not (Test-Path $javac)) { throw "JDK not found: $javac" }

$asm = 'D:\Meng\Documents\PCL2\.minecraft\libraries\org\ow2\asm'
$asmCp = @(
    (Join-Path $asm 'asm\9.9.1\asm-9.9.1.jar'),
    (Join-Path $asm 'asm-tree\9.9.1\asm-tree-9.9.1.jar'),
    (Join-Path $asm 'asm-commons\9.9.1\asm-commons-9.9.1.jar'),
    (Join-Path $asm 'asm-analysis\9.9.1\asm-analysis-9.9.1.jar')
)
$remapCp = (Join-Path $script:RepoRoot 'mod\tools\remap\out') + ';' + ($asmCp -join ';')
$linkCheckOut = Join-Path $script:RepoRoot 'tools\runtime\out'
$linkCheckCp = $linkCheckOut + ';' + ($asmCp -join ';')

$distRoot = Join-Path $script:RepoRoot 'dist'
$workRoot = Join-Path $script:RepoRoot '.workbuddy\build'
$commonLibs = Get-CommonLibPaths

$script:Results = @()

function Get-ArtifactName {
    param([string]$LoaderName, [string]$Version)
    # Minecraft version before the loader: PCL2 and friends pass -DignoreList=...,forge-,...
    # to ModLauncher, which matches any jar whose name contains "forge-". A name such as
    # nps-minecraft-1.1.0-forge-1.20.1.jar is silently dropped on those launchers.
    return "nps-minecraft-$($script:ModVersion)-$Version-$LoaderName.jar"
}

function Get-ClasspathFor {
    param($Row)

    $vi = $script:VersionInfo[$Row.McVersion]
    $probe = Join-Path $script:RepoRoot '.workbuddy\probe'
    $cp = @()
    $cp += (Get-MatrixRepoPath $vi.ClientJar)

    switch ($Row.Loader) {
        'fabric' {
            $cp += Get-FabricApiPaths $Row.McVersion
            $loaderVer = $script:FabricInfo[$Row.McVersion].LoaderJar
            $cp += (Join-Path $script:LibRoot ('net\fabricmc\fabric-loader\' + $loaderVer + '\fabric-loader-' + $loaderVer + '.jar'))
        }
        'forge' {
            $fo = $script:ForgeInfo[$Row.McVersion]
            $cp += (Join-Path $probe ('forge\forge-' + $fo.Univ + '-universal.jar'))
            if ($Row.McVersion -ne '1.16.5') {
                foreach ($a in @('fmlcore', 'javafmllanguage', 'fmlloader', 'mclanguage', 'lowcodelanguage')) {
                    $cp += (Join-Path $probe ('forgeextra\' + $a + '-' + $fo.Univ + '.jar'))
                }
            }
            $cp += (Join-Path $probe ('forgeextra\eventbus-' + $fo.Bus + '.jar'))
        }
        'neoforge' {
            $nf = $script:NeoForgeInfo[$Row.McVersion]
            $cp += (Join-Path $probe ('nf\neoforge-' + $nf.Univ + '-universal.jar'))
            $cp += (Join-Path $probe ('nffml\loader-' + $nf.Loader + '.jar'))
            $cp += (Join-Path $probe 'nffml\bus-8.0.5.jar')
        }
    }

    $cp += $commonLibs
    $kept = @()
    foreach ($p in $cp) { if ($p -and (Test-Path $p)) { $kept += $p } }
    return $kept
}

function Invoke-Compile {
    param($Row, [string]$OutDir)

    if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    $cp = Get-ClasspathFor $Row
    $src = Get-ModSources -PlatformSrc $Row.Platform -Compat $Row.Compat

    $errFile = Join-Path $workRoot ('javac-{0}-{1}.txt' -f $Row.Loader, $Row.McVersion)
    $javacArgs = @('-nowarn', ('--release=' + $Row.JavaRelease), '-cp', ($cp -join ';'), '-d', $OutDir) + $src

    # javac writes deprecation/removal notes to stderr. With ErrorActionPreference=Stop
    # PowerShell would turn those notes into a terminating NativeCommandError even when
    # javac itself exits 0, so relax it for the duration of the call.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $javac @javacArgs 2> $errFile
    } finally {
        $ErrorActionPreference = $previous
    }
    $code = $LASTEXITCODE
    $count = @(Get-ChildItem $OutDir -Recurse -Filter '*.class' -ErrorAction SilentlyContinue).Count
    return New-Object psobject -Property @{ ExitCode = $code; Classes = $count; ErrFile = $errFile }
}

function Invoke-NamespaceRemap {
    param($Row, [string]$InDir, [string]$OutDir)

    if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }

    $vi = $script:VersionInfo[$Row.McVersion]
    $proguard = Get-MatrixRepoPath $vi.Proguard
    # The Mojang-official client jar is handed to RemapTool so it can walk Minecraft's
    # supertype graph and resolve inherited members (ClientLevel.getBlockState et al.)
    # against the class that truly declares them, instead of guessing by name.
    $mojHierarchy = Get-MatrixRepoPath $vi.ClientJar

    # RemapTool prints its summary to stderr, and with ErrorActionPreference=Stop
    # PowerShell would turn that into a terminating NativeCommandError.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        switch ($Row.Namespace) {
            'inter' {
                $map = Get-MatrixRepoPath $script:InterMap[$Row.McVersion]
                $log = & $java -cp $remapCp RemapTool remap $InDir $OutDir $proguard inter $map $mojHierarchy 2>&1
            }
            'srg_m' {
                $map = Get-MatrixRepoPath $script:SrgMap[$Row.McVersion]
                $log = & $java -cp $remapCp RemapTool remap $InDir $OutDir $proguard srg $map $mojHierarchy 2>&1
            }
            'srg1165' {
                $map = Get-MatrixRepoPath $script:SrgMap[$Row.McVersion]
                $log = & $java -cp $remapCp RemapTool remap $InDir $OutDir $proguard srg $map $mojHierarchy 2>&1
            }
            default { throw ('unknown namespace: ' + $Row.Namespace) }
        }
    } finally {
        $ErrorActionPreference = $previous
    }

    $text = ($log | Out-String)
    $ok = $text -match 'remap ok'
    $unmapped = [regex]::Match($text, 'unmapped MC members: methods=(\d+) fields=(\d+)')
    $um = 0
    $uf = 0
    if ($unmapped.Success) {
        $um = [int]$unmapped.Groups[1].Value
        $uf = [int]$unmapped.Groups[2].Value
    }
    return New-Object psobject -Property @{ Ok = $ok; Log = $text; UnmappedMethods = $um; UnmappedFields = $uf }
}

function New-FabricMetadata {
    param($Row, [string]$StageDir)

    $vi = $script:VersionInfo[$Row.McVersion]
    $fi = $script:FabricInfo[$Row.McVersion]
    $depends = [ordered]@{}
    $depends['fabricloader'] = $fi.Loader
    $depends['minecraft'] = ('~' + $Row.McVersion)
    $depends['java'] = $vi.JavaMin
    $depends[$script:FabricApi[$Row.McVersion].KeyModule] = '*'
    $depends['fabric-lifecycle-events-v1'] = '*'

    $description = 'Minecraft game state mirrored to the Windows NotchPeninsula Dynamic Island: mining progress, block placement remaining count, held item and durability. The mod draws nothing in game.'
    $mod = [ordered]@{
        schemaVersion = 1
        id = 'dynamicisland'
        version = $script:ModVersion
        name = 'NPS Minecraft'
        description = $description
        authors = @('ShiguangMeng')
        license = 'MIT'
        environment = 'client'
        entrypoints = [ordered]@{ client = @('com.shiguangmeng.dynamicisland.fabric.FabricEntry') }
        depends = $depends
    }
    $json = ConvertTo-Json -InputObject $mod -Depth 8
    $path = Join-Path $StageDir 'fabric.mod.json'
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $json, $utf8)
    return $path
}

function Invoke-Package {
    param($Row, [string]$ClassesDir)

    $loaderName = $Row.Loader
    $outDir = Join-Path $distRoot $loaderName
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $target = Join-Path $outDir (Get-ArtifactName $loaderName $Row.McVersion)

    $stage = Join-Path $workRoot ('stage-{0}-{1}' -f $loaderName, $Row.McVersion)
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null

    Copy-Item -Recurse -Force (Join-Path $ClassesDir '*') $stage
    Copy-Item -Recurse -Force (Join-Path $script:RepoRoot 'mod\resources\assets') $stage

    switch ($loaderName) {
        'fabric' {
            New-FabricMetadata -Row $Row -StageDir $stage | Out-Null
        }
        'forge' {
            $res = Join-Path $script:RepoRoot ('mod\resources\forge\' + $Row.McVersion)
            if (-not (Test-Path $res)) { throw ('missing Forge resources: ' + $res) }
            Copy-Item -Recurse -Force (Join-Path $res '*') $stage
        }
        'neoforge' {
            $res = Join-Path $script:RepoRoot ('mod\resources\neoforge\' + $Row.McVersion)
            if (-not (Test-Path $res)) { throw ('missing NeoForge resources: ' + $res) }
            Copy-Item -Recurse -Force (Join-Path $res '*') $stage
        }
    }

    if (Test-Path $target) { Remove-Item -Force $target }
    Push-Location $stage
    try { & $jarTool cfM $target . | Out-Null } finally { Pop-Location }

    if (-not (Test-Path $target)) { throw ('package failed: ' + $target) }
    return $target
}

function Get-ClassMajorVersion {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return ($bytes[6] * 256 + $bytes[7])
}

function Get-JarClassTexts {
    param([string]$JarPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $texts = @()
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.EndsWith('.class')) { continue }
            $stream = $e.Open()
            try {
                $ms = New-Object System.IO.MemoryStream
                $stream.CopyTo($ms)
                $texts += [System.Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray())
                $ms.Dispose()
            } finally {
                $stream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
    return $texts
}

function Get-RuntimeJarFor {
    param($Row)

    # LinkCheck needs Minecraft's members in the namespace the loader actually sees at
    # runtime. RemapTool `index` derives that from the same mapping files the remap used,
    # in about ten seconds, so the index is built once per MC version and cached.
    $cache = Join-Path $workRoot ('idx-' + $Row.McVersion + '-' + $Row.Namespace + '.txt')
    if (Test-Path $cache) { return $cache }

    $vi = $script:VersionInfo[$Row.McVersion]
    $proguard = Get-MatrixRepoPath $vi.Proguard
    $mojJar = Get-MatrixRepoPath $vi.ClientJar

    switch ($Row.Namespace) {
        'mojmap' {
            # Already Mojang-official at runtime; use the srg-free client jar as the index source.
            return ''
        }
        'inter' {
            $map = Get-MatrixRepoPath $script:InterMap[$Row.McVersion]
            $target = 'inter'
        }
        default {
            $map = Get-MatrixRepoPath $script:SrgMap[$Row.McVersion]
            $target = 'srg'
        }
    }

    if (-not $proguard -or -not (Test-Path $proguard) -or -not $map -or -not (Test-Path $map)) {
        return ''
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $java -cp $remapCp RemapTool index $cache $proguard $target $map $mojJar 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $previous
    }
    if (Test-Path $cache) { return $cache }
    return ''
}

function Invoke-LinkCheck {
    param($Row, [string]$JarPath)

    if ($SkipLinkCheck) {
        return New-Object psobject -Property @{ Ok = $true; MemberRefs = 0; Failure = ''; Detail = @(); Skipped = $true }
    }
    if ($Row.Namespace -eq 'mojmap') {
        # Compiling against the mojmap client jar and shipping without a remap already
        # guarantees the namespace matches; there is nothing extra to resolve here.
        return New-Object psobject -Property @{ Ok = $true; MemberRefs = 0; Failure = ''; Detail = @(); Skipped = $true }
    }

    $index = Get-RuntimeJarFor $Row
    if (-not $index) {
        return New-Object psobject -Property @{ Ok = $true; MemberRefs = 0; Failure = ''; Detail = @(); Skipped = $true }
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $java -cp $linkCheckCp LinkCheck $JarPath $index 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }

    $text = ($out | Out-String)
    $refsMatch = [regex]::Match($text, 'mcMemberRefs=(\d+)')
    $memberRefs = 0
    if ($refsMatch.Success) { $memberRefs = [int]$refsMatch.Groups[1].Value }

    if ($text -match 'RESULT OK') {
        return New-Object psobject -Property @{ Ok = $true; MemberRefs = $memberRefs; Failure = ''; Detail = @(); Skipped = $false }
    }

    $detail = @()
    foreach ($line in $out) {
        $s = ([string]$line).Trim()
        if ($s -match '^\S+ -> (METHOD|FIELD|TYPE) ') { $detail += $s }
    }
    return New-Object psobject -Property @{ Ok = $false; MemberRefs = $memberRefs; Failure = 'unresolved MC members against runtime namespace'; Detail = @($detail | Select-Object -First 15); Skipped = $false }
}

function Invoke-SelfCheck {    param($Row, [string]$JarPath)

    $name = Get-ArtifactName $Row.Loader $Row.McVersion
    $entries = @(& $jarTool tf $JarPath)
    $checks = [ordered]@{}

    # 1) entry point
    switch ($Row.Loader) {
        'fabric' {
            $checks['entrypoint'] = (($entries -contains 'fabric.mod.json') -and ($entries -contains 'com/shiguangmeng/dynamicisland/fabric/FabricEntry.class'))
        }
        'forge' {
            $checks['entrypoint'] = (($entries -contains 'META-INF/mods.toml') -and ($entries -contains 'com/shiguangmeng/dynamicisland/forge/ForgeEntry.class'))
        }
        'neoforge' {
            if ($Row.McVersion -eq '1.20.4') {
                $checks['entrypoint'] = (($entries -contains 'META-INF/mods.toml') -and ($entries -contains 'com/shiguangmeng/dynamicisland/neoforge/NeoForgeEntry.class'))
            } else {
                $checks['entrypoint'] = (($entries -contains 'META-INF/neoforge.mods.toml') -and ($entries -contains 'com/shiguangmeng/dynamicisland/neoforge/NeoForgeEntry.class'))
            }
        }
    }

    # 2) exactly one compat shim, and the one this row should carry
    $compats = @($entries | Where-Object { $_ -match 'dynamicisland/compat/Compat\w+\.class$' })
    $checks['singleCompat'] = (($compats.Count -eq 1) -and ($compats[0] -match ('/' + $Row.Compat + '\.class$')))

    # 3) language resources
    $checks['lang'] = (($entries -contains 'assets/dynamicisland/lang/zh_cn.json') -and ($entries -contains 'assets/dynamicisland/lang/en_us.json'))

    # 4) class file version matches the target Java release
    $expectedMajor = @{ '8' = 52; '17' = 61; '21' = 65; '25' = 69 }[$Row.JavaRelease]
    $tmp = Join-Path $workRoot ('selfcheck-{0}-{1}' -f $Row.Loader, $Row.McVersion)
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Push-Location $tmp
    try { & $jarTool xf $JarPath 'com/shiguangmeng/dynamicisland/core/IslandCore.class' | Out-Null } finally { Pop-Location }
    $clsPath = Join-Path $tmp 'com\shiguangmeng\dynamicisland\core\IslandCore.class'
    if (Test-Path $clsPath) {
        $major = Get-ClassMajorVersion $clsPath
        $checks['classVersion'] = ($major -eq $expectedMajor)
        $checks['classVersionDetail'] = ('major=' + $major + ' expected=' + $expectedMajor)
    } else {
        $checks['classVersion'] = $false
        $checks['classVersionDetail'] = 'IslandCore.class missing'
    }

    # 5) namespace: neither leftover SRG names the target version lacks, nor missing required ones
    $srgM = 0
    $srg16 = 0
    foreach ($text in (Get-JarClassTexts $JarPath)) {
        $srgM += ([regex]::Matches($text, 'm_\d{4,}_')).Count
        $srg16 += ([regex]::Matches($text, 'func_\d{4,}_')).Count
    }
    switch ($Row.Namespace) {
        'mojmap'  { $checks['namespace'] = (($srgM -eq 0) -and ($srg16 -eq 0)) }
        'inter'   { $checks['namespace'] = (($srgM -eq 0) -and ($srg16 -eq 0)) }
        'srg_m'   { $checks['namespace'] = (($srgM -gt 0) -and ($srg16 -eq 0)) }
        'srg1165' { $checks['namespace'] = (($srg16 -gt 0) -and ($srgM -eq 0)) }
    }
    $checks['namespaceDetail'] = ('m_=' + $srgM + ' func_=' + $srg16 + ' target=' + $Row.Namespace)

    # 6) metadata version + minecraft constraint
    $metaOk = $false
    switch ($Row.Loader) {
        'fabric' {
            Push-Location $tmp
            try { & $jarTool xf $JarPath 'fabric.mod.json' | Out-Null } finally { Pop-Location }
            $metaPath = Join-Path $tmp 'fabric.mod.json'
            if (Test-Path $metaPath) {
                $metaText = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($metaPath))
                $meta = ConvertFrom-Json -InputObject $metaText
                $metaOk = (($meta.version -eq $script:ModVersion) -and ($meta.depends.minecraft -eq ('~' + $Row.McVersion)))
            }
        }
        'forge' {
            Push-Location $tmp
            try { & $jarTool xf $JarPath 'META-INF/mods.toml' | Out-Null } finally { Pop-Location }
            $metaPath = Join-Path $tmp 'META-INF\mods.toml'
            if (Test-Path $metaPath) {
                $metaText = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($metaPath))
                $metaOk = (($metaText.Contains('version = "' + $script:ModVersion + '"')) -and ($metaText.Contains('[' + $Row.McVersion + ']')))
            }
        }
        'neoforge' {
            $tomlName = 'META-INF/neoforge.mods.toml'
            if ($Row.McVersion -eq '1.20.4') { $tomlName = 'META-INF/mods.toml' }
            Push-Location $tmp
            try { & $jarTool xf $JarPath $tomlName | Out-Null } finally { Pop-Location }
            $metaPath = Join-Path $tmp ($tomlName -replace '/', '\')
            if (Test-Path $metaPath) {
                $metaText = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($metaPath))
                $metaOk = (($metaText.Contains('version="' + $script:ModVersion + '"')) -and ($metaText.Contains('[' + $Row.McVersion + ']')))
            }
        }
    }
    $checks['metadataVersion'] = $metaOk

    $failed = @()
    foreach ($key in $checks.Keys) {
        if ($key -like '*Detail') { continue }
        if (-not $checks[$key]) { $failed += $key }
    }

    return New-Object psobject -Property @{
        Name = $name
        Passed = ($failed.Count -eq 0)
        Failures = $failed
        Detail = $checks
        Size = (Get-Item $JarPath).Length
        Entries = $entries.Count
    }
}

function Invoke-Row {
    param($Row)

    $label = ('{0,-8} {1,-8}' -f $Row.Loader, $Row.McVersion)
    $classesDir = Join-Path $workRoot ('classes-{0}-{1}' -f $Row.Loader, $Row.McVersion)
    $remappedDir = Join-Path $workRoot ('remap-{0}-{1}' -f $Row.Loader, $Row.McVersion)
    $artifactName = Get-ArtifactName $Row.Loader $Row.McVersion

    $jarPath = $null

    if (-not $VerifyOnly) {
        Write-Host ('[compile] ' + $label) -NoNewline
        $compile = Invoke-Compile -Row $Row -OutDir $classesDir
        if ($compile.ExitCode -ne 0) {
            Write-Host '  FAILED (javac)' -ForegroundColor Red
            Get-Content $compile.ErrFile -ErrorAction SilentlyContinue | Select-Object -First 10 | ForEach-Object { Write-Host ('    ' + $_) -ForegroundColor DarkRed }
            return New-Object psobject -Property @{ Loader = $Row.Loader; McVersion = $Row.McVersion; Name = $artifactName; Passed = $false; Note = 'javac failed'; Size = 0; Entries = 0; Failures = @('javac'); Detail = @{} }
        }
        Write-Host ('  ok (' + $compile.Classes + ' classes)') -ForegroundColor Green

        $finalDir = $classesDir
        if (($Row.Namespace -ne 'mojmap') -and (-not $SkipRemap)) {
            Write-Host ('[remap]   ' + $label) -NoNewline
            $remap = Invoke-NamespaceRemap -Row $Row -InDir $classesDir -OutDir $remappedDir
            if (-not $remap.Ok) {
                Write-Host '  FAILED' -ForegroundColor Red
                Write-Host $remap.Log -ForegroundColor DarkRed
                return New-Object psobject -Property @{ Loader = $Row.Loader; McVersion = $Row.McVersion; Name = $artifactName; Passed = $false; Note = 'remap failed'; Size = 0; Entries = 0; Failures = @('remap'); Detail = @{} }
            }
            Write-Host ('  ok (-> ' + $Row.Namespace + ', unmapped m/f=' + $remap.UnmappedMethods + '/' + $remap.UnmappedFields + ')') -ForegroundColor Green
            $finalDir = $remappedDir
        }

        Write-Host ('[package] ' + $label) -NoNewline
        $jarPath = Invoke-Package -Row $Row -ClassesDir $finalDir
        Write-Host ('  ok (' + (Get-Item $jarPath).Length + ' B)') -ForegroundColor Green
    } else {
        $jarPath = Join-Path (Join-Path $distRoot $Row.Loader) $artifactName
        if (-not (Test-Path $jarPath)) {
            return New-Object psobject -Property @{ Loader = $Row.Loader; McVersion = $Row.McVersion; Name = $artifactName; Passed = $false; Note = 'jar missing'; Size = 0; Entries = 0; Failures = @('missing'); Detail = @{} }
        }
    }

    Write-Host ('[link]    ' + $label) -NoNewline
    $link = Invoke-LinkCheck -Row $Row -JarPath $jarPath
    if ($link.Skipped) {
        Write-Host '  ok (mojmap, no remap to verify)' -ForegroundColor Green
    } elseif ($link.Ok) {
        Write-Host ('  ok (' + $link.MemberRefs + ' MC member refs resolved)') -ForegroundColor Green
    } else {
        Write-Host ('  FAIL: ' + $link.Failure) -ForegroundColor Red
        foreach ($line in $link.Detail) { Write-Host ('    ' + $line) -ForegroundColor DarkRed }
    }

    Write-Host ('[check]   ' + $label) -NoNewline
    $check = Invoke-SelfCheck -Row $Row -JarPath $jarPath
    if ($check.Passed) {
        Write-Host '  PASS' -ForegroundColor Green
    } else {
        Write-Host ('  FAIL: ' + ($check.Failures -join ', ')) -ForegroundColor Red
    }

    # The row only passes when both the structural self-check and the link check agree.
    $allPassed = ($check.Passed -and $link.Ok)
    $notes = @()
    if (-not $check.Passed) { $notes += $check.Failures }
    if (-not $link.Ok) { $notes += 'linkcheck' }

    return New-Object psobject -Property @{
        Loader = $Row.Loader
        McVersion = $Row.McVersion
        Name = $check.Name
        Passed = $allPassed
        Note = ($notes -join ',')
        Size = $check.Size
        Entries = $check.Entries
        Failures = $notes
        Detail = $check.Detail
        LinkRefs = $link.MemberRefs
    }
}

# ---- main ----------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
New-Item -ItemType Directory -Force -Path $distRoot | Out-Null

$rows = Get-AllRows
if ($Loader -ne 'all') { $rows = @($rows | Where-Object { $_.Loader -eq $Loader }) }
if ($McVersion -ne '') { $rows = @($rows | Where-Object { $_.McVersion -eq $McVersion }) }
if ($rows.Count -eq 0) { throw 'no matrix row matched' }

Write-Host ('=== NPS Minecraft build matrix: ' + $rows.Count + ' artifacts ===') -ForegroundColor Cyan

$index = 0
foreach ($row in $rows) {
    $index++
    Write-Host ''
    Write-Host ('-- [' + $index + '/' + $rows.Count + '] ' + $row.Loader + ' / MC ' + $row.McVersion + ' (' + $row.Platform + ', ' + $row.Namespace + ')') -ForegroundColor White
    $script:Results += (Invoke-Row -Row $row)
}

# ---- summary -------------------------------------------------------------

Write-Host ''
Write-Host '=== results ===' -ForegroundColor Cyan
$script:Results | Format-Table -AutoSize Loader, McVersion, @{Label = 'Result'; Expression = { if ($_.Passed) { 'PASS' } else { 'FAIL' } } }, Note, Size, Entries

$pass = @($script:Results | Where-Object { $_.Passed }).Count
$fail = @($script:Results | Where-Object { -not $_.Passed }).Count
if ($fail -eq 0) {
    Write-Host ('TOTAL: PASS=' + $pass + ' FAIL=0') -ForegroundColor Green
} else {
    Write-Host ('TOTAL: PASS=' + $pass + ' FAIL=' + $fail) -ForegroundColor Red
}

$reportPath = Join-Path $distRoot 'build-report.json'
$artifacts = @()
foreach ($r in $script:Results) {
    $artifacts += [ordered]@{
        loader = $r.Loader
        minecraft = $r.McVersion
        file = $r.Name
        passed = $r.Passed
        bytes = $r.Size
        entries = $r.Entries
        note = $r.Note
    }
}
$report = [ordered]@{
    builtAt = (Get-Date).ToString('o')
    modVersion = $script:ModVersion
    jdk = (& $javac -version 2>&1 | Out-String).Trim()
    pass = $pass
    fail = $fail
    artifacts = $artifacts
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($reportPath, (ConvertTo-Json -InputObject $report -Depth 6), $utf8)
Write-Host ('report: ' + $reportPath)

if ($fail -gt 0) { exit 1 }
