[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedSha256,

    [string] $JarPath,

    [string] $RunDirectory,

    [string] $GradleUserHome,

    [switch] $PreflightOnly,

    [ValidateRange(15, 600)]
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
$gradlePropertiesPath = Join-Path $repositoryRoot 'gradle.properties'
$approvedFabricApiCoordinate = 'net.fabricmc.fabric-api:fabric-api'
$approvedFabricApiVersion = '0.153.0+26.1.2'
$approvedFabricApiSha256 = '2A604CCC66C1294F860ACB8D0763C8887E927B3ED34AA262AC79E26D8626B94C'

function Read-GradleProperties {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#!\s][^=]*)\s*=\s*(.*?)\s*$') {
            $properties[$Matches[1].Trim()] = $Matches[2]
        }
    }
    return $properties
}

function Resolve-RepositoryPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $candidate = $Path
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $repositoryRoot $candidate
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Assert-NoReparsePathComponents {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $rootPath = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($rootPath)) {
        throw "${Description} has no usable filesystem root: $Path"
    }

    $currentPath = $rootPath.TrimEnd('\')
    $relativePath = $fullPath.Substring($rootPath.Length)
    foreach ($component in $relativePath.Split('\', [StringSplitOptions]::RemoveEmptyEntries)) {
        $currentPath = Join-Path $currentPath $component
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) {
            break
        }
        $attributes = [IO.FileAttributes] $item.Attributes
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "${Description} contains a reparse-point component: $currentPath"
        }
    }
}

function Get-CanonicalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    Assert-NoReparsePathComponents -Path $Path -Description 'Path'
    $item = Get-Item -LiteralPath $Path -Force
    $attributes = [IO.FileAttributes] $item.Attributes
    if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Reparse-point paths are not accepted: $Path"
    }
    return [IO.Path]::GetFullPath($item.FullName)
}

function Assert-PathLexicallyUnder {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Root,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $normalizedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (-not $normalizedPath.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
        -not $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Description} must be under ${normalizedRoot}: ${normalizedPath}"
    }
}

function Get-Sha1Hash {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $algorithm = [Security.Cryptography.SHA1]::Create()
    $stream = $null
    try {
        $stream = [IO.File]::OpenRead($Path)
        return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
        $algorithm.Dispose()
    }
}

function Assert-PathUnder {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Root,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $normalizedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    Assert-NoReparsePathComponents -Path $Root -Description "${Description} root"
    Assert-NoReparsePathComponents -Path $Path -Description $Description
    if (-not $normalizedPath.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
        -not $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Description} must be under ${normalizedRoot}: $normalizedPath"
    }
}

