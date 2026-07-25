[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedSha256,

    [string] $JarPath,

    [string] $RunDirectory,

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

function Get-CanonicalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $item = Get-Item -LiteralPath $Path
    if ($item.PSObject.Properties.Name -contains 'LinkType' -and $null -ne $item.LinkType) {
        throw "Reparse-point candidates are not accepted: $Path"
    }
    return $item.FullName
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
    if (-not $normalizedPath.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
        -not $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Description} must be under ${normalizedRoot}: $normalizedPath"
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
    if (-not (Test-Path -LiteralPath $artifactRoot -PathType Container)) {
        throw "Offline Fabric API cache is missing: $artifactRoot"
    }

    $artifactName = "fabric-api-$approvedFabricApiVersion.jar"
    $artifacts = @(Get-ChildItem -LiteralPath $artifactRoot -Recurse -File -Filter $artifactName)
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
    foreach ($jar in @(Get-ChildItem -LiteralPath $LibrariesDirectory -File -Filter '*.jar')) {
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

    if (-not (Test-Path -LiteralPath $ModsDirectory)) {
        throw "Mods directory is missing: $ModsDirectory"
    }
    if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
        throw "Mods path is not a directory: $ModsDirectory"
    }

    $entries = @(Get-ChildItem -LiteralPath $ModsDirectory -Force)
    $farmHelperDuplicates = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $entries) {
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

function Install-ApprovedFabricApi {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ModsDirectory,

        [Parameter(Mandatory = $true)]
        [pscustomobject] $Dependency
    )

    $sourceHash = (Get-FileHash -LiteralPath $Dependency.source -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($sourceHash -ne $Dependency.sha256) {
        throw "Fabric API changed after cache audit: expected $($Dependency.sha256), found $sourceHash."
    }

    $destination = Join-Path $ModsDirectory "fabric-api-$($Dependency.version).jar"
    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to overwrite an existing approved dependency: $destination"
    }
    Copy-Item -LiteralPath $Dependency.source -Destination $destination

    $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($destinationHash -ne $Dependency.sha256) {
        throw "Staged Fabric API SHA-256 mismatch: expected $($Dependency.sha256), found $destinationHash."
    }

    return [IO.Path]::GetFullPath($destination)
}

function Assert-ApprovedRuntimeModsDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ModsDirectory,

        [Parameter(Mandatory = $true)]
        [pscustomobject] $Dependency,

        [Parameter(Mandatory = $true)]
        [string] $StagedPath
    )

    $entries = @(Get-ChildItem -LiteralPath $ModsDirectory -Force)
    if ($entries.Count -ne 1) {
        throw "Mods directory is not allowlist-clean; expected exactly one approved dependency, found: $($entries.Name -join ', ')"
    }

    $entry = $entries[0]
    if ($entry.PSIsContainer -or
        -not $entry.FullName.Equals($StagedPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected entry in mods directory: $($entry.FullName)"
    }

    $metadata = Read-FabricModMetadata -Path $entry.FullName
    if ([string] $metadata.id -eq 'farmhelper') {
        throw "Duplicate FarmHelper mod in mods directory: $($entry.FullName)"
    }
    if ([string] $metadata.id -ne $Dependency.modId -or
        [string] $metadata.version -ne $Dependency.modVersion) {
        throw "Unexpected mod metadata in approved dependency path: $($entry.FullName)"
    }

    $actualHash = (Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -ne $Dependency.sha256) {
        throw "Staged dependency SHA-256 mismatch: expected $($Dependency.sha256), found $actualHash."
    }

    return [ordered]@{
        name = $entry.Name
        path = [IO.Path]::GetFullPath($entry.FullName)
        size = $entry.Length
        sha256 = $actualHash
        modId = [string] $metadata.id
        modVersion = [string] $metadata.version
        allowlisted = $true
    }
}

function Assert-OfflineAssets {
    param(
        [Parameter(Mandatory = $true)]
        [string] $MinecraftVersion,

        [Parameter(Mandatory = $true)]
        [string] $GradleHome
    )

    $metadataPath = Join-Path $GradleHome "caches\fabric-loom\$MinecraftVersion\mojang_minecraft_info.json"
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Offline Minecraft metadata is missing: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($null -eq $metadata.assetIndex -or [string]::IsNullOrWhiteSpace([string] $metadata.assetIndex.id)) {
        throw "Minecraft metadata has no usable asset index: $metadataPath"
    }

    $indexName = if ([string] $metadata.assetIndex.id -eq $MinecraftVersion) {
        $MinecraftVersion
    } else {
        "$MinecraftVersion-$($metadata.assetIndex.id)"
    }
    $assetsRoot = Join-Path $GradleHome 'caches\fabric-loom\assets'
    $indexPath = Join-Path $assetsRoot "indexes\$indexName.json"
    if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
        throw "Offline asset index is missing: $indexPath"
    }
    $indexHash = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($indexHash -ne ([string] $metadata.assetIndex.sha1).ToLowerInvariant()) {
        throw "Offline asset index SHA-1 mismatch: $indexPath"
    }

    $index = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json
    $missing = [System.Collections.Generic.List[string]]::new()
    foreach ($asset in @($index.objects.PSObject.Properties)) {
        $hash = [string] $asset.Value.hash
        if ($hash -notmatch '^[0-9a-fA-F]{40}$') {
            throw "Invalid asset hash in ${indexPath}: $hash"
        }
        $objectPath = Join-Path (Join-Path (Join-Path $assetsRoot 'objects') $hash.Substring(0, 2)) $hash
        if (-not (Test-Path -LiteralPath $objectPath -PathType Leaf)) {
            $missing.Add($asset.Name)
        }
    }
    if ($missing.Count -gt 0) {
        throw "Offline asset cache is incomplete; missing $($missing.Count) objects, including $($missing[0])."
    }

    return [pscustomobject]@{
        root = $assetsRoot
        index = $indexName
        objectCount = @($index.objects.PSObject.Properties).Count
    }
}

