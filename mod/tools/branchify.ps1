# NPS Minecraft - derive one source branch per Minecraft version from the integration branch.
#
# Each generated branch keeps the shared core, the build/verify tooling and exactly the
# pieces that version needs: its compat shim, the platform glue it can load, and its own
# mod metadata. The build matrix is pinned to a single version, so a branch builds only
# its own artifacts.
#
# The branches are generated, not hand-edited: fix shared code on the base branch and
# re-run this script instead of patching a version branch in place.
#
# NOTE: pure ASCII on purpose. Windows PowerShell 5.1 parses BOM-less scripts as ANSI, so
# any non-ASCII byte here would corrupt the repository path that contains a middle dot.

[CmdletBinding()]
param(
    [string[]]$Versions = @(),
    [string]$BaseBranch = 'main',
    [switch]$NoCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

$utf8 = New-Object System.Text.UTF8Encoding($false)

# The desktop app ships its own PortableGit and does not always add it to PATH.
function Resolve-Git {
    $command = Get-Command git -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        $env:NPS_GIT,
        (Join-Path $env:LOCALAPPDATA 'Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\WorkBuddy\resources\vendor\PortableGit\cmd\git.exe'),
        ('D:\Users\' + $env:USERNAME + '\AppData\Local\Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe'),
        'C:\Program Files\Git\cmd\git.exe',
        'C:\Program Files (x86)\Git\cmd\git.exe'
    )
    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) { return $path }
    }
    throw 'git not found; set NPS_GIT to git.exe or add Git to PATH'
}

$git = Resolve-Git

function Set-PinnedList {
    param([string]$Text, [string]$Name, [string]$Value)

    $pattern = '(?s)\$script:' + $Name + '\s*=\s*@\([^)]*\)'
    $replacement = '$script:' + $Name + ' = @(' + $Value + ')'
    $evaluator = [System.Text.RegularExpressions.MatchEvaluator] { param($m) $replacement }
    $result = [regex]::Replace($Text, $pattern, $evaluator)
    if ($result -eq $Text) { throw ('matrix list not found: ' + $Name) }
    return $result
}

function New-LoaderTable {
    param([string]$Version, [bool]$Fabric, [bool]$Forge, [bool]$NeoForge)

    $rows = @()
    if ($Fabric) {
        $rows += '| Fabric | Loader ' + $script:FabricInfo[$Version].Loader + '，Fabric API `' + $script:FabricApi[$Version].KeyModule + '` | `nps-minecraft-' + $script:ModVersion + '-fabric-' + $Version + '.jar` |'
    }
    if ($Forge) {
        $rows += '| Forge | ' + $script:ForgeInfo[$Version].Univ + ' | `nps-minecraft-' + $script:ModVersion + '-forge-' + $Version + '.jar` |'
    }
    if ($NeoForge) {
        $rows += '| NeoForge | ' + $script:NeoForgeInfo[$Version].Univ + ' | `nps-minecraft-' + $script:ModVersion + '-neoforge-' + $Version + '.jar` |'
    }
    return ($rows -join "`n")
}