function Assert-SafeAssetIndexId {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $AssetIndexId
    )

    $invalidFileNameChars = [IO.Path]::GetInvalidFileNameChars()
    $isRootedOrPathLike = [IO.Path]::IsPathRooted($AssetIndexId) -or
        $AssetIndexId -match '^[\\/]' -or
        $AssetIndexId -match '^[A-Za-z]:'
    $hasSeparator = $AssetIndexId.IndexOfAny([char[]] @('\', '/')) -ge 0

    if ([string]::IsNullOrWhiteSpace($AssetIndexId) -or
        $AssetIndexId -ne $AssetIndexId.Trim()) {
        throw "Minecraft asset index id must be a non-empty filename component: '$AssetIndexId'"
    }
    if ($isRootedOrPathLike) {
        throw "Minecraft asset index id must not be rooted or path-like: '$AssetIndexId'"
    }
    if ($hasSeparator) {
        throw "Minecraft asset index id must not contain path separators: '$AssetIndexId'"
    }
    if ($AssetIndexId -in @('.', '..')) {
        throw "Minecraft asset index id must not be '.' or '..': '$AssetIndexId'"
    }
    if ($AssetIndexId.IndexOfAny($invalidFileNameChars) -ge 0 -or
        $AssetIndexId -notmatch '^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9_-])?$') {
        throw "Minecraft asset index id is not a valid filename component: '$AssetIndexId'"
    }
    if ($AssetIndexId -match '(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {
        throw "Minecraft asset index id uses a reserved filename: '$AssetIndexId'"
    }
    if ($AssetIndexId.Length -gt 240) {
        throw "Minecraft asset index id is too long for an index filename: '$AssetIndexId'"
    }
}

function Read-FabricModMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('fabric.mod.json')
        if ($null -eq $entry) {
            throw "JAR has no fabric.mod.json: $Path"
        }

        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try {
            return ($reader.ReadToEnd() | ConvertFrom-Json)
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

function Resolve-ApprovedFabricApi {
    param(
        [Parameter(Mandatory = $true)]
        [string] $GradleHome,

        [Parameter(Mandatory = $true)]
        [hashtable] $Properties
    )

    Assert-NoReparsePathComponents -Path $GradleHome -Description 'Gradle user home'

    if ([string] $Properties['fabric_api_version'] -ne $approvedFabricApiVersion) {
        throw "fabric_api_version is not the audited version $approvedFabricApiVersion."
    }

    $provenancePath = Join-Path $repositoryRoot 'dependency-provenance.json'
    if (-not (Test-Path -LiteralPath $provenancePath -PathType Leaf)) {
        throw "Dependency provenance is missing: $provenancePath"
    }
    $provenance = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
    $entries = @($provenance.runtimeDependencies | Where-Object {
        [string] $_.coordinate -eq $approvedFabricApiCoordinate
    })
    if ($entries.Count -ne 1) {
        throw "Expected exactly one provenance entry for $approvedFabricApiCoordinate; found $($entries.Count)."
    }
    $entry = $entries[0]
    if ([string] $entry.declaredVersion -ne $approvedFabricApiVersion -or
        [string] $entry.resolvedVersion -ne $approvedFabricApiVersion -or
        [string] $entry.source -ne 'Fabric Maven' -or
        $entry.direct -ne $true -or
        $entry.bundled -ne $false) {
        throw "Provenance for $approvedFabricApiCoordinate is not the audited direct external dependency."
    }

    $artifactRoot = Join-Path $GradleHome (
        "caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$approvedFabricApiVersion")
    Assert-NoReparsePathComponents -Path $artifactRoot -Description 'Fabric API cache'
    if (-not (Test-Path -LiteralPath $artifactRoot -PathType Container)) {
        throw "Offline Fabric API cache is missing: $artifactRoot"
    }

    $artifactName = "fabric-api-$approvedFabricApiVersion.jar"
    $cachedEntries = @(Get-ChildItem -LiteralPath $artifactRoot -Force -Recurse -ErrorAction Stop)
    foreach ($cachedEntry in $cachedEntries) {
        Assert-NoReparsePathComponents -Path $cachedEntry.FullName -Description 'Fabric API cache entry'
    }
    $artifacts = @($cachedEntries | Where-Object {
        -not $_.PSIsContainer -and $_.Name -eq $artifactName
    })
    if ($artifacts.Count -ne 1) {
        throw "Expected exactly one cached $approvedFabricApiCoordinate artifact; found $($artifacts.Count)."
    }

    $artifactPath = Get-CanonicalPath -Path $artifacts[0].FullName
    $actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -ne $approvedFabricApiSha256) {
        throw "Fabric API SHA-256 mismatch: expected $approvedFabricApiSha256, found $actualHash."
    }

    $metadata = Read-FabricModMetadata -Path $artifactPath
    if ([string] $metadata.id -ne 'fabric-api' -or
        [string] $metadata.version -ne $approvedFabricApiVersion) {
        throw "Cached Fabric API metadata does not match ${approvedFabricApiCoordinate}:$approvedFabricApiVersion."
    }

    return [pscustomobject]@{
        coordinate = "$approvedFabricApiCoordinate`:$approvedFabricApiVersion"
        version = $approvedFabricApiVersion
        source = $artifactPath
        sha256 = $actualHash
        size = (Get-Item -LiteralPath $artifactPath).Length
        modId = [string] $metadata.id
        modVersion = [string] $metadata.version
        provenanceSource = [string] $entry.source
        direct = [bool] $entry.direct
        bundled = [bool] $entry.bundled
    }
}

function Assert-CandidateArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedHash,

        [Parameter(Mandatory = $true)]
        [string] $LibrariesDirectory
    )

    if ($ExpectedHash -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'ExpectedSha256 must be exactly 64 hexadecimal characters.'
    }
    Assert-NoReparsePathComponents -Path $LibrariesDirectory -Description 'Libraries directory'
    Assert-NoReparsePathComponents -Path $Path -Description 'Candidate JAR'
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Candidate JAR is missing: $Path"
    }
    if ([IO.Path]::GetExtension($Path) -ne '.jar') {
        throw "Candidate is not a JAR: $Path"
    }

    $candidate = Get-CanonicalPath -Path $Path
    $actualHash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToUpperInvariant()
    $normalizedExpected = $ExpectedHash.ToUpperInvariant()
    if ($actualHash -ne $normalizedExpected) {
        throw "Candidate SHA-256 mismatch: expected $normalizedExpected, found $actualHash."
    }

    $matching = [System.Collections.Generic.List[string]]::new()
    foreach ($jar in @(Get-ChildItem -LiteralPath $LibrariesDirectory -Force -File -Filter '*.jar')) {
        Assert-NoReparsePathComponents -Path $jar.FullName -Description 'Candidate library entry'
        $hash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($hash -eq $normalizedExpected) {
            $matching.Add((Get-CanonicalPath -Path $jar.FullName))
        }
    }
    if ($matching.Count -ne 1) {
        throw "Expected SHA-256 matched $($matching.Count) JARs under $LibrariesDirectory; refusing ambiguous artifact selection: $($matching -join ', ')"
    }
    if (-not $matching[0].Equals($candidate, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Expected SHA-256 belongs to $($matching[0]), not the requested candidate $candidate."
    }

    $metadata = Read-FabricModMetadata -Path $candidate
    if ([string] $metadata.id -ne 'farmhelper') {
        throw "Candidate fabric.mod.json id is '$($metadata.id)', expected 'farmhelper'."
    }
    if ([string] $metadata.environment -ne 'client') {
        throw "Candidate environment is '$($metadata.environment)', expected 'client'."
    }
    $clientEntrypoints = @($metadata.entrypoints.client)
    if (-not ($clientEntrypoints -contains 'dev.hylfrd.farmhelper.client.FarmHelperClient')) {
        throw 'Candidate does not declare the expected FarmHelper client entrypoint.'
    }

    return [pscustomobject]@{
        path = $candidate
        sha256 = $actualHash
        size = (Get-Item -LiteralPath $candidate).Length
        modId = [string] $metadata.id
        modVersion = [string] $metadata.version
    }
}

