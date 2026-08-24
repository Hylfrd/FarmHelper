[CmdletBinding()]
param(
    [string] $GradleUserHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$verificationScript = Join-Path $PSScriptRoot 'verify-packaged-client.ps1'
$gradlePropertiesPath = Join-Path $repositoryRoot 'gradle.properties'
$buildGradlePath = Join-Path $repositoryRoot 'build.gradle'
$wrapper = Join-Path $repositoryRoot 'gradlew.bat'
$librariesDirectory = Join-Path $repositoryRoot 'build\libs'
$candidatePath = Join-Path $librariesDirectory 'FarmHelper-26.1.2.jar'
$fabricApiVersion = '0.153.0+26.1.2'
$fabricApiArtifactName = "fabric-api-$fabricApiVersion.jar"
$gradleUserHome = if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
    [IO.Path]::GetFullPath($GradleUserHome)
} elseif (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    [IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
} else {
    throw 'The packaged-client harness requires an explicit -GradleUserHome or GRADLE_USER_HOME.'
}
$defaultUserGradleHome = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.gradle'))
if ($gradleUserHome.Equals($defaultUserGradleHome, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The packaged-client harness refuses the default user-level Gradle cache: $gradleUserHome"
}
$env:GRADLE_USER_HOME = $gradleUserHome
$fabricApiCacheRoot = Join-Path $gradleUserHome (
    "caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$fabricApiVersion")

function Read-ConfiguredGradleProperty {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $pattern = '(?m)^[\t ]*' + [Regex]::Escape($Name) + '[\t ]*=[\t ]*([^\r\n]*)(?:\r?$)'
    $propertyMatches = [Regex]::Matches([IO.File]::ReadAllText($Path), $pattern)
    if ($propertyMatches.Count -ne 1) {
        throw "Expected exactly one $Name entry in $Path; found $($propertyMatches.Count)."
    }
    return $propertyMatches[0].Groups[1].Value
}

function Assert-TestPackagedClientUsername {
    param(
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [AllowEmptyString()]
        [string] $Username
    )

    if ([string]::IsNullOrWhiteSpace($Username)) {
        throw 'Packaged client username is absent or blank.'
    }
    if ($Username.Length -gt 16) {
        throw 'Packaged client username exceeds the 16-character login limit.'
    }
    if ($Username -notmatch '^[A-Za-z0-9_]{3,16}$') {
        throw 'Packaged client username is invalid; expected 3-16 ASCII letters, digits, or underscore.'
    }
    return $Username
}

function Invoke-UsernameExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [AllowEmptyString()]
        [string] $Value,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedMessage
    )

    $failed = $false
    try {
        [void] (Assert-TestPackagedClientUsername -Username $Value)
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch [Regex]::Escape($ExpectedMessage)) {
            throw "$Name failed for the wrong reason: $($_.Exception.Message)"
        }
    }
    if (-not $failed) {
        throw "$Name unexpectedly passed."
    }
    Write-Output "USERNAME_NEGATIVE_OK $Name"
}

$configuredUsername = Read-ConfiguredGradleProperty `
    -Path $gradlePropertiesPath `
    -Name 'packaged_client_verification_username'
[void] (Assert-TestPackagedClientUsername -Username $configuredUsername)
$buildText = [IO.File]::ReadAllText($buildGradlePath)
if ($buildText -match 'FarmHelperVerification' -or
    $buildText -notmatch 'project\.findProperty\("packaged_client_verification_username"\)' -or
    $buildText -notmatch '"--username",\s*packagedClientVerificationUsername' -or
    $buildText -notmatch 'effective launch arguments do not contain the configured packaged client username') {
    throw 'Packaged client username configuration is not bound to the validated effective launch arguments.'
}
Invoke-UsernameExpectedFailure `
    -Name 'absent username' `
    -Value $null `
    -ExpectedMessage 'absent or blank'
Invoke-UsernameExpectedFailure `
    -Name 'blank username' `
    -Value '' `
    -ExpectedMessage 'absent or blank'
