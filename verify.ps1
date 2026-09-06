[CmdletBinding()]
param(
    [string] $PnpmCommand = $env:ANI_RSS_PNPM,
    [string] $MavenCommand = $env:ANI_RSS_MVN
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$RepoRoot = $PSScriptRoot
$UiRoot = Join-Path $RepoRoot 'ani-rss-ui'

function Resolve-Tool {
    param(
        [Parameter(Mandatory)] [string] $Requested,
        [Parameter(Mandatory)] [string] $Name
    )

    if ([string]::IsNullOrWhiteSpace($Requested)) {
        $found = Get-Command $Name -ErrorAction SilentlyContinue
        if ($null -eq $found) {
            throw "$Name command was not found; pass -${Name}Command or set ANI_RSS_$($Name.ToUpper())"
        }
        return $found.Source
    }
    if (-not (Get-Command $Requested -ErrorAction SilentlyContinue)) {
        throw "$Name command was not found: $Requested"
    }
    return $Requested
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)] [string] $Command,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string] $WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $Command $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

$PnpmCommand = Resolve-Tool $PnpmCommand 'pnpm'
$MavenCommand = Resolve-Tool $MavenCommand 'mvn'

Write-Host '== Frontend locked install and tests =='
Invoke-Checked $PnpmCommand @('install', '--frozen-lockfile') $UiRoot
Invoke-Checked $PnpmCommand @('test') $UiRoot
Invoke-Checked $PnpmCommand @('build:verify') $UiRoot
Invoke-Checked $PnpmCommand @('check:bundle') $UiRoot

Write-Host '== Java Maven verify (no clean step) =='
Invoke-Checked $MavenCommand @('-B', '-Dskip.frontend=true', 'verify', '--file', 'pom.xml') $RepoRoot

if ($env:ANI_RSS_SKIP_AUDIT -ne '1') {
    Write-Host '== Production dependency audit =='
    Invoke-Checked $PnpmCommand @('audit', '--prod', '--audit-level', 'high') $UiRoot
}

Write-Host 'All local verification gates passed.'