function Assert-CleanModsDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ModsDirectory
    )

    Assert-NoReparsePathComponents -Path $ModsDirectory -Description 'Mods directory'
    if (-not (Test-Path -LiteralPath $ModsDirectory)) {
        throw "Mods directory is missing: $ModsDirectory"
    }
    if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
        throw "Mods path is not a directory: $ModsDirectory"
    }

    $entries = @(Get-ChildItem -LiteralPath $ModsDirectory -Force)
    $farmHelperDuplicates = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $entries) {
        Assert-NoReparsePathComponents -Path $entry.FullName -Description 'Mods directory entry'
        if ($entry.PSIsContainer -or [IO.Path]::GetExtension($entry.Name) -ne '.jar') {
            continue
        }

        try {
            $metadata = Read-FabricModMetadata -Path $entry.FullName
            if ([string] $metadata.id -eq 'farmhelper') {
                $farmHelperDuplicates.Add($entry.FullName)
            }
        } catch {
            # A non-Fabric or malformed JAR is still rejected as a non-clean entry below.
        }
    }
    if ($farmHelperDuplicates.Count -gt 0) {
        throw "Duplicate FarmHelper mod in mods directory: $($farmHelperDuplicates -join ', ')"
    }
    if ($entries.Count -ne 0) {
        throw "Mods directory is not clean; expected zero entries, found: $($entries.Name -join ', ')"
    }
}

function Assert-OfflineAssets {
    param(
        [Parameter(Mandatory = $true)]
        [string] $MinecraftVersion,

        [Parameter(Mandatory = $true)]
        [string] $GradleHome
    )

    Assert-NoReparsePathComponents -Path $GradleHome -Description 'Gradle user home'
    $metadataPath = Join-Path $GradleHome "caches\fabric-loom\$MinecraftVersion\mojang_minecraft_info.json"
    Assert-NoReparsePathComponents -Path $metadataPath -Description 'Minecraft metadata'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Offline Minecraft metadata is missing: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($null -eq $metadata.assetIndex -or [string]::IsNullOrWhiteSpace([string] $metadata.assetIndex.id)) {
        throw "Minecraft metadata has no usable asset index: $metadataPath"
    }

    if ($metadata.assetIndex.id -isnot [string]) {
        throw "Minecraft metadata asset index id must be a JSON string: $metadataPath"
    }
    $assetIndexId = [string] $metadata.assetIndex.id
    Assert-SafeAssetIndexId -AssetIndexId $assetIndexId

    $assetsRoot = Join-Path $GradleHome 'caches\fabric-loom\assets'
    Assert-NoReparsePathComponents -Path $assetsRoot -Description 'Loom asset cache'
    $indexesRoot = Join-Path $assetsRoot 'indexes'
    Assert-PathLexicallyUnder `
        -Path $indexesRoot `
        -Root $assetsRoot `
        -Description 'Loom asset indexes root'
    Assert-NoReparsePathComponents -Path $indexesRoot -Description 'Loom asset indexes root'
    if (-not (Test-Path -LiteralPath $indexesRoot -PathType Container)) {
        throw "Offline asset index directory is missing: $indexesRoot"
    }
    $canonicalIndexesRoot = Get-CanonicalPath -Path $indexesRoot

    $indexName = if ($assetIndexId -eq $MinecraftVersion) {
        $MinecraftVersion
    } else {
        "$MinecraftVersion-$assetIndexId"
    }
    $indexFileName = "$indexName.json"
    if ($indexFileName.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or
        $indexFileName.Length -gt 255) {
        throw "Minecraft asset index filename is invalid: $indexFileName"
    }
    $indexPath = Join-Path $canonicalIndexesRoot $indexFileName
    Assert-PathLexicallyUnder `
        -Path $indexPath `
        -Root $canonicalIndexesRoot `
        -Description 'Minecraft asset index'
    if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
        throw "Offline asset index is missing: $indexPath"
    }
    Assert-NoReparsePathComponents -Path $indexPath -Description 'Minecraft asset index'
    if ([string] $metadata.assetIndex.sha1 -notmatch '^[0-9a-fA-F]{40}$') {
        throw "Minecraft metadata has an invalid asset index SHA-1: $metadataPath"
    }
    $indexHash = Get-Sha1Hash -Path $indexPath
    if ($indexHash -ne ([string] $metadata.assetIndex.sha1).ToLowerInvariant()) {
        throw "Offline asset index SHA-1 mismatch: $indexPath"
    }

    $index = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json
    if ($null -eq $index.objects) {
        throw "Minecraft asset index has no objects map: $indexPath"
    }
    $assetEntries = @($index.objects.PSObject.Properties)
    $missing = [System.Collections.Generic.List[string]]::new()
    $corrupt = [System.Collections.Generic.List[string]]::new()
    $objectsRoot = Join-Path $assetsRoot 'objects'
    # Hash-derived components need only a lexical boundary check after the root walk.
    Assert-PathLexicallyUnder `
        -Path $objectsRoot `
        -Root $assetsRoot `
        -Description 'Loom asset objects root'
    Assert-NoReparsePathComponents -Path $objectsRoot -Description 'Loom asset objects root'

    $objectRootExists = Test-Path -LiteralPath $objectsRoot -PathType Container
    $prefixStates = @{}
    $objectStates = @{}
    $prefixDirectoryGuardCalls = 0
    $leafGuardCalls = 0
    $sha1ObjectCount = 0
    $verifiedObjectCount = 0
    $scanStopwatch = [Diagnostics.Stopwatch]::StartNew()
    foreach ($asset in $assetEntries) {
        $hash = [string] $asset.Value.hash
        if ($hash -notmatch '^[0-9a-fA-F]{40}$') {
            throw "Invalid asset hash in ${indexPath}: $hash"
        }

        $prefix = $hash.Substring(0, 2)
        $prefixPath = Join-Path $objectsRoot $prefix
        $objectPath = Join-Path $prefixPath $hash
        $objectKey = $hash.ToLowerInvariant()
        Assert-PathLexicallyUnder `
            -Path $objectPath `
            -Root $objectsRoot `
            -Description 'Asset object'

        if (-not $objectRootExists) {
            $objectStates[$objectKey] = [pscustomobject]@{ present = $false }
            $missing.Add($asset.Name)
            continue
        }

        if (-not $prefixStates.ContainsKey($prefixPath)) {
            $prefixDirectoryGuardCalls++
            $prefixItem = Get-Item -LiteralPath $prefixPath -Force -ErrorAction SilentlyContinue
            if ($null -eq $prefixItem -or -not [bool] $prefixItem.PSIsContainer) {
                $prefixStates[$prefixPath] = $false
            } else {
                $prefixAttributes = [IO.FileAttributes] $prefixItem.Attributes
                if (($prefixAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "Asset object contains a reparse-point component: $prefixPath"
                }
                $prefixStates[$prefixPath] = $true
            }
        }
        if (-not [bool] $prefixStates[$prefixPath]) {
            $objectStates[$objectKey] = [pscustomobject]@{ present = $false }
            $missing.Add($asset.Name)
            continue
        }

        if ($objectStates.ContainsKey($objectKey)) {
            $objectState = $objectStates[$objectKey]
            if (-not [bool] $objectState.present) {
                $missing.Add($asset.Name)
                continue
            }
            $verifiedObjectCount++
            if ([string] $objectState.actualHash -ne $objectKey) {
                $corrupt.Add("$($asset.Name): expected $hash, found $($objectState.actualHash)")
            }
            continue
        }

        $leafGuardCalls++
        $leafItem = Get-Item -LiteralPath $objectPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $leafItem -or [bool] $leafItem.PSIsContainer) {
            $objectStates[$objectKey] = [pscustomobject]@{ present = $false }
            $missing.Add($asset.Name)
            continue
        }
        $leafAttributes = [IO.FileAttributes] $leafItem.Attributes
        if (($leafAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Asset object contains a reparse-point component: $objectPath"
        }

        $actualHash = Get-Sha1Hash -Path $objectPath
        $objectStates[$objectKey] = [pscustomobject]@{
            present = $true
            actualHash = $actualHash
        }
        $sha1ObjectCount++
        $verifiedObjectCount++
        if ($actualHash -ne $hash.ToLowerInvariant()) {
            $corrupt.Add("$($asset.Name): expected $hash, found $actualHash")
        }
    }
    $scanStopwatch.Stop()
    if ($missing.Count -gt 0) {
        throw "Offline asset cache is incomplete; missing $($missing.Count) objects, including $($missing[0])."
    }
    if ($corrupt.Count -gt 0) {
        throw "Offline asset cache has SHA-1 mismatched objects; found $($corrupt[0])."
    }

    return [pscustomobject]@{
        root = $assetsRoot
        index = $indexName
        objectCount = $assetEntries.Count
        verifiedObjectCount = $verifiedObjectCount
        traversal = [ordered]@{
            guardCalls = [ordered]@{
                objectsRoot = 1
                prefixDirectories = $prefixDirectoryGuardCalls
                leaves = $leafGuardCalls
            }
            sha1Objects = $sha1ObjectCount
            scanMilliseconds = [int64] $scanStopwatch.ElapsedMilliseconds
        }
    }
}