function Get-ProcessSnapshot {
    return @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CommandLine)
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
        $normalizedCommand = Normalize-CommandLinePath -Path $commandLine
        if ($normalizedCommand.Contains('knotclient') -and $normalizedCommand.Contains($candidateText)) {
            return $process
        }
    }
    return $null
}

function Stop-OwnedProcessTree {
    param(
        [Parameter(Mandatory = $true)]
        [int] $RootProcessId
    )

    $snapshot = Get-ProcessSnapshot
    $ownedIds = @(Get-DescendantProcessIds -RootProcessId $RootProcessId -Snapshot $snapshot)
    $ownedIds = @($ownedIds | Sort-Object -Descending)
    foreach ($processId in $ownedIds) {
        Stop-Process -Id $processId -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $RootProcessId -ErrorAction SilentlyContinue
}

function Read-LoadedClassProof {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ClassLoadLog,

        [Parameter(Mandatory = $true)]
        [string] $CandidatePath
    )

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

$properties = Read-GradleProperties -Path $gradlePropertiesPath
$minecraftVersion = [string] $properties['minecraft_version']
if ([string]::IsNullOrWhiteSpace($minecraftVersion)) {
    throw 'minecraft_version is missing from gradle.properties.'
}

$librariesDirectory = Join-Path $repositoryRoot 'build\libs'
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

if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    $verificationRoot = Join-Path $repositoryRoot 'build\verification\packaged-client'
    if ($PreflightOnly) {
        throw 'PreflightOnly requires an existing -RunDirectory fixture.'
    }
    New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null
    $RunDirectory = Join-Path $verificationRoot ([Guid]::NewGuid().ToString('N'))
} else {
    $RunDirectory = Resolve-RepositoryPath -Path $RunDirectory
}

if ($PreflightOnly) {
    if (-not (Test-Path -LiteralPath $RunDirectory -PathType Container)) {
        throw "Preflight fixture directory is missing: $RunDirectory"
    }
} else {
    $verificationRoot = Join-Path $repositoryRoot 'build\verification\packaged-client'
    Assert-PathUnder -Path $RunDirectory -Root $verificationRoot -Description 'Run directory'
    if (Test-Path -LiteralPath $RunDirectory) {
        throw "Refusing to reuse an existing run directory: $RunDirectory"
    }
    New-Item -ItemType Directory -Path $RunDirectory | Out-Null
}

$modsDirectory = Join-Path $RunDirectory 'mods'
if (-not (Test-Path -LiteralPath $modsDirectory)) {
    if ($PreflightOnly) {
        throw "Preflight fixture mods directory is missing: $modsDirectory"
    }
    New-Item -ItemType Directory -Path $modsDirectory | Out-Null
}
Assert-CleanModsDirectory -ModsDirectory $modsDirectory