$repo = $script:RepoRoot
Push-Location $repo
try {
    $dirty = @(& $git status --porcelain)
    if ($dirty.Count -gt 0) {
        throw 'working tree is not clean; commit or stash the pending changes before branching'
    }

    if (-not $Versions -or $Versions.Count -eq 0) { $Versions = $script:McVersions }

    $baseCommit = (& $git rev-parse --short $BaseBranch)
    $stamp = (Get-Date).ToString('yyyy-MM-dd')

    foreach ($v in $Versions) {
        if (-not $script:VersionInfo.ContainsKey($v)) { throw ('unknown Minecraft version: ' + $v) }

        $vi = $script:VersionInfo[$v]
        $compat = $vi.Compat
        $hasFabric = $script:FabricVersions -contains $v
        $hasForge = $script:ForgeVersions -contains $v
        $hasNeoForge = $script:NeoForgeVersions -contains $v

        $platforms = @()
        if ($hasFabric) { $platforms += $script:FabricInfo[$v].Platform }
        if ($hasForge) { $platforms += $script:ForgeInfo[$v].Platform }
        if ($hasNeoForge) { $platforms += $script:NeoForgeInfo[$v].Platform }
        $platforms = @($platforms | Select-Object -Unique)

        $branch = 'mc/' + $v
        & $git checkout -q -B $branch $BaseBranch
        if ($LASTEXITCODE -ne 0) { throw ('checkout failed: ' + $branch) }

        # The plugin (C#) and the legacy Fabric-only project are version independent and
        # stay on the base branch; a version branch is the mod source for one Minecraft.
        & $git rm -r -q --ignore-unmatch 'NpsDynamicIsland' 'DynamicIsland-MC'
        if ($LASTEXITCODE -ne 0) { throw 'failed to drop version-independent directories' }

        $compatDir = 'mod\compat\src\com\shiguangmeng\dynamicisland\compat'
        Get-ChildItem $compatDir -Filter '*.java' |
            Where-Object { $_.Name -ne ($compat + '.java') } |
            Remove-Item -Force

        Get-ChildItem 'mod\platforms' -Directory |
            Where-Object { $_.Name -ne 'common' -and ($platforms -notcontains $_.Name) } |
            Remove-Item -Recurse -Force

        $forgeRoot = 'mod\resources\forge'
        if ($hasForge) {
            if (Test-Path $forgeRoot) {
                Get-ChildItem $forgeRoot -Directory |
                    Where-Object { $_.Name -ne $v } |
                    Remove-Item -Recurse -Force
            }
        } elseif (Test-Path $forgeRoot) {
            Remove-Item -Recurse -Force $forgeRoot
        }

        $neoRoot = 'mod\resources\neoforge'
        if ($hasNeoForge) {
            if (Test-Path $neoRoot) {
                Get-ChildItem $neoRoot -Directory |
                    Where-Object { $_.Name -ne $v } |
                    Remove-Item -Recurse -Force
            }
        } elseif (Test-Path $neoRoot) {
            Remove-Item -Recurse -Force $neoRoot
        }

        $matrixPath = 'mod\tools\matrix.ps1'
        $text = [System.IO.File]::ReadAllText($matrixPath)
        $text = Set-PinnedList $text 'McVersions' ("'" + $v + "'")
        $text = Set-PinnedList $text 'FabricVersions' $(if ($hasFabric) { "'" + $v + "'" } else { '' })
        $text = Set-PinnedList $text 'ForgeVersions' $(if ($hasForge) { "'" + $v + "'" } else { '' })
        $text = Set-PinnedList $text 'NeoForgeVersions' $(if ($hasNeoForge) { "'" + $v + "'" } else { '' })
        [System.IO.File]::WriteAllText($matrixPath, $text, $utf8)

        $templatePath = 'mod\tools\branch-readme.template.md'
        $template = [System.IO.File]::ReadAllText($templatePath, [System.Text.Encoding]::UTF8)
        $readme = $template.Replace('{{VERSION}}', $v)
        $readme = $readme.Replace('{{LOADER_TABLE}}', (New-LoaderTable $v $hasFabric $hasForge $hasNeoForge))
        $readme = $readme.Replace('{{COMPAT}}', $compat)
        $readme = $readme.Replace('{{JAVA}}', $vi.JavaRelease)
        $readme = $readme.Replace('{{MAIN_COMMIT}}', $baseCommit)
        $readme = $readme.Replace('{{GENERATED_AT}}', $stamp)
        [System.IO.File]::WriteAllText((Join-Path $repo 'README.md'), $readme, $utf8)

        & $git add -A
        if ($LASTEXITCODE -ne 0) { throw 'git add failed' }

        if (-not $NoCommit) {
            & $git commit -q -m ('chore(branch): pin Minecraft ' + $v)
            if ($LASTEXITCODE -ne 0) { throw ('commit failed: ' + $branch) }
        }
        Write-Host ('[branch] ' + $branch + ' <- ' + $BaseBranch + ' @ ' + $baseCommit + ' (' + ($platforms -join ', ') + ', ' + $compat + ')')
    }

    & $git checkout -q $BaseBranch
    if ($LASTEXITCODE -ne 0) { throw ('failed to return to ' + $BaseBranch) }
    Write-Host ('[branch] done: ' + $Versions.Count + ' branches')
} finally {
    Pop-Location
}
