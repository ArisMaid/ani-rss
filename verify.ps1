[CmdletBinding()]
param(
    [string] $PnpmCommand = $env:ANI_RSS_PNPM
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$RepoRoot = $PSScriptRoot
$UiRoot = Join-Path $RepoRoot 'ani-rss-ui'
$MavenImage = 'maven:3.9.12-eclipse-temurin-17'
$OsvImage = 'ghcr.io/google/osv-scanner:v2.4.0'
$AppImage = 'ani-rss:verify-local'
$SmokeContainer = "ani-rss-smoke-$PID"

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

function Remove-SmokeContainer {
    $existing = & docker ps -a --filter "name=^/$SmokeContainer$" --format '{{.Names}}'
    if ($existing -eq $SmokeContainer) {
        & docker rm -f $SmokeContainer | Out-Null
    }
}

if ([string]::IsNullOrWhiteSpace($PnpmCommand)) {
    $pnpm = Get-Command pnpm -ErrorAction SilentlyContinue
    if ($null -eq $pnpm) {
        throw 'pnpm is required; pass -PnpmCommand or set ANI_RSS_PNPM when it is not on PATH'
    }
    $PnpmCommand = $pnpm.Source
}
if (-not (Get-Command $PnpmCommand -ErrorAction SilentlyContinue)) {
    throw "pnpm command was not found: $PnpmCommand"
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for the Java 17 gate, OSV scan, and smoke test'
}

Write-Host '== Frontend locked install =='
Invoke-Checked $PnpmCommand @('install', '--frozen-lockfile') $UiRoot
Invoke-Checked $PnpmCommand @('lint') $UiRoot
Invoke-Checked $PnpmCommand @('typecheck') $UiRoot
Invoke-Checked $PnpmCommand @('test') $UiRoot
Invoke-Checked $PnpmCommand @('build') $UiRoot

Write-Host '== Java 17 Maven verify (tests, JaCoCo, SpotBugs) =='
Invoke-Checked docker @(
    'run', '--rm',
    '-v', "${RepoRoot}:/workspace",
    '-v', "${HOME}\.m2:/root/.m2",
    '-w', '/workspace',
    $MavenImage,
    'mvn', '-B', '-Dskip.frontend=true', 'verify'
) $RepoRoot

Write-Host '== Production dependency audits =='
Invoke-Checked $PnpmCommand @('audit', '--prod', '--audit-level', 'low') $UiRoot
if ($env:ANI_RSS_SKIP_OSV -ne '1') {
    Invoke-Checked docker @(
        'run', '--rm',
        '-v', "${RepoRoot}:/src:ro",
        '-w', '/src',
        $OsvImage,
        'scan', 'source', '-L', '/src/ani-rss-application/target/bom.json'
    ) $RepoRoot
}

if ($env:ANI_RSS_SKIP_DOCKER_SMOKE -ne '1') {
    Write-Host '== Docker smoke test =='
    Invoke-Checked docker @('build', '--file', 'docker/Dockerfile', '--tag', $AppImage, '.') $RepoRoot
    Remove-SmokeContainer
    try {
        Invoke-Checked docker @(
            'run', '-d', '--name', $SmokeContainer,
            '--tmpfs', '/config:rw,noexec,nosuid,size=64m',
            '-p', '127.0.0.1::7789',
            $AppImage
        ) $RepoRoot

        $binding = (& docker port $SmokeContainer '7789/tcp' | Select-Object -First 1).Trim()
        $match = [regex]::Match($binding, '(\d+)$')
        if (-not $match.Success) {
            throw "Unable to determine smoke-test port from: $binding"
        }
        $uri = "http://127.0.0.1:$($match.Groups[1].Value)/"
        $ready = $false
        for ($attempt = 1; $attempt -le 45; $attempt++) {
            try {
                $response = Invoke-WebRequest -Uri $uri -Method Get -TimeoutSec 2 -UseBasicParsing
                if ($response.StatusCode -eq 200 -and $response.Content -match '<div id="app"') {
                    $ready = $true
                    break
                }
            }
            catch {
                Start-Sleep -Seconds 2
            }
        }
        if (-not $ready) {
            & docker logs $SmokeContainer --tail 200
            throw 'ANI-RSS did not become ready within 90 seconds'
        }
    }
    finally {
        Remove-SmokeContainer
    }
}

Write-Host 'All local verification gates passed.'