Invoke-UsernameExpectedFailure `
    -Name 'over-limit username' `
    -Value 'FarmHelperVerification' `
    -ExpectedMessage '16-character login limit'
Invoke-UsernameExpectedFailure `
    -Name 'malformed username' `
    -Value 'Farm Helper' `
    -ExpectedMessage '3-16 ASCII letters'
Write-Output "PACKAGED_CLIENT_USERNAME_INVARIANTS_OK username=$configuredUsername invalidCount=4"

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
    $effectiveGradleUserHome = if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
        $GradleUserHome
    } else {
        $gradleUserHome
    }
    $arguments += @('-GradleUserHome', $effectiveGradleUserHome)
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
        [string] $RunDirectory,

        [string] $Username
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
        '--project-prop', "packagedClientRunDir=$RunDirectory"
    )
    if ($PSBoundParameters.ContainsKey('Username')) {
        $arguments += @('--project-prop', "packaged_client_verification_username=$Username")
    }
    $arguments += 'verifyPackagedClient'
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

try {
    $positiveFixture = Join-Path $fixtureRoot 'positive-preflight'
    New-Item -ItemType Directory -Path (Join-Path $positiveFixture 'mods') -Force | Out-Null
    $positiveOutput = @(
        & $powershell -NoProfile -File $verificationScript `
            -ExpectedSha256 $sha256 `
            -JarPath $candidatePath `
            -RunDirectory $positiveFixture `
            -GradleUserHome $gradleUserHome `
            -PreflightOnly 2>&1
    )
    $positiveExitCode = $LASTEXITCODE
    if ($positiveExitCode -ne 0) {
        throw "positive preflight unexpectedly failed: $($positiveOutput -join "`n")"
    }
    $positiveProof = ($positiveOutput -join "`n") | ConvertFrom-Json
    if ([string] $positiveProof.status -ne 'preflight-passed' -or
        [string] $positiveProof.provenanceBoundary.gradleTask -ne 'verifyPackagedClientProvenanceGate' -or
        [bool] $positiveProof.provenanceBoundary.packagedTaskDependsOnProvenance -ne $true -or
        [string] $positiveProof.launch.username -ne $configuredUsername -or
        [string] $positiveProof.launch.usernameArgument -ne '--username' -or
        [int] $positiveProof.assets.verifiedObjectCount -ne [int] $positiveProof.assets.objectCount -or
        [int] $positiveProof.assets.objectCount -le 0) {
        throw "positive preflight returned incomplete proof: $($positiveOutput -join "`n")"
    }
    Write-Output "POSITIVE_OK preflight assets=$($positiveProof.assets.verifiedObjectCount)"

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

    Write-Output 'PACKAGED_CLIENT_NEGATIVE_TESTS_OK count=5'

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

    $usernameGradleCases = @(
        [pscustomobject]@{
            name = 'Gradle blank packaged-client username'
            value = ''
            message = 'packaged client username is absent or blank'
        },
        [pscustomobject]@{
            name = 'Gradle over-limit packaged-client username'
            value = 'FarmHelperVerification'
            message = 'packaged client username exceeds the 16-character login limit'
        },
        [pscustomobject]@{
            name = 'Gradle malformed packaged-client username'
            value = 'Farm Helper'
            message = 'packaged client username is invalid'
        }
    )
    foreach ($usernameCase in $usernameGradleCases) {
        Invoke-GradleExpectedFailure `
            -Name $usernameCase.name `
            -ExpectedMessage $usernameCase.message `
            -CandidateHash $sha256 `
            -DependencyHash $fabricApiSha256 `
            -RunDirectory $gradleBoundaryRunDirectory `
            -Username $usernameCase.value
    }
    Write-Output 'GRADLE_USERNAME_INVARIANT_NEGATIVES_OK count=3'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
