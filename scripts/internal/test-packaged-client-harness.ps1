[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$verificationScript = Join-Path $PSScriptRoot 'verify-packaged-client.ps1'
$librariesDirectory = Join-Path $repositoryRoot 'build\libs'
$candidatePath = Join-Path $librariesDirectory 'FarmHelper-26.1.2.jar'

if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
    throw "Build the candidate JAR first: $candidatePath"
}
$sha256 = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToUpperInvariant()
$powershell = Join-Path $PSHOME 'pwsh.exe'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ("farmhelper-packaged-client-negative-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedMessage,

        [Parameter(Mandatory = $true)]
        [string] $FixtureDirectory,

        [Parameter(Mandatory = $true)]
        [string] $Hash
    )

    $output = @(
        & $powershell -NoProfile -File $verificationScript `
            -ExpectedSha256 $Hash `
            -JarPath $candidatePath `
            -RunDirectory $FixtureDirectory `
            -PreflightOnly 2>&1
    )
    $exitCode = $LASTEXITCODE
    $text = $output -join "`n"
    if ($exitCode -eq 0) {
        throw "$Name unexpectedly passed."
    }
    if ($text -notmatch [Regex]::Escape($ExpectedMessage)) {
        throw "$Name failed for the wrong reason: $text"
    }
    Write-Output "NEGATIVE_OK $Name"
}

try {
    $wrongHashFixture = Join-Path $fixtureRoot 'wrong-hash'
    New-Item -ItemType Directory -Path (Join-Path $wrongHashFixture 'mods') -Force | Out-Null
    Invoke-ExpectedFailure `
        -Name 'wrong hash' `
        -ExpectedMessage 'Candidate SHA-256 mismatch' `
        -FixtureDirectory $wrongHashFixture `
        -Hash ('0' * 64)

    $duplicateFixture = Join-Path $fixtureRoot 'duplicate'
    $duplicateMods = Join-Path $duplicateFixture 'mods'
    New-Item -ItemType Directory -Path $duplicateMods -Force | Out-Null
    Copy-Item -LiteralPath $candidatePath -Destination (Join-Path $duplicateMods 'FarmHelper-duplicate.jar')
    Invoke-ExpectedFailure `
        -Name 'duplicate FarmHelper mod' `
        -ExpectedMessage 'Duplicate FarmHelper mod' `
        -FixtureDirectory $duplicateFixture `
        -Hash $sha256

    $nonCleanFixture = Join-Path $fixtureRoot 'non-clean'
    $nonCleanMods = Join-Path $nonCleanFixture 'mods'
    New-Item -ItemType Directory -Path $nonCleanMods -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $nonCleanMods 'README.txt') -Value 'fixture' -Encoding ascii
    Invoke-ExpectedFailure `
        -Name 'non-clean mods directory' `
        -ExpectedMessage 'Mods directory is not clean' `
        -FixtureDirectory $nonCleanFixture `
        -Hash $sha256

    Write-Output 'PACKAGED_CLIENT_NEGATIVE_TESTS_OK count=3'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