function Get-ProcessSnapshot {
    return @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CommandLine, CreationDate)
}

function Get-DescendantProcessIds {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [Parameter(Mandatory = $true)]
        [object[]] $Snapshot
    )

    $descendants = [System.Collections.Generic.List[int]]::new()
    $frontier = [System.Collections.Generic.Queue[int]]::new()
    $frontier.Enqueue($RootProcessId)
    while ($frontier.Count -gt 0) {
        $parent = $frontier.Dequeue()
        foreach ($process in $Snapshot) {
            if ([int] $process.ParentProcessId -eq $parent -and
                -not $descendants.Contains([int] $process.ProcessId)) {
                $descendants.Add([int] $process.ProcessId)
                $frontier.Enqueue([int] $process.ProcessId)
            }
        }
    }
    return @($descendants)
}

function Normalize-CommandLinePath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return $Path.Replace('/', '\').ToLowerInvariant()
}

function Find-OwnedGameProcess {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [Parameter(Mandatory = $true)]
        [string] $CandidatePath
    )

    $snapshot = Get-ProcessSnapshot
    $ownedIds = @(Get-DescendantProcessIds -RootProcessId $RootProcessId -Snapshot $snapshot)
    $candidateText = Normalize-CommandLinePath -Path $CandidatePath
    foreach ($process in $snapshot) {
        if (-not $ownedIds.Contains([int] $process.ProcessId)) {
            continue
        }
        $commandLine = [string] $process.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) {
            continue
        }
        $normalizedCommand = Normalize-CommandLinePath -Path $commandLine
        if ($normalizedCommand.Contains('knotclient') -and $normalizedCommand.Contains($candidateText)) {
            return $process
        }
    }
    return $null
}

