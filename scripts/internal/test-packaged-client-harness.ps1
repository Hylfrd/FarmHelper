[CmdletBinding()]
param(
    [switch] $RunAssetPerformanceFixture
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$verificationScript = Join-Path $PSScriptRoot 'verify-packaged-client.ps1'
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
$librariesDirectory = Join-Path $repositoryRoot 'build\libs'
$candidatePath = Join-Path $librariesDirectory 'FarmHelper-26.1.2.jar'
$fabricApiVersion = '0.153.0+26.1.2'
$fabricApiArtifactName = "fabric-api-$fabricApiVersion.jar"
$fabricApiCacheRoot = Join-Path $env:USERPROFILE (
    ".gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fabricApiVersion")

if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
    throw "Build the candidate JAR first: $candidatePath"
}
$sha256 = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToUpperInvariant()
$powershell = Join-Path $PSHOME 'pwsh.exe'
$apiArtifacts = @(Get-ChildItem -LiteralPath $fabricApiCacheRoot -Recurse -File -Filter $fabricApiArtifactName)
if ($apiArtifacts.Count -ne 1) {
    throw "Expected exactly one cached Fabric API artifact for the harness; found $($apiArtifacts.Count)."
}
$fabricApiPath = $apiArtifacts[0].FullName
$fabricApiSha256 = (Get-FileHash -LiteralPath $fabricApiPath -Algorithm SHA256).Hash.ToUpperInvariant()
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
        [string] $Hash,

        [string] $GradleUserHome
    )

    $arguments = @(
        '-ExpectedSha256', $Hash,
        '-JarPath', $candidatePath,
        '-RunDirectory', $FixtureDirectory,
        '-PreflightOnly'
    )
    if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
        $arguments += @('-GradleUserHome', $GradleUserHome)
    }
    $output = @(& $powershell -NoProfile -File $verificationScript @arguments 2>&1)
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

function Get-Sha1HexForBytes {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]] $Bytes
    )

    $algorithm = [Security.Cryptography.SHA1]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Write-AssetIndexFixture {
    param(
        [Parameter(Mandatory = $true)]
        [string] $GradleUserHome,

        [Parameter(Mandatory = $true)]
        [string] $AssetIndexId,

        [Parameter(Mandatory = $true)]
        [object] $Objects
    )

    $metadataDirectory = Join-Path $GradleUserHome 'caches\fabric-loom\26.1.2'
    $indexesDirectory = Join-Path $GradleUserHome 'caches\fabric-loom\assets\indexes'
    New-Item -ItemType Directory -Path $metadataDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $indexesDirectory -Force | Out-Null

    $index = [ordered]@{ objects = $Objects }
    $indexPath = Join-Path $indexesDirectory "26.1.2-$AssetIndexId.json"
    $indexText = $index | ConvertTo-Json -Compress -Depth 8
    [IO.File]::WriteAllText($indexPath, $indexText, [Text.UTF8Encoding]::new($false))
    $indexSha1 = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA1).Hash.ToLowerInvariant()

    $metadata = [ordered]@{
        assetIndex = [ordered]@{
            id = $AssetIndexId
            sha1 = $indexSha1
        }
    }
    $metadataPath = Join-Path $metadataDirectory 'mojang_minecraft_info.json'
    $metadataText = $metadata | ConvertTo-Json -Compress -Depth 5
    [IO.File]::WriteAllText($metadataPath, $metadataText, [Text.UTF8Encoding]::new($false))

    return [pscustomobject]@{
        assetsRoot = Join-Path $GradleUserHome 'caches\fabric-loom\assets'
        objectsRoot = Join-Path $GradleUserHome 'caches\fabric-loom\assets\objects'
        indexPath = $indexPath
        metadataPath = $metadataPath
    }
}

