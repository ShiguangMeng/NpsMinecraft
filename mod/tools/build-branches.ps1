# NPS Minecraft - build every generated version branch and aggregate the results.
#
# Each branch pins the matrix to one Minecraft version, so this walks mc/* and runs the
# normal build pipeline inside it. The per-branch report is copied out before the next
# checkout overwrites dist/build-report.json.
#
# NOTE: pure ASCII on purpose (Windows PowerShell 5.1 parses BOM-less scripts as ANSI).

[CmdletBinding()]
param(
    [string[]]$Branches = @(),
    [string]$BaseBranch = 'main'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'matrix.ps1')

function Resolve-Git {
    $command = Get-Command git -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @(
        $env:NPS_GIT,
        (Join-Path $env:LOCALAPPDATA 'Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe'),
        ('D:\Users\' + $env:USERNAME + '\AppData\Local\Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe'),
        'C:\Program Files\Git\cmd\git.exe'
    )
    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) { return $path }
    }
    throw 'git not found; set NPS_GIT to git.exe or add Git to PATH'
}

$git = Resolve-Git
$repo = $script:RepoRoot
$buildScript = Join-Path $PSScriptRoot 'build.ps1'
$reportPath = Join-Path $repo 'dist\build-report.json'

Push-Location $repo
try {
    if (-not $Branches -or $Branches.Count -eq 0) {
        $Branches = @(& $git branch --list 'mc/*' | ForEach-Object { $_.Trim().TrimStart('*').Trim() })
    }
    if ($Branches.Count -eq 0) { throw 'no mc/* branches found; run branchify.ps1 first' }

    $rows = @()
    foreach ($branch in $Branches) {
        Write-Host ('[matrix] ' + $branch)
        & $git checkout -q $branch
        if ($LASTEXITCODE -ne 0) { throw ('checkout failed: ' + $branch) }

        & powershell -NoProfile -ExecutionPolicy Bypass -File $buildScript | Out-Null
        $code = $LASTEXITCODE

        if (-not (Test-Path $reportPath)) { throw ('build produced no report: ' + $branch) }
        $report = Get-Content $reportPath -Raw | ConvertFrom-Json

        $rows += New-Object psobject -Property @{
            Branch    = $branch
            ExitCode  = $code
            Pass      = $report.pass
            Fail      = $report.fail
            Artifacts = @($report.artifacts | ForEach-Object { $_.file })
        }
        Write-Host ('[matrix] ' + $branch + ' pass=' + $report.pass + ' fail=' + $report.fail)
    }

    & $git checkout -q $BaseBranch
    if ($LASTEXITCODE -ne 0) { throw ('failed to return to ' + $BaseBranch) }

    $totalPass = ($rows | Measure-Object -Property Pass -Sum).Sum
    $totalFail = ($rows | Measure-Object -Property Fail -Sum).Sum
    $summary = [ordered]@{
        builtAt   = (Get-Date).ToString('o')
        branches  = $rows.Count
        pass      = $totalPass
        fail      = $totalFail
        detail    = $rows
    }
    $json = ConvertTo-Json -InputObject $summary -Depth 6
    [System.IO.File]::WriteAllText((Join-Path $repo 'dist\branch-report.json'), $json, (New-Object System.Text.UTF8Encoding($false)))

    $rows | ForEach-Object { Write-Host ('{0,-14} pass={1,-3} fail={2}' -f $_.Branch, $_.Pass, $_.Fail) }
    Write-Host ('TOTAL branches=' + $rows.Count + ' pass=' + $totalPass + ' fail=' + $totalFail)
    Write-Host ('report: ' + (Join-Path $repo 'dist\branch-report.json'))

    if ($totalFail -gt 0 -or ($rows | Where-Object { $_.ExitCode -ne 0 })) { exit 1 }
} finally {
    Pop-Location
}