function Get-OwnedProcessHandle {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [Parameter(Mandatory = $true)]
        [object] $ObservedProcess,

        [Parameter(Mandatory = $true)]
        [string] $CandidatePath
    )

    $snapshot = Get-ProcessSnapshot
    $ownedIds = @(Get-DescendantProcessIds -RootProcessId $RootProcessId -Snapshot $snapshot)
    $current = @($snapshot | Where-Object {
        [int] $_.ProcessId -eq [int] $ObservedProcess.ProcessId
    })
    if ($current.Count -ne 1 -or -not $ownedIds.Contains([int] $ObservedProcess.ProcessId)) {
        return $null
    }

    $currentCommandLine = [string] $current[0].CommandLine
    if ([string]::IsNullOrWhiteSpace($currentCommandLine) -or
        $currentCommandLine -ne [string] $ObservedProcess.CommandLine -or
        -not (Normalize-CommandLinePath -Path $currentCommandLine).Contains(
            (Normalize-CommandLinePath -Path $CandidatePath))) {
        throw "Owned game PID $($ObservedProcess.ProcessId) changed identity before termination."
    }

    try {
        $handle = [Diagnostics.Process]::GetProcessById([int] $ObservedProcess.ProcessId)
        if ($handle.HasExited) {
            $handle.Dispose()
            return $null
        }
        return $handle
    } catch [ArgumentException] {
        return $null
    }
}

function Wait-LauncherExit {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.Process] $LauncherProcess,

        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds
    )

    if (-not $LauncherProcess.HasExited -and
        -not $LauncherProcess.WaitForExit($TimeoutSeconds * 1000)) {
        throw "Gradle/Loom launcher did not exit within ${TimeoutSeconds}s."
    }
    return [int] $LauncherProcess.ExitCode
}

function New-LauncherStartInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LauncherPath,

        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    if ($LauncherPath -match '(?i)\.(bat|cmd)$') {
        $commandShell = [Environment]::GetEnvironmentVariable('ComSpec')
        if ([string]::IsNullOrWhiteSpace($commandShell)) {
            throw 'ComSpec is not available for the Gradle/Loom batch launcher.'
        }
        $startInfo.FileName = $commandShell
        [void] $startInfo.ArgumentList.Add('/d')
        [void] $startInfo.ArgumentList.Add('/c')
        [void] $startInfo.ArgumentList.Add('call')
        [void] $startInfo.ArgumentList.Add($LauncherPath)
        foreach ($argument in $Arguments) {
            [void] $startInfo.ArgumentList.Add([string] $argument)
        }
    } else {
        $startInfo.FileName = $LauncherPath
        foreach ($argument in $Arguments) {
            [void] $startInfo.ArgumentList.Add([string] $argument)
        }
    }

    return $startInfo
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Value,

        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Stop-OwnedGameProcess {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.Process] $LauncherProcess,

        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [Parameter(Mandatory = $true)]
        [object] $ObservedProcess,

        [Parameter(Mandatory = $true)]
        [Diagnostics.Process] $GameProcess,

        [Parameter(Mandatory = $true)]
        [string] $CandidatePath,

        [Parameter(Mandatory = $true)]
        [string] $RunDirectory
    )

    if ($LauncherProcess.HasExited) {
        throw "Gradle/Loom launcher exited before verifier-controlled termination (exit code $($LauncherProcess.ExitCode))."
    }

    $current = Find-OwnedGameProcess -RootProcessId $RootProcessId -CandidatePath $CandidatePath
    if ($null -eq $current -or
        [int] $current.ProcessId -ne [int] $ObservedProcess.ProcessId -or
        [string] $current.CommandLine -ne [string] $ObservedProcess.CommandLine) {
        throw "Owned game process changed or disappeared before verifier-controlled termination."
    }

    $terminationEvidence = [ordered]@{
        kind = 'verifier-controlled-termination-request'
        requestedAtUtc = [DateTime]::UtcNow.ToString('o')
        launcherProcessId = $RootProcessId
        launcherWasAliveAtRequest = $true
        gameProcessId = [int] $ObservedProcess.ProcessId
        gameProcessCommandLine = [string] $ObservedProcess.CommandLine
        candidatePath = [IO.Path]::GetFullPath($CandidatePath)
    }
    Write-JsonFile -Value $terminationEvidence -Path (Join-Path $RunDirectory 'termination-requested.json')

    if ($LauncherProcess.HasExited) {
        throw "Gradle/Loom launcher exited before verifier-controlled termination was issued (exit code $($LauncherProcess.ExitCode))."
    }
    Stop-Process -Id ([int] $ObservedProcess.ProcessId) -ErrorAction Stop

    $gameWaitDeadline = (Get-Date).AddSeconds(15)
    while (-not $GameProcess.HasExited -and (Get-Date) -lt $gameWaitDeadline) {
        Start-Sleep -Milliseconds 250
    }
    if (-not $GameProcess.HasExited) {
        throw "Owned game process did not terminate after the verifier request."
    }

    $terminationEvidence.gameExitCode = [int] $GameProcess.ExitCode
    $terminationEvidence.observedExitedAfterRequest = $true
    return $terminationEvidence
}

function Stop-OwnedProcessTree {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId,

        [Diagnostics.Process] $LauncherProcess,

        [object] $KnownGameProcess,

        [string] $CandidatePath
    )

    $snapshot = Get-ProcessSnapshot
    $ownedIds = @(Get-DescendantProcessIds -RootProcessId $RootProcessId -Snapshot $snapshot)
    $ownedIds = @($ownedIds | Sort-Object -Descending)
    foreach ($processId in $ownedIds) {
        Stop-Process -Id $processId -ErrorAction SilentlyContinue
    }

    if ($null -ne $KnownGameProcess -and -not [string]::IsNullOrWhiteSpace($CandidatePath)) {
        $knownCurrent = @($snapshot | Where-Object {
            [int] $_.ProcessId -eq [int] $KnownGameProcess.ProcessId -and
                (Normalize-CommandLinePath -Path ([string] $_.CommandLine)).Contains(
                    (Normalize-CommandLinePath -Path $CandidatePath)) -and
                [string] $_.CommandLine -eq [string] $KnownGameProcess.CommandLine
        })
        if ($knownCurrent.Count -eq 1) {
            Stop-Process -Id ([int] $KnownGameProcess.ProcessId) -ErrorAction SilentlyContinue
        }
    }

    if ($null -ne $LauncherProcess -and -not $LauncherProcess.HasExited) {
        Stop-Process -Id $RootProcessId -ErrorAction SilentlyContinue
    }
}