function Copy-FabricApiFixture {
    param(
        [Parameter(Mandatory = $true)]
        [string] $GradleUserHome,

        [string] $FixtureName = 'fixture'
    )

    $apiDirectory = Join-Path $GradleUserHome (
        "caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fabricApiVersion\$FixtureName")
    New-Item -ItemType Directory -Path $apiDirectory -Force | Out-Null
    $apiPath = Join-Path $apiDirectory $fabricApiArtifactName
    Copy-Item -LiteralPath $fabricApiPath -Destination $apiPath
    return $apiPath
}

function Invoke-PreflightProof {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FixtureDirectory,

        [Parameter(Mandatory = $true)]
        [string] $GradleUserHome,

        [Parameter(Mandatory = $true)]
        [string] $Hash
    )

    $output = @(
        & $powershell -NoProfile -File $verificationScript `
            -ExpectedSha256 $Hash `
            -JarPath $candidatePath `
            -RunDirectory $FixtureDirectory `
            -GradleUserHome $GradleUserHome `
            -PreflightOnly 2>&1
    )
    $exitCode = $LASTEXITCODE
    $text = $output -join "`n"
    if ($exitCode -ne 0) {
        throw "preflight unexpectedly failed: $text"
    }
    return $text | ConvertFrom-Json
}

function Invoke-GradleExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedMessage,

        [Parameter(Mandatory = $true)]
        [string] $CandidateHash,

        [Parameter(Mandatory = $true)]
        [string] $DependencyHash,

        [Parameter(Mandatory = $true)]
        [string] $RunDirectory
    )

    $arguments = @(
        '--offline',
        '--no-daemon',
        '--console=plain',
        '-x', 'jar',
        '--project-prop', "packagedClientJar=$candidatePath",
        '--project-prop', "packagedClientJarSha256=$CandidateHash",
        '--project-prop', "packagedClientDependency=$fabricApiPath",
        '--project-prop', "packagedClientDependencySha256=$DependencyHash",
        '--project-prop', "packagedClientRunDir=$RunDirectory",
        'verifyPackagedClient'
    )
    $output = @(& $wrapper @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = $output -join "`n"
    if ($exitCode -eq 0) {
        throw "$Name unexpectedly passed."
    }
    if ($text -notmatch [Regex]::Escape($ExpectedMessage)) {
        throw "$Name failed for the wrong reason: $text"
    }
    if ($text -notmatch 'Dependency provenance verified') {
        throw "$Name did not execute the complete dependency provenance gate: $text"
    }
    Write-Output "GRADLE_NEGATIVE_OK $Name exit=$exitCode"
}

