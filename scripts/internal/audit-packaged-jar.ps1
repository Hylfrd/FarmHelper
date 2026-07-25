[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $FirstArtifact,

    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $repositoryRoot 'build\libs\FarmHelper-26.1.2.jar'
}

function Resolve-InputPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        $Path = Join-Path $repositoryRoot $Path
    }
    return [IO.Path]::GetFullPath($Path)
}

function Get-ArchiveAudit {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entries = @($archive.Entries)
        $names = @($entries | ForEach-Object FullName)
        if (@($names | Sort-Object -Unique).Count -ne $names.Count) {
            throw "Duplicate ZIP entry names in $Path"
        }

        $requiredEntries = @('META-INF/LICENSE', 'META-INF/NOTICE', 'fabric.mod.json')
        foreach ($required in $requiredEntries) {
            if ($null -eq $archive.GetEntry($required)) {
                throw "Required JAR entry is missing: $required"
            }
        }

        $forbiddenPattern = '(?i)(^|/)(AGENTS\.md|progress\.md|test\.md|.*\.pem|.*\.p12|.*\.key|.*api[_-]?key.*|\.env.*)$'
        $forbidden = @($names | Where-Object { $_ -match $forbiddenPattern })
        if ($forbidden.Count -gt 0) {
            throw "Forbidden JAR content found: $($forbidden -join ', ')"
        }

        $metadataEntry = $archive.GetEntry('fabric.mod.json')
        $reader = [IO.StreamReader]::new($metadataEntry.Open())
        try {
            $metadata = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
        if ([string] $metadata.id -ne 'farmhelper' -or
            [string] $metadata.version -ne '0.1.0' -or
            [string] $metadata.environment -ne 'client' -or
            @($metadata.entrypoints.client) -notcontains
                'dev.hylfrd.farmhelper.client.FarmHelperClient') {
            throw 'fabric.mod.json content audit failed.'
        }

        return [pscustomobject]@{
            records = @($entries | ForEach-Object {
                "$($_.FullName)|$($_.Length)|$($_.CompressedLength)"
            } | Sort-Object)
            entryCount = $entries.Count
            id = [string] $metadata.id
            version = [string] $metadata.version
        }
    } finally {
        $archive.Dispose()
    }
}

$firstPath = Resolve-InputPath -Path $FirstArtifact
$candidatePath = Resolve-InputPath -Path $JarPath
foreach ($path in @($firstPath, $candidatePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "JAR is missing: $path"
    }
}

$firstHash = (Get-FileHash -LiteralPath $firstPath -Algorithm SHA256).Hash.ToUpperInvariant()
$candidateHash = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToUpperInvariant()
$firstSize = (Get-Item -LiteralPath $firstPath).Length
$candidateSize = (Get-Item -LiteralPath $candidatePath).Length
if ($firstHash -ne $candidateHash -or $firstSize -ne $candidateSize) {
    throw "Deterministic artifact mismatch: first=$firstHash/$firstSize candidate=$candidateHash/$candidateSize"
}

$firstAudit = Get-ArchiveAudit -Path $firstPath
$candidateAudit = Get-ArchiveAudit -Path $candidatePath
if (($firstAudit.records -join "`n") -ne ($candidateAudit.records -join "`n")) {
    throw 'Deterministic archive entry/content metadata mismatch.'
}

Write-Output 'DETERMINISTIC_JAR_OK'
Write-Output "size=$candidateSize"
Write-Output "sha256=$candidateHash"
Write-Output "entries=$($candidateAudit.entryCount)"
Write-Output "modId=$($candidateAudit.id)"
Write-Output "modVersion=$($candidateAudit.version)"