$gradleHome = if ($env:GRADLE_USER_HOME) {
    [IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
} else {
    Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
}
$assets = Assert-OfflineAssets -MinecraftVersion $minecraftVersion -GradleHome $gradleHome
$fabricApi = Resolve-ApprovedFabricApi -GradleHome $gradleHome -Properties $properties
$modsDirectoryEntries = @()
$runtimeDependencyEvidence = [ordered]@{
    coordinate = $fabricApi.coordinate
    version = $fabricApi.version
    sourcePath = $fabricApi.source
    installedPath = $null
    sha256 = $fabricApi.sha256
    size = $fabricApi.size
    modId = $fabricApi.modId
    modVersion = $fabricApi.modVersion
    source = $fabricApi.provenanceSource
    direct = $fabricApi.direct
    bundled = $fabricApi.bundled
    allowlisted = $true
}
if (-not $PreflightOnly) {
    $stagedFabricApi = Install-ApprovedFabricApi -ModsDirectory $modsDirectory -Dependency $fabricApi
    $runtimeDependencyEvidence.installedPath = $stagedFabricApi
    $modsDirectoryEntries = @(
        Assert-ApprovedRuntimeModsDirectory `
            -ModsDirectory $modsDirectory `
            -Dependency $fabricApi `
            -StagedPath $stagedFabricApi
    )
}
$launcherLogDirectory = Join-Path (Split-Path -Parent $RunDirectory) 'launcher-logs'
$runDirectoryName = Split-Path -Leaf $RunDirectory
$gradleStdout = Join-Path $launcherLogDirectory "$runDirectoryName.stdout.log"
$gradleStderr = Join-Path $launcherLogDirectory "$runDirectoryName.stderr.log"

$proof = [ordered]@{
    schemaVersion = 1
    status = if ($PreflightOnly) { 'preflight-passed' } else { 'launch-pending' }
    minecraftVersion = $minecraftVersion
    loaderVersion = [string] $properties['loader_version']
    candidate = $candidate
    runtimeDependencies = @($runtimeDependencyEvidence)
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
        addMods = [IO.Path]::GetFullPath($candidate.path)
        classLoadLog = Join-Path $RunDirectory 'class-load.log'
        minecraftLog = Join-Path $RunDirectory 'logs\latest.log'
        gradleStdout = $gradleStdout
        gradleStderr = $gradleStderr
    }
}

if ($PreflightOnly) {
    $proof | ConvertTo-Json -Depth 8
    exit 0
}

New-Item -ItemType Directory -Path $launcherLogDirectory -Force | Out-Null
$gradleArguments = @(
    '--offline',
    '--no-daemon',
    '--console=plain',
    '-x', 'jar',
    '--project-prop', "packagedClientJar=$($candidate.path)",
    '--project-prop', "packagedClientRunDir=$([IO.Path]::GetFullPath($RunDirectory))",
    'verifyPackagedClient'
)

$process = Start-Process `
    -FilePath $wrapper `
    -ArgumentList $gradleArguments `
    -WorkingDirectory $repositoryRoot `
    -RedirectStandardOutput $gradleStdout `
    -RedirectStandardError $gradleStderr `
    -PassThru
$rootProcessId = $process.Id
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$loaded = $false
$ownedGameProcess = $null

try {
    while ((Get-Date) -lt $deadline) {
        $ownedGameProcess = Find-OwnedGameProcess -RootProcessId $rootProcessId -CandidatePath $candidate.path
        $minecraftLog = Join-Path $RunDirectory 'logs\latest.log'
        $hasMarker = (Test-Path -LiteralPath $minecraftLog -PathType Leaf) -and
            (Select-String -LiteralPath $minecraftLog -SimpleMatch 'FarmHelper client initialized.' -Quiet)
        $hasClassProof = (Test-Path -LiteralPath (Join-Path $RunDirectory 'class-load.log') -PathType Leaf) -and
            (Select-String -LiteralPath (Join-Path $RunDirectory 'class-load.log') -SimpleMatch 'dev.hylfrd.farmhelper.client.FarmHelperClient' -Quiet)

        if ($null -ne $ownedGameProcess -and $hasMarker -and $hasClassProof) {
            $loaded = $true
            break
        }
        if ($process.HasExited) {
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $loaded) {
        throw "Timed out or exited before proving exact FarmHelper JAR loading. Owned game PID: $($null -eq $ownedGameProcess ? 'none' : $ownedGameProcess.ProcessId)."
    }

    $proof.launch.gameProcessId = [int] $ownedGameProcess.ProcessId
    $proof.launch.gameProcessCommandLine = [string] $ownedGameProcess.CommandLine
    $proof.launch.classLoad = Read-LoadedClassProof `
        -ClassLoadLog (Join-Path $RunDirectory 'class-load.log') `
        -CandidatePath $candidate.path

    # The client has initialized the mod and the evidence is durable. Stop only
    # the exact game process spawned below our Gradle root; no GUI input is sent.
    Stop-Process -Id ([int] $ownedGameProcess.ProcessId) -ErrorAction SilentlyContinue
    $waitDeadline = (Get-Date).AddSeconds(15)
    while (-not $process.HasExited -and (Get-Date) -lt $waitDeadline) {
        Start-Sleep -Milliseconds 250
    }
} finally {
    if (-not $process.HasExited) {
        Stop-OwnedProcessTree -RootProcessId $rootProcessId
    }
}

$postHash = (Get-FileHash -LiteralPath $candidate.path -Algorithm SHA256).Hash.ToUpperInvariant()
if ($postHash -ne $candidate.sha256) {
    throw "Candidate JAR changed during verification: expected $($candidate.sha256), found $postHash."
}

$proof.status = 'passed'
$proof.launch.launcherExitCode = $process.ExitCode
$proof.launch.controlledTermination = $true
$proof | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $RunDirectory 'evidence.json') -Encoding utf8

Write-Output "PACKAGED_CLIENT_VERIFICATION_OK"
Write-Output "candidate=$($candidate.path)"
Write-Output "sha256=$($candidate.sha256)"
Write-Output "size=$($candidate.size)"
Write-Output "evidence=$(Join-Path $RunDirectory 'evidence.json')"