function Invoke-AssetPerformanceFixture {
    $performanceFixture = Join-Path $fixtureRoot 'asset traversal performance'
    $performanceGradleHome = Join-Path $performanceFixture 'gradle home'
    $performanceRunDirectory = Join-Path $performanceFixture 'run'
    $objectCount = 4750
    $objects = [ordered]@{}
    $hashes = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $prefixDirectories = @{}
    $utf8 = [Text.UTF8Encoding]::new($false)
    $assetOrdinal = 0

    for ($prefixValue = 0; $prefixValue -lt 256; $prefixValue++) {
        $expectedPrefix = '{0:x2}' -f $prefixValue
        $attempt = 0
        do {
            $bytes = $utf8.GetBytes("AUDIT-009 prefix $expectedPrefix attempt $attempt")
            $hash = Get-Sha1HexForBytes -Bytes $bytes
            $attempt++
        } while ($hash.Substring(0, 2) -ne $expectedPrefix -or $hashes.Contains($hash))
        if (-not $hashes.Add($hash)) {
            throw "Could not create a unique performance fixture hash for prefix $expectedPrefix."
        }

        $prefixPath = Join-Path (Join-Path $performanceGradleHome (
            'caches\fabric-loom\assets\objects')) $expectedPrefix
        New-Item -ItemType Directory -Path $prefixPath -Force | Out-Null
        [IO.File]::WriteAllBytes((Join-Path $prefixPath $hash), $bytes)
        $prefixDirectories[$expectedPrefix] = $true
        $objects["fixture/performance-$assetOrdinal.bin"] = [ordered]@{
            hash = $hash
            size = $bytes.Length
        }
        $assetOrdinal++
    }

    while ($assetOrdinal -lt $objectCount) {
        $attempt = 0
        do {
            $bytes = $utf8.GetBytes("AUDIT-009 object $assetOrdinal attempt $attempt")
            $hash = Get-Sha1HexForBytes -Bytes $bytes
            $attempt++
        } while ($hashes.Contains($hash))
        if ($hashes.Add($hash)) {
            $prefix = $hash.Substring(0, 2)
            $prefixPath = Join-Path (Join-Path $performanceGradleHome (
                'caches\fabric-loom\assets\objects')) $prefix
            if (-not $prefixDirectories.ContainsKey($prefix)) {
                New-Item -ItemType Directory -Path $prefixPath -Force | Out-Null
                $prefixDirectories[$prefix] = $true
            }
            [IO.File]::WriteAllBytes((Join-Path $prefixPath $hash), $bytes)
            $objects["fixture/performance-$assetOrdinal.bin"] = [ordered]@{
                hash = $hash
                size = $bytes.Length
            }
            $assetOrdinal++
        }
    }

    if ($objects.Count -ne $objectCount -or $prefixDirectories.Count -ne 256) {
        throw "Performance fixture shape is wrong: objects=$($objects.Count), prefixes=$($prefixDirectories.Count)."
    }
    Write-AssetIndexFixture `
        -GradleUserHome $performanceGradleHome `
        -AssetIndexId 'performance' `
        -Objects $objects | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $performanceRunDirectory 'mods') -Force | Out-Null
    Copy-FabricApiFixture `
        -GradleUserHome $performanceGradleHome `
        -FixtureName 'performance' | Out-Null

    $wallStopwatch = [Diagnostics.Stopwatch]::StartNew()
    $proof = Invoke-PreflightProof `
        -FixtureDirectory $performanceRunDirectory `
        -GradleUserHome $performanceGradleHome `
        -Hash $sha256
    $wallStopwatch.Stop()
    $traversal = $proof.assets.traversal
    $guardCalls = [int] $traversal.guardCalls.objectsRoot +
        [int] $traversal.guardCalls.prefixDirectories +
        [int] $traversal.guardCalls.leaves
    if ([int] $proof.assets.objectCount -ne $objectCount -or
        [int] $proof.assets.verifiedObjectCount -ne $objectCount -or
        [int] $traversal.guardCalls.objectsRoot -ne 1 -or
        [int] $traversal.guardCalls.prefixDirectories -ne $prefixDirectories.Count -or
        [int] $traversal.guardCalls.leaves -ne $objectCount -or
        [int] $traversal.sha1Objects -ne $objectCount) {
        throw "Performance fixture returned incomplete traversal proof: $($proof | ConvertTo-Json -Depth 12 -Compress)"
    }
    Write-Output ("ASSET_TRAVERSAL_PERFORMANCE_OK objects=$objectCount " +
        "prefixes=$($prefixDirectories.Count) wallMs=$($wallStopwatch.ElapsedMilliseconds) " +
        "scanMs=$($traversal.scanMilliseconds) guardCalls=$guardCalls")
}