function Read-LoadedClassProof {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ClassLoadLog,

        [Parameter(Mandatory = $true)]
        [string] $CandidatePath
    )

    Assert-NoReparsePathComponents -Path $ClassLoadLog -Description 'JVM class-load log'
    Assert-NoReparsePathComponents -Path $CandidatePath -Description 'Candidate JAR'
    if (-not (Test-Path -LiteralPath $ClassLoadLog -PathType Leaf)) {
        throw "JVM class-load evidence is missing: $ClassLoadLog"
    }
    $candidate = [IO.Path]::GetFullPath($CandidatePath)
    foreach ($line in Get-Content -LiteralPath $ClassLoadLog) {
        if ($line -notmatch 'dev\.hylfrd\.farmhelper\.client\.FarmHelperClient\s+source:\s+(\S+)') {
            continue
        }
        $source = $Matches[1]
        try {
            $sourcePath = ([Uri] $source).LocalPath
            $resolvedSource = [IO.Path]::GetFullPath($sourcePath)
            if ($resolvedSource.Equals($candidate, [StringComparison]::OrdinalIgnoreCase)) {
                return [pscustomobject]@{
                    line = $line
                    source = $source
                    path = $resolvedSource
                }
            }
        } catch {
            continue
        }
    }
    throw "FarmHelperClient was not observed loading from the exact candidate JAR: $candidate"
}

