[CmdletBinding()]
param(
    [switch] $Online
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
$arguments = @('--no-daemon', '--console=plain', 'verifyDependencyProvenance')
if (-not $Online) {
    $arguments = @('--offline') + $arguments
}

Push-Location $repositoryRoot
try {
    & $wrapper @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle provenance verification failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