try {
    $positiveFixture = Join-Path $fixtureRoot 'positive-preflight'
    New-Item -ItemType Directory -Path (Join-Path $positiveFixture 'mods') -Force | Out-Null
    $positiveOutput = @(
        & $powershell -NoProfile -File $verificationScript `
            -ExpectedSha256 $sha256 `
            -JarPath $candidatePath `
            -RunDirectory $positiveFixture `
            -PreflightOnly 2>&1
    )
    $positiveExitCode = $LASTEXITCODE
    if ($positiveExitCode -ne 0) {
        throw "positive preflight unexpectedly failed: $($positiveOutput -join "`n")"
    }
    $positiveProof = ($positiveOutput -join "`n") | ConvertFrom-Json
    $positiveTraversal = $positiveProof.assets.traversal
    if ([string] $positiveProof.status -ne 'preflight-passed' -or
        [string] $positiveProof.provenanceBoundary.gradleTask -ne 'verifyPackagedClientProvenanceGate' -or
        [bool] $positiveProof.provenanceBoundary.packagedTaskDependsOnProvenance -ne $true -or
        [int] $positiveProof.assets.verifiedObjectCount -ne [int] $positiveProof.assets.objectCount -or
        [int] $positiveProof.assets.objectCount -le 0 -or
        [int] $positiveTraversal.guardCalls.objectsRoot -ne 1 -or
        [int] $positiveTraversal.guardCalls.prefixDirectories -lt 1 -or
        [int] $positiveTraversal.guardCalls.prefixDirectories -gt 256 -or
        [int] $positiveTraversal.guardCalls.leaves -ne [int] $positiveTraversal.sha1Objects -or
        [int] $positiveTraversal.sha1Objects -lt 1 -or
        [int] $positiveTraversal.sha1Objects -gt [int] $positiveProof.assets.verifiedObjectCount) {
        throw "positive preflight returned incomplete proof: $($positiveOutput -join "`n")"
    }
    Write-Output ("POSITIVE_OK preflight assets=$($positiveProof.assets.verifiedObjectCount) " +
        "prefixGuards=$($positiveTraversal.guardCalls.prefixDirectories) " +
        "leafGuards=$($positiveTraversal.guardCalls.leaves) " +
        "sha1Objects=$($positiveTraversal.sha1Objects) " +
        "scanMs=$($positiveTraversal.scanMilliseconds)")

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

    $junctionFixture = Join-Path $fixtureRoot 'junction fixture'
    $junctionOutside = Join-Path $junctionFixture 'outside'
    $junctionParent = Join-Path $junctionFixture 'run-link'
    New-Item -ItemType Directory -Path $junctionOutside -Force | Out-Null
    New-Item -ItemType Junction -Path $junctionParent -Target $junctionOutside -ErrorAction Stop | Out-Null
    Invoke-ExpectedFailure `
        -Name 'parent-directory junction escape' `
        -ExpectedMessage 'reparse-point component' `
        -FixtureDirectory (Join-Path $junctionParent 'escaped-run') `
        -Hash $sha256

    $assetFixture = Join-Path $fixtureRoot 'asset hash mismatch'
    $assetGradleHome = Join-Path $assetFixture 'gradle home'
    $assetMetadataDirectory = Join-Path $assetGradleHome 'caches\fabric-loom\26.1.2'
    $assetIndexesDirectory = Join-Path $assetGradleHome 'caches\fabric-loom\assets\indexes'
    $assetObjectsDirectory = Join-Path $assetGradleHome 'caches\fabric-loom\assets\objects\aa'
    $assetRunDirectory = Join-Path $assetFixture 'run'
    New-Item -ItemType Directory -Path $assetMetadataDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $assetIndexesDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $assetObjectsDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $assetRunDirectory 'mods') -Force | Out-Null

    $expectedAssetSha1 = 'a' * 40
    $assetIndex = [ordered]@{
        objects = [ordered]@{
            'fixture/object.txt' = [ordered]@{
                hash = $expectedAssetSha1
                size = 12
            }
        }
    }
    $assetIndexPath = Join-Path $assetIndexesDirectory '26.1.2-fixture.json'
    $assetIndexText = $assetIndex | ConvertTo-Json -Compress -Depth 5
    [IO.File]::WriteAllText($assetIndexPath, $assetIndexText, [Text.UTF8Encoding]::new($false))
    $assetIndexSha1 = (Get-FileHash -LiteralPath $assetIndexPath -Algorithm SHA1).Hash.ToLowerInvariant()
    $assetMetadata = [ordered]@{
        assetIndex = [ordered]@{
            id = 'fixture'
            sha1 = $assetIndexSha1
        }
    }
    $assetMetadataPath = Join-Path $assetMetadataDirectory 'mojang_minecraft_info.json'
    $assetMetadata | ConvertTo-Json -Compress -Depth 5 | Set-Content -LiteralPath $assetMetadataPath -Encoding utf8
    Set-Content -LiteralPath (Join-Path $assetObjectsDirectory $expectedAssetSha1) -Value 'wrong asset' -Encoding ascii

    $assetApiPath = Join-Path $assetGradleHome (
        "caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fabricApiVersion\fixture")
    New-Item -ItemType Directory -Path $assetApiPath -Force | Out-Null
    Copy-Item -LiteralPath $fabricApiPath -Destination (Join-Path $assetApiPath $fabricApiArtifactName)
    Invoke-ExpectedFailure `
        -Name 'Mojang asset SHA-1 mismatch' `
        -ExpectedMessage 'Offline asset cache has SHA-1 mismatched objects' `
        -FixtureDirectory $assetRunDirectory `
        -Hash $sha256 `
        -GradleUserHome $assetGradleHome

    $assetEscapeFixture = Join-Path $fixtureRoot 'asset object root junction'
    $assetEscapeGradleHome = Join-Path $assetEscapeFixture 'gradle home'
    $assetEscapeRunDirectory = Join-Path $assetEscapeFixture 'run'
    $assetEscapeBytes = [Text.UTF8Encoding]::new($false).GetBytes('escaped asset')
    $assetEscapeHash = Get-Sha1HexForBytes -Bytes $assetEscapeBytes
    $assetEscapeObjects = [ordered]@{
        'fixture/escaped-object.txt' = [ordered]@{
            hash = $assetEscapeHash
            size = $assetEscapeBytes.Length
        }
    }
    $assetEscapeLayout = Write-AssetIndexFixture `
        -GradleUserHome $assetEscapeGradleHome `
        -AssetIndexId 'escape' `
        -Objects $assetEscapeObjects
    $assetEscapeOutside = Join-Path $assetEscapeFixture 'outside objects'
    $assetEscapeOutsidePrefix = Join-Path $assetEscapeOutside $assetEscapeHash.Substring(0, 2)
    New-Item -ItemType Directory -Path $assetEscapeOutsidePrefix -Force | Out-Null
    [IO.File]::WriteAllBytes(
        (Join-Path $assetEscapeOutsidePrefix $assetEscapeHash),
        $assetEscapeBytes)
    New-Item -ItemType Junction `
        -Path $assetEscapeLayout.objectsRoot `
        -Target $assetEscapeOutside `
        -ErrorAction Stop | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $assetEscapeRunDirectory 'mods') -Force | Out-Null
    Copy-FabricApiFixture -GradleUserHome $assetEscapeGradleHome -FixtureName 'escape' | Out-Null
    Invoke-ExpectedFailure `
        -Name 'asset object root junction escape' `
        -ExpectedMessage 'reparse-point component' `
        -FixtureDirectory $assetEscapeRunDirectory `
        -Hash $sha256 `
        -GradleUserHome $assetEscapeGradleHome

    Write-Output 'PACKAGED_CLIENT_NEGATIVE_TESTS_OK count=6'

    $validLaunchFixture = Join-Path $fixtureRoot 'valid launch cache'
    $validGradleHome = Join-Path $validLaunchFixture 'gradle home'
    $validMetadataDirectory = Join-Path $validGradleHome 'caches\fabric-loom\26.1.2'
    $validIndexesDirectory = Join-Path $validGradleHome 'caches\fabric-loom\assets\indexes'
    $validAssetsDirectory = Join-Path $validGradleHome 'caches\fabric-loom\assets\objects'
    $validSourceAsset = Join-Path $validLaunchFixture 'source asset.bin'
    New-Item -ItemType Directory -Path $validMetadataDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $validIndexesDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $validAssetsDirectory -Force | Out-Null
    [IO.File]::WriteAllText($validSourceAsset, 'valid asset', [Text.UTF8Encoding]::new($false))
    $validAssetSha1 = (Get-FileHash -LiteralPath $validSourceAsset -Algorithm SHA1).Hash.ToLowerInvariant()
    $validObjectDirectory = Join-Path $validAssetsDirectory $validAssetSha1.Substring(0, 2)
    New-Item -ItemType Directory -Path $validObjectDirectory -Force | Out-Null
    Copy-Item -LiteralPath $validSourceAsset -Destination (Join-Path $validObjectDirectory $validAssetSha1)
    $validIndex = [ordered]@{
        objects = [ordered]@{
            'fixture/object.txt' = [ordered]@{
                hash = $validAssetSha1
                size = (Get-Item -LiteralPath $validSourceAsset).Length
            }
        }
    }
    $validIndexPath = Join-Path $validIndexesDirectory '26.1.2-fixture.json'
    $validIndexText = $validIndex | ConvertTo-Json -Compress -Depth 5
    [IO.File]::WriteAllText($validIndexPath, $validIndexText, [Text.UTF8Encoding]::new($false))
    $validIndexSha1 = (Get-FileHash -LiteralPath $validIndexPath -Algorithm SHA1).Hash.ToLowerInvariant()
    [ordered]@{
        assetIndex = [ordered]@{
            id = 'fixture'
            sha1 = $validIndexSha1
        }
    } | ConvertTo-Json -Compress -Depth 5 | Set-Content -LiteralPath (
        Join-Path $validMetadataDirectory 'mojang_minecraft_info.json') -Encoding utf8
    $validApiPath = Join-Path $validGradleHome (
        "caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fabricApiVersion\fixture")
    New-Item -ItemType Directory -Path $validApiPath -Force | Out-Null
    Copy-Item -LiteralPath $fabricApiPath -Destination (Join-Path $validApiPath $fabricApiArtifactName)

    $launcherFixture = Join-Path $fixtureRoot 'launcher with spaces'
    New-Item -ItemType Directory -Path $launcherFixture -Force | Out-Null
    $capturePath = Join-Path $launcherFixture 'captured arguments.txt'
    $captureScript = Join-Path $launcherFixture 'capture launcher.ps1'
    @'
$env:FARMHELPER_CAPTURED_ARGS | Set-Content -LiteralPath $env:FARMHELPER_VERIFIER_CAPTURE -Encoding utf8
[Console]::Error.WriteLine('forced nonzero launcher exit')
exit 37
'@ | Set-Content -LiteralPath $captureScript -Encoding utf8
    $launcherCommand = @"
@echo off
set "FARMHELPER_CAPTURED_ARGS=%*"
"$powershell" -NoProfile -File "$captureScript"
exit /b %ERRORLEVEL%
"@
    $launcherPath = Join-Path $launcherFixture 'launcher with spaces.cmd'
    $launcherCommand | Set-Content -LiteralPath $launcherPath -Encoding ascii
    $spacedVerificationParent = Join-Path (Join-Path $repositoryRoot 'build\verification\packaged-client') (
        'spaced launcher ' + [Guid]::NewGuid().ToString('N'))
    $spacedRunDirectory = Join-Path $spacedVerificationParent 'run with spaces'
    $oldTestMode = $env:FARMHELPER_VERIFIER_TEST_MODE
    $oldTestLauncher = $env:FARMHELPER_VERIFIER_TEST_LAUNCHER
    $oldCapturePath = $env:FARMHELPER_VERIFIER_CAPTURE
    $oldCapturedArguments = $env:FARMHELPER_CAPTURED_ARGS
    try {
        $env:FARMHELPER_VERIFIER_TEST_MODE = '1'
        $env:FARMHELPER_VERIFIER_TEST_LAUNCHER = $launcherPath
        $env:FARMHELPER_VERIFIER_CAPTURE = $capturePath
        $launchOutput = @(
            & $powershell -NoProfile -File $verificationScript `
                -ExpectedSha256 $sha256 `
                -JarPath $candidatePath `
                -RunDirectory $spacedRunDirectory `
                -GradleUserHome $validGradleHome `
                -TimeoutSeconds 15 2>&1
        )
        $launchExitCode = $LASTEXITCODE
        $launchText = $launchOutput -join "`n"
        if ($launchExitCode -eq 0 -or $launchText -notmatch 'exit code 37') {
            throw "forced nonzero launcher test failed: exit=$launchExitCode output=$launchText"
        }
        if (-not (Test-Path -LiteralPath $capturePath -PathType Leaf)) {
            throw 'forced nonzero launcher test did not capture its arguments.'
        }
        $capturedArguments = Get-Content -LiteralPath $capturePath -Raw
        $expectedArguments = @(
            "packagedClientJar=$candidatePath",
            "packagedClientJarSha256=$sha256",
            "packagedClientDependency=$(Join-Path $validApiPath $fabricApiArtifactName)",
            "packagedClientDependencySha256=$fabricApiSha256",
            "packagedClientRunDir=$([IO.Path]::GetFullPath($spacedRunDirectory))"
        )
        foreach ($expectedArgument in $expectedArguments) {
            $expectedToken = if ($expectedArgument -match '\s') {
                '"' + $expectedArgument + '"'
            } else {
                $expectedArgument
            }
            if ($capturedArguments -notmatch [Regex]::Escape($expectedToken)) {
                throw "spaced launcher argument was truncated or changed: $expectedArgument; captured=$capturedArguments"
            }
        }
        Write-Output 'NEGATIVE_OK forced nonzero launcher and spaced arguments'
    } finally {
        if ($null -eq $oldTestMode) {
            Remove-Item Env:FARMHELPER_VERIFIER_TEST_MODE -ErrorAction SilentlyContinue
        } else {
            $env:FARMHELPER_VERIFIER_TEST_MODE = $oldTestMode
        }
        if ($null -eq $oldTestLauncher) {
            Remove-Item Env:FARMHELPER_VERIFIER_TEST_LAUNCHER -ErrorAction SilentlyContinue
        } else {
            $env:FARMHELPER_VERIFIER_TEST_LAUNCHER = $oldTestLauncher
        }
        if ($null -eq $oldCapturePath) {
            Remove-Item Env:FARMHELPER_VERIFIER_CAPTURE -ErrorAction SilentlyContinue
        } else {
            $env:FARMHELPER_VERIFIER_CAPTURE = $oldCapturePath
        }
        if ($null -eq $oldCapturedArguments) {
            Remove-Item Env:FARMHELPER_CAPTURED_ARGS -ErrorAction SilentlyContinue
        } else {
            $env:FARMHELPER_CAPTURED_ARGS = $oldCapturedArguments
        }
        if (Test-Path -LiteralPath $spacedVerificationParent) {
            Remove-Item -LiteralPath $spacedVerificationParent -Recurse -Force
        }
    }

    if ($RunAssetPerformanceFixture) {
        Invoke-AssetPerformanceFixture
    } else {
        Write-Output 'ASSET_TRAVERSAL_PERFORMANCE_SKIPPED opt-in=-RunAssetPerformanceFixture'
    }

    $gradleBoundaryRunDirectory = Join-Path (Join-Path $repositoryRoot 'build\verification\packaged-client') (
        'gradle boundary ' + [Guid]::NewGuid().ToString('N'))
    Invoke-GradleExpectedFailure `
        -Name 'Gradle candidate hash boundary' `
        -ExpectedMessage 'candidate SHA-256 does not match packagedClientJarSha256' `
        -CandidateHash ('0' * 64) `
        -DependencyHash $fabricApiSha256 `
        -RunDirectory $gradleBoundaryRunDirectory
    Invoke-GradleExpectedFailure `
        -Name 'Gradle Fabric API hash boundary' `
        -ExpectedMessage 'packaged Fabric API hash is not the repository allowlist hash' `
        -CandidateHash $sha256 `
        -DependencyHash ('0' * 64) `
        -RunDirectory $gradleBoundaryRunDirectory
    Write-Output 'GRADLE_HASH_BOUNDARY_NEGATIVES_OK count=2'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