function Save-LauncherStreams {
    param(
        [Parameter(Mandatory = $true)]
        [object] $StandardOutputTask,

        [Parameter(Mandatory = $true)]
        [object] $StandardErrorTask,

        [Parameter(Mandatory = $true)]
        [string] $StandardOutputPath,

        [Parameter(Mandatory = $true)]
        [string] $StandardErrorPath
    )

    $stdout = $StandardOutputTask.GetAwaiter().GetResult()
    $stderr = $StandardErrorTask.GetAwaiter().GetResult()
    [IO.File]::WriteAllText($StandardOutputPath, $stdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($StandardErrorPath, $stderr, [Text.UTF8Encoding]::new($false))
}

$properties = Read-GradleProperties -Path $gradlePropertiesPath
$minecraftVersion = [string] $properties['minecraft_version']
if ([string]::IsNullOrWhiteSpace($minecraftVersion)) {
    throw 'minecraft_version is missing from gradle.properties.'
}

Assert-NoReparsePathComponents -Path $repositoryRoot -Description 'Repository root'
Assert-NoReparsePathComponents -Path $wrapper -Description 'Gradle wrapper'

$launcherPath = Get-CanonicalPath -Path $wrapper
if (-not [string]::IsNullOrWhiteSpace($env:FARMHELPER_VERIFIER_TEST_LAUNCHER)) {
    if ($env:FARMHELPER_VERIFIER_TEST_MODE -ne '1') {
        throw 'FARMHELPER_VERIFIER_TEST_LAUNCHER requires FARMHELPER_VERIFIER_TEST_MODE=1.'
    }
    $launcherPath = Get-CanonicalPath -Path $env:FARMHELPER_VERIFIER_TEST_LAUNCHER
}

$librariesDirectory = Join-Path $repositoryRoot 'build\libs'
Assert-NoReparsePathComponents -Path $librariesDirectory -Description 'Build output directory'
if (-not (Test-Path -LiteralPath $librariesDirectory -PathType Container)) {
    throw "Build output directory is missing: $librariesDirectory"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $librariesDirectory "FarmHelper-$minecraftVersion.jar"
}
$resolvedJarPath = Resolve-RepositoryPath -Path $JarPath
Assert-PathUnder -Path $resolvedJarPath -Root $librariesDirectory -Description 'Candidate JAR'
$candidate = Assert-CandidateArtifact `
    -Path $resolvedJarPath `
    -ExpectedHash $ExpectedSha256 `
    -LibrariesDirectory $librariesDirectory

$verificationRoot = Join-Path $repositoryRoot 'build\verification\packaged-client'
if (-not (Test-Path -LiteralPath $verificationRoot)) {
    New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null
}
Assert-NoReparsePathComponents -Path $verificationRoot -Description 'Packaged verification root'

if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    if ($PreflightOnly) {
        throw 'PreflightOnly requires an existing -RunDirectory fixture.'
    }
    $RunDirectory = Join-Path $verificationRoot ([Guid]::NewGuid().ToString('N'))
} else {
    $RunDirectory = Resolve-RepositoryPath -Path $RunDirectory
}

Assert-NoReparsePathComponents -Path $RunDirectory -Description 'Run directory'
if ($PreflightOnly) {
    if (-not (Test-Path -LiteralPath $RunDirectory -PathType Container)) {
        throw "Preflight fixture directory is missing: $RunDirectory"
    }
} else {
    Assert-PathUnder -Path $RunDirectory -Root $verificationRoot -Description 'Run directory'
    if (Test-Path -LiteralPath $RunDirectory) {
        throw "Refusing to reuse an existing run directory: $RunDirectory"
    }
    New-Item -ItemType Directory -Path $RunDirectory | Out-Null
    Assert-NoReparsePathComponents -Path $RunDirectory -Description 'Run directory'
}

$modsDirectory = Join-Path $RunDirectory 'mods'
if (-not (Test-Path -LiteralPath $modsDirectory)) {
    if ($PreflightOnly) {
        throw "Preflight fixture mods directory is missing: $modsDirectory"
    }
    New-Item -ItemType Directory -Path $modsDirectory | Out-Null
}
Assert-CleanModsDirectory -ModsDirectory $modsDirectory

$gradleHome = if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
    [IO.Path]::GetFullPath($GradleUserHome)
} elseif ($env:GRADLE_USER_HOME) {
    [IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
} else {
    Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
}
Assert-NoReparsePathComponents -Path $gradleHome -Description 'Gradle user home'
$assets = Assert-OfflineAssets -MinecraftVersion $minecraftVersion -GradleHome $gradleHome
$fabricApi = Resolve-ApprovedFabricApi -GradleHome $gradleHome -Properties $properties
$modsDirectoryEntries = @()
$runtimeDependencyEvidence = [ordered]@{
    coordinate = $fabricApi.coordinate
    version = $fabricApi.version
    path = $fabricApi.source
    sha256 = $fabricApi.sha256
    size = $fabricApi.size
    modId = $fabricApi.modId
    modVersion = $fabricApi.modVersion
    source = $fabricApi.provenanceSource
    direct = $fabricApi.direct
    bundled = $fabricApi.bundled
    allowlisted = $true
}
$launcherLogDirectory = Join-Path (Split-Path -Parent $RunDirectory) 'launcher-logs'
Assert-NoReparsePathComponents -Path $launcherLogDirectory -Description 'Launcher log directory'
$runDirectoryName = Split-Path -Leaf $RunDirectory
$gradleStdout = Join-Path $launcherLogDirectory "$runDirectoryName.stdout.log"
$gradleStderr = Join-Path $launcherLogDirectory "$runDirectoryName.stderr.log"

$proof = [ordered]@{
    schemaVersion = 2
    status = if ($PreflightOnly) { 'preflight-passed' } else { 'launch-pending' }
    minecraftVersion = $minecraftVersion
    loaderVersion = [string] $properties['loader_version']
    candidate = $candidate
    runtimeDependencies = @($runtimeDependencyEvidence)
    provenanceBoundary = [ordered]@{
        gradleTask = 'verifyPackagedClientProvenanceGate'
        dependencyTask = 'verifyDependencyProvenance'
        packagedTaskDependsOnProvenance = $true
        candidateHashCheckedInsideGradle = $true
        fabricApiHashCheckedInsideGradle = $true
    }
    runDirectory = [IO.Path]::GetFullPath($RunDirectory)
    modsDirectory = [IO.Path]::GetFullPath($modsDirectory)
    modsDirectoryEntries = @($modsDirectoryEntries)
    offline = $true
    assets = $assets
    launch = [ordered]@{
        gradleTask = 'verifyPackagedClient'
        taskType = 'net.fabricmc.loom.task.prod.ClientProductionRunTask'
        mainClass = 'net.fabricmc.loader.impl.launch.knot.KnotClient'
        development = $false
        noGui = $true
        addMods = @(
            [IO.Path]::GetFullPath($candidate.path)
            [IO.Path]::GetFullPath($fabricApi.source)
        )
        candidateAddMod = [IO.Path]::GetFullPath($candidate.path)
        dependencyAddMods = @([IO.Path]::GetFullPath($fabricApi.source))
        classLoadLog = Join-Path $RunDirectory 'class-load.log'
        minecraftLog = Join-Path $RunDirectory 'logs\latest.log'
        gradleStdout = $gradleStdout
        gradleStderr = $gradleStderr
    }
}

if ($PreflightOnly) {
    $proof | ConvertTo-Json -Depth 12
    exit 0
}

New-Item -ItemType Directory -Path $launcherLogDirectory -Force | Out-Null
Assert-NoReparsePathComponents -Path $launcherLogDirectory -Description 'Launcher log directory'
$gradleArguments = @(
    '--offline',
    '--no-daemon',
    '--console=plain',
    '-x', 'jar',
    '--project-prop', "packagedClientJar=$($candidate.path)",
    '--project-prop', "packagedClientJarSha256=$($candidate.sha256)",
    '--project-prop', "packagedClientDependency=$($fabricApi.source)",
    '--project-prop', "packagedClientDependencySha256=$($fabricApi.sha256)",
    '--project-prop', "packagedClientRunDir=$([IO.Path]::GetFullPath($RunDirectory))",
    'verifyPackagedClient'
)

$process = $null
$stdoutTask = $null
$stderrTask = $null
$rootProcessId = $null
$ownedGameProcess = $null
$ownedGameProcessHandle = $null
$rootExitCode = $null
$gameExitCode = $null
$loaded = $false
$controlledTerminationEvidence = $null

try {
    $startInfo = New-LauncherStartInfo `
        -LauncherPath $launcherPath `
        -WorkingDirectory $repositoryRoot `
        -Arguments $gradleArguments
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Could not start Gradle/Loom launcher: $launcherPath"
    }
    $rootProcessId = $process.Id
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        $ownedGameProcess = Find-OwnedGameProcess -RootProcessId $rootProcessId -CandidatePath $candidate.path
        if ($null -ne $ownedGameProcess -and $null -eq $ownedGameProcessHandle) {
            $ownedGameProcessHandle = Get-OwnedProcessHandle `
                -RootProcessId $rootProcessId `
                -ObservedProcess $ownedGameProcess `
                -CandidatePath $candidate.path
        }

        if ($process.HasExited) {
            $rootExitCode = [int] $process.ExitCode
            throw "Gradle/Loom launcher exited unexpectedly before exact proof (exit code $rootExitCode)."
        }

        $minecraftLog = Join-Path $RunDirectory 'logs\latest.log'
        $classLoadLog = Join-Path $RunDirectory 'class-load.log'
        $hasMarker = (Test-Path -LiteralPath $minecraftLog -PathType Leaf) -and
            (Select-String -LiteralPath $minecraftLog -SimpleMatch 'FarmHelper client initialized.' -Quiet)
        $hasClassProof = (Test-Path -LiteralPath $classLoadLog -PathType Leaf) -and
            (Select-String -LiteralPath $classLoadLog -SimpleMatch 'dev.hylfrd.farmhelper.client.FarmHelperClient' -Quiet)

        if ($null -ne $ownedGameProcessHandle -and $hasMarker -and $hasClassProof) {
            $loaded = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $loaded) {
        throw "Timed out before proving exact FarmHelper JAR loading. Owned game PID: " +
            $(if ($null -eq $ownedGameProcess) { 'none' } else { $ownedGameProcess.ProcessId })
    }

    $proof.launch.gameProcessId = [int] $ownedGameProcess.ProcessId
    $proof.launch.gameProcessCommandLine = [string] $ownedGameProcess.CommandLine
    $proof.launch.classLoad = Read-LoadedClassProof `
        -ClassLoadLog (Join-Path $RunDirectory 'class-load.log') `
        -CandidatePath $candidate.path
    $proof.status = 'proof-ready'
    $proof.launch.proofReadyAtUtc = [DateTime]::UtcNow.ToString('o')
    Write-JsonFile -Value $proof -Path (Join-Path $RunDirectory 'proof-ready.json')
    Write-Output 'PACKAGED_CLIENT_PROOF_READY'
    Write-Output "gameProcessId=$($proof.launch.gameProcessId)"
    Write-Output "runDirectory=$($proof.runDirectory)"

    # The verifier never waits for GUI or Player observation. The owned game
    # termination is cleanup evidence; the Gradle/Loom exit remains authoritative.
    $controlledTerminationEvidence = Stop-OwnedGameProcess `
        -LauncherProcess $process `
        -RootProcessId $rootProcessId `
        -ObservedProcess $ownedGameProcess `
        -GameProcess $ownedGameProcessHandle `
        -CandidatePath $candidate.path `
        -RunDirectory $RunDirectory
    $gameExitCode = [int] $controlledTerminationEvidence.gameExitCode
    $rootExitCode = Wait-LauncherExit -LauncherProcess $process -TimeoutSeconds 30
    if ($rootExitCode -ne 0) {
        $proof.status = 'failed-launcher-exit'
        $proof.launch.controlledTermination = $true
        $proof.launch.gameExitCode = $gameExitCode
        $proof.launch.launcherExitCode = $rootExitCode
        $proof.launch.terminationEvidence = $controlledTerminationEvidence
        Write-JsonFile -Value $proof -Path (Join-Path $RunDirectory 'failed-evidence.json')
        throw "Gradle/Loom launcher exited unexpectedly after verifier-controlled termination (exit code $rootExitCode)."
    }
    $proof.launch.controlledTermination = $true
} finally {
    if ($null -ne $process) {
        try {
            if (-not $process.HasExited) {
                Stop-OwnedProcessTree `
                    -RootProcessId $rootProcessId `
                    -LauncherProcess $process `
                    -KnownGameProcess $ownedGameProcess `
                    -CandidatePath $candidate.path
                [void] $process.WaitForExit(15000)
            }
        } catch {
            # Cleanup is best effort and remains limited to the owned process tree.
        }

        if ($null -ne $stdoutTask -and $null -ne $stderrTask) {
            try {
                Save-LauncherStreams `
                    -StandardOutputTask $stdoutTask `
                    -StandardErrorTask $stderrTask `
                    -StandardOutputPath $gradleStdout `
                    -StandardErrorPath $gradleStderr
            } catch {
                # The primary verifier failure is retained if stream draining fails.
            }
        }
        $process.Dispose()
    }
    if ($null -ne $ownedGameProcessHandle) {
        $ownedGameProcessHandle.Dispose()
    }
}

$postHash = (Get-FileHash -LiteralPath $candidate.path -Algorithm SHA256).Hash.ToUpperInvariant()
if ($postHash -ne $candidate.sha256) {
    throw "Candidate JAR changed during verification: expected $($candidate.sha256), found $postHash."
}
$postDependencyHash = (Get-FileHash -LiteralPath $fabricApi.source -Algorithm SHA256).Hash.ToUpperInvariant()
if ($postDependencyHash -ne $fabricApi.sha256) {
    throw "Approved Fabric API changed during verification: expected $($fabricApi.sha256), found $postDependencyHash."
}

$proof.status = 'passed'
$proof.launch.gameExitCode = $gameExitCode
$proof.launch.launcherExitCode = $rootExitCode
$proof.launch.controlledTermination = [bool] $proof.launch.controlledTermination
$proof.launch.terminationEvidence = $controlledTerminationEvidence
Write-JsonFile -Value $proof -Path (Join-Path $RunDirectory 'evidence.json')

Write-Output 'PACKAGED_CLIENT_VERIFICATION_OK'
Write-Output "candidate=$($candidate.path)"
Write-Output "sha256=$($candidate.sha256)"
Write-Output "size=$($candidate.size)"
Write-Output "evidence=$(Join-Path $RunDirectory 'evidence.json')"
