[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$prepareScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'prepare-player-cache.ps1'))
$powershellCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if ($null -eq $powershellCommand) {
    throw 'The synthetic suite requires pwsh.exe.'
}
$powershell = [IO.Path]::GetFullPath($powershellCommand.Source)

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]
        [bool] $Condition,

        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Write-Bytes {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [byte[]] $Bytes
    )

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllBytes($Path, $Bytes)
}

function Get-Sha1 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant()
}

function New-AssetFixture {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [switch] $IncludeSecondaryObject,

        [switch] $CorruptPrimaryObject
    )

    $sourceRoot = Join-Path $Root 'caches\fabric-loom'
    New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null

    $primaryBytes = [Text.Encoding]::UTF8.GetBytes('primary asset payload')
    $primarySourcePath = Join-Path $sourceRoot 'assets\objects\aa\placeholder'
    $primaryHash = ([Security.Cryptography.SHA1]::Create()).ComputeHash($primaryBytes)
    $primaryHashHex = -join ($primaryHash | ForEach-Object { $_.ToString('x2') })
    $primaryPath = Join-Path $sourceRoot (Join-Path (Join-Path 'assets\objects' $primaryHashHex.Substring(0, 2)) $primaryHashHex)
    if ($CorruptPrimaryObject) {
        Write-Bytes -Path $primaryPath -Bytes ([Text.Encoding]::UTF8.GetBytes('wrong primary bytes'))
    } else {
        Write-Bytes -Path $primaryPath -Bytes $primaryBytes
    }

    $objects = [ordered]@{
        'fixture/primary.bin' = [ordered]@{
            hash = $primaryHashHex
            size = $primaryBytes.Length
        }
    }
    $secondaryBytes = [Text.Encoding]::UTF8.GetBytes('secondary asset payload')
    $secondaryHashHex = $null
    $secondaryPath = $null
    if ($IncludeSecondaryObject) {
        $secondaryHash = ([Security.Cryptography.SHA1]::Create()).ComputeHash($secondaryBytes)
        $secondaryHashHex = -join ($secondaryHash | ForEach-Object { $_.ToString('x2') })
        $secondaryPath = Join-Path $sourceRoot (Join-Path (Join-Path 'assets\objects' $secondaryHashHex.Substring(0, 2)) $secondaryHashHex)
        Write-Bytes -Path $secondaryPath -Bytes $secondaryBytes
        $objects['fixture/secondary.bin'] = [ordered]@{
            hash = $secondaryHashHex
            size = $secondaryBytes.Length
        }
    }

    $index = [ordered]@{ objects = $objects }
    $indexText = $index | ConvertTo-Json -Compress -Depth 10
    $indexBytes = [Text.UTF8Encoding]::new($false).GetBytes($indexText)
    $indexPath = Join-Path $sourceRoot 'assets\indexes\fixture.json'
    Write-Bytes -Path $indexPath -Bytes $indexBytes
    $indexHash = Get-Sha1 -Path $indexPath

    Write-Bytes -Path (Join-Path $sourceRoot 'bin\loom.bin') -Bytes ([Text.Encoding]::UTF8.GetBytes('loom content'))
    Write-Bytes -Path (Join-Path $sourceRoot 'nested\deep\ordinary.txt') -Bytes ([Text.Encoding]::UTF8.GetBytes('ordinary content'))
    Write-Bytes -Path (Join-Path $sourceRoot 'cache.lock') -Bytes ([Text.Encoding]::UTF8.GetBytes('must not copy'))
    Write-Bytes -Path (Join-Path $sourceRoot 'daemon\daemon-state.bin') -Bytes ([Text.Encoding]::UTF8.GetBytes('must not copy'))

    $unrelatedHash = ('b' * 40)
    Write-Bytes `
        -Path (Join-Path $sourceRoot (Join-Path (Join-Path 'assets\objects' $unrelatedHash.Substring(0, 2)) $unrelatedHash)) `
        -Bytes ([Text.Encoding]::UTF8.GetBytes('unrelated source object'))

    return [pscustomobject]@{
        root = $sourceRoot
        indexRelativePath = 'assets\indexes\fixture.json'
        indexPath = $indexPath
        indexId = 'fixture'
        indexSha1 = $indexHash
        indexSize = [int64] $indexBytes.Length
        primaryHash = $primaryHashHex
        primarySize = [int64] $primaryBytes.Length
        primaryPath = $primaryPath
        secondaryHash = $secondaryHashHex
        secondarySize = if ($null -eq $secondaryBytes) { 0L } else { [int64] $secondaryBytes.Length }
        secondaryPath = $secondaryPath
        secondaryBytes = $secondaryBytes
        officialIndexUrl = "https://piston-meta.mojang.com/v1/packages/$indexHash/fixture.json"
        officialObjectBaseUrl = 'https://resources.download.minecraft.net/'
    }
}

function Copy-Fixture {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Source,

        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Source '*') -Destination $Destination -Recurse -Force
}

function Get-SourceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root
    )

    $rootItem = Get-Item -LiteralPath $Root -Force -ErrorAction Stop
    $rows = [System.Collections.Generic.List[object]]::new()
    $directories = [System.Collections.Generic.Stack[object]]::new()
    $rootAttributes = [IO.FileAttributes] $rootItem.Attributes
    $rootIsReparse = ($rootAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    $rows.Add([pscustomobject][ordered]@{
            path = ''
            type = if ($rootIsReparse) { 'reparse' } else { 'directory' }
            attributes = [int64] $rootAttributes
            reparse = [bool] $rootIsReparse
            size = $null
            sha1 = $null
        })
    $directories.Push($rootItem)

    while ($directories.Count -gt 0) {
        $directory = $directories.Pop()
        $directoryAttributes = [IO.FileAttributes] $directory.Attributes
        if (($directoryAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            continue
        }

        foreach ($entry in @(Get-ChildItem -LiteralPath $directory.FullName -Force -ErrorAction Stop | Sort-Object Name)) {
            $attributes = [IO.FileAttributes] $entry.Attributes
            $isReparse = ($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            $isDirectory = [bool] $entry.PSIsContainer
            $row = [ordered]@{
                path = [IO.Path]::GetRelativePath($Root, $entry.FullName).Replace('/', '\')
                type = if ($isReparse) { 'reparse' } elseif ($isDirectory) { 'directory' } else { 'file' }
                attributes = [int64] $attributes
                reparse = [bool] $isReparse
                size = $null
                sha1 = $null
            }
            if (-not $isDirectory -and -not $isReparse) {
                $row.size = [int64] $entry.Length
                $row.sha1 = Get-Sha1 -Path $entry.FullName
            }
            $rows.Add([pscustomobject] $row)
            if ($isDirectory -and -not $isReparse) {
                $directories.Push($entry)
            }
        }
    }

    $snapshot = [ordered]@{
        entries = @($rows | Sort-Object path)
    }
    return ($snapshot | ConvertTo-Json -Compress -Depth 5)
}

function Get-SnapshotReparseEntries {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Snapshot
    )

    $parsed = $Snapshot | ConvertFrom-Json
    return ,@($parsed.entries | Where-Object { [bool] $_.reparse })
}

function Invoke-Preparation {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Fixture,

        [Parameter(Mandatory = $true)]
        [string] $DestinationRoot,

        [Parameter(Mandatory = $true)]
        [string] $ReceiptPath,

        [string] $ObjectBaseUrl,

        [switch] $AllowLoopback,

        [int] $StallTimeoutSeconds = 20,

        [int] $MaxDownloadSeconds = 120,

        [int] $CleanupTimeoutSeconds = 10
    )

    $arguments = @(
        '-NoProfile',
        '-NonInteractive',
        '-File', $prepareScript,
        '-SourceRoot', $Fixture.root,
        '-DestinationRoot', $DestinationRoot,
        '-AssetIndexId', $Fixture.indexId,
        '-AssetIndexSha1', $Fixture.indexSha1,
        '-AssetIndexSize', ([string] $Fixture.indexSize),
        '-AssetIndexUrl', $Fixture.officialIndexUrl,
        '-ObjectBaseUrl', $(if ($ObjectBaseUrl) { $ObjectBaseUrl } else { $Fixture.officialObjectBaseUrl }),
        '-AssetIndexRelativePath', $Fixture.indexRelativePath,
        '-ReceiptPath', $ReceiptPath,
        '-StallTimeoutSeconds', ([string] $StallTimeoutSeconds),
        '-MaxDownloadSeconds', ([string] $MaxDownloadSeconds),
        '-CleanupTimeoutSeconds', ([string] $CleanupTimeoutSeconds)
    )
    if ($AllowLoopback) {
        $arguments += '-AllowLoopbackUris'
    }

    $output = @(& $powershell @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Preparation unexpectedly failed (exit=$exitCode): $($output -join "`n")"
    }
    $receipt = ($output -join "`n") | ConvertFrom-Json
    Assert-True -Condition ([string] $receipt.status -eq 'succeeded') -Message 'Successful preparation returned a non-success receipt.'
    return $receipt
}

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedMessage,

        [Parameter(Mandatory = $true)]
        [object] $Fixture,

        [Parameter(Mandatory = $true)]
        [string] $DestinationRoot,

        [Parameter(Mandatory = $true)]
        [string] $ReceiptPath,

        [string] $ObjectBaseUrl,

        [switch] $AllowLoopback,

        [int] $StallTimeoutSeconds = 20,

        [int] $MaxDownloadSeconds = 120,

        [int] $CleanupTimeoutSeconds = 10,

        [switch] $ReturnOutput
    )

    $arguments = @(
        '-NoProfile',
        '-NonInteractive',
        '-File', $prepareScript,
        '-SourceRoot', $Fixture.root,
        '-DestinationRoot', $DestinationRoot,
        '-AssetIndexId', $Fixture.indexId,
        '-AssetIndexSha1', $Fixture.indexSha1,
        '-AssetIndexSize', ([string] $Fixture.indexSize),
        '-AssetIndexUrl', $Fixture.officialIndexUrl,
        '-ObjectBaseUrl', $(if ($ObjectBaseUrl) { $ObjectBaseUrl } else { $Fixture.officialObjectBaseUrl }),
        '-AssetIndexRelativePath', $Fixture.indexRelativePath,
        '-ReceiptPath', $ReceiptPath,
        '-StallTimeoutSeconds', ([string] $StallTimeoutSeconds),
        '-MaxDownloadSeconds', ([string] $MaxDownloadSeconds),
        '-CleanupTimeoutSeconds', ([string] $CleanupTimeoutSeconds)
    )
    if ($AllowLoopback) {
        $arguments += '-AllowLoopbackUris'
    }

    $output = @(& $powershell @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = $output -join "`n"
    Assert-True -Condition ($exitCode -ne 0) -Message "$Name unexpectedly passed."
    Assert-True `
        -Condition ($text -match [Regex]::Escape($ExpectedMessage)) `
        -Message "$Name failed for the wrong reason: $text"
    if ($ReturnOutput) {
        return [pscustomobject]@{
            text = $text
            exitCode = $exitCode
        }
    }
    $script:passCount++
    Write-Output "TEST_OK $Name"
}

function Add-ProcessArgument {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.ProcessStartInfo] $StartInfo,

        [Parameter(Mandatory = $true)]
        [string] $Value
    )

    [void] $StartInfo.ArgumentList.Add($Value)
}

function Start-ObjectServer {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [Parameter(Mandatory = $true)]
        [string] $ServerScript,

        [Parameter(Mandatory = $true)]
        [string] $PortPath,

        [switch] $Stall
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $powershell
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    Add-ProcessArgument -StartInfo $startInfo -Value '-NoProfile'
    Add-ProcessArgument -StartInfo $startInfo -Value '-NonInteractive'
    Add-ProcessArgument -StartInfo $startInfo -Value '-File'
    Add-ProcessArgument -StartInfo $startInfo -Value $ServerScript
    Add-ProcessArgument -StartInfo $startInfo -Value '-Root'
    Add-ProcessArgument -StartInfo $startInfo -Value $Root
    Add-ProcessArgument -StartInfo $startInfo -Value '-PortPath'
    Add-ProcessArgument -StartInfo $startInfo -Value $PortPath
    if ($Stall) {
        Add-ProcessArgument -StartInfo $startInfo -Value '-Stall'
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        $process.Dispose()
        throw 'Unable to start the loopback object server.'
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $PortPath -PathType Leaf)) {
        if ($process.HasExited) {
            throw "Loopback object server exited early with code $($process.ExitCode)."
        }
        if ([DateTime]::UtcNow -gt $deadline) {
            throw 'Loopback object server did not publish a port in time.'
        }
        Start-Sleep -Milliseconds 50
    }
    $port = [int] (Get-Content -LiteralPath $PortPath -Raw)
    return [pscustomobject]@{
        process = $process
        port = $port
    }
}

function Stop-ObjectServer {
    param(
        [AllowNull()]
        [object] $Server
    )

    if ($null -eq $Server -or $null -eq $Server.process) {
        return
    }
    try {
        if (-not $Server.process.HasExited) {
            $Server.process.Kill()
            [void] $Server.process.WaitForExit(5000)
        }
    } finally {
        $Server.process.Dispose()
    }
}

$serverScript = $null
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('farmhelper-player-cache-preparation-tests-' + [Guid]::NewGuid().ToString('N'))
$destinationTestRoot = Join-Path $repositoryRoot ('build\verification\player-cache-preparation-tests-' + [Guid]::NewGuid().ToString('N'))
$server = $null
$stallServer = $null
$script:passCount = 0

New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
New-Item -ItemType Directory -Path $destinationTestRoot -Force | Out-Null

try {
    $astErrors = @()
    [System.Management.Automation.Language.Parser]::ParseFile($prepareScript, [ref] $null, [ref] $astErrors) | Out-Null
    Assert-True -Condition ($astErrors.Count -eq 0) -Message "Preparation script AST errors: $($astErrors -join '; ')"
    $testScript = [IO.Path]::GetFullPath($PSCommandPath)
    $testAstErrors = @()
    [System.Management.Automation.Language.Parser]::ParseFile($testScript, [ref] $null, [ref] $testAstErrors) | Out-Null
    Assert-True -Condition ($testAstErrors.Count -eq 0) -Message "Synthetic suite AST errors: $($testAstErrors -join '; ')"
    Write-Output 'TEST_OK AST parse errors=0'
    $script:passCount++

    $sourceFixture = New-AssetFixture -Root (Join-Path $fixtureRoot 'baseline-source')
    New-Item -ItemType Directory -Path (Join-Path $sourceFixture.root 'empty-source-directory') -Force | Out-Null
    $sourceSnapshotBefore = Get-SourceSnapshot -Root $sourceFixture.root
    $destinationRoot = Join-Path $destinationTestRoot 'baseline\caches\fabric-loom'
    New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $destinationRoot 'empty-destination-directory') -Force | Out-Null
    $unrelatedDestinationPath = Join-Path $destinationRoot 'unrelated-extra.bin'
    Write-Bytes -Path $unrelatedDestinationPath -Bytes ([Text.Encoding]::UTF8.GetBytes('preserve me'))
    $extraDestinationObject = Join-Path $destinationRoot 'assets\objects\zz\unrelated-extra'
    Write-Bytes -Path $extraDestinationObject -Bytes ([Text.Encoding]::UTF8.GetBytes('preserve object'))
    $baselineReceiptPath = Join-Path $destinationTestRoot 'baseline-receipt.json'

    $firstReceipt = Invoke-Preparation `
        -Fixture $sourceFixture `
        -DestinationRoot $destinationRoot `
        -ReceiptPath $baselineReceiptPath
    Assert-True -Condition ([int] $firstReceipt.work.networkRequests -eq 0) -Message 'Baseline preparation unexpectedly used network.'
    Assert-True -Condition ([int] $firstReceipt.work.copyOperations -gt 0) -Message 'Baseline preparation reported no copy work.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging'))) -Message 'Successful preparation left its owned staging root behind.'
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $destinationRoot 'bin\loom.bin') -PathType Leaf) -Message 'Fabric Loom content did not land at the exact destination root.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $destinationRoot 'fabric-loom'))) -Message 'Observed nested fabric-loom destination bug reproduced.'
    $destinationSiblingEntries = @(Get-ChildItem -LiteralPath (Split-Path -Parent $destinationRoot) -Force | Where-Object Name -like 'fabric-loom*')
    Assert-True -Condition ($destinationSiblingEntries.Count -eq 1 -and $destinationSiblingEntries[0].FullName -eq $destinationRoot) -Message 'Observed suffix staging destination bug reproduced.'
    Assert-True -Condition ((Get-Content -LiteralPath $unrelatedDestinationPath -Raw) -eq 'preserve me') -Message 'Unrelated destination file was changed.'
    Assert-True -Condition (Test-Path -LiteralPath $extraDestinationObject -PathType Leaf) -Message 'Unrelated destination object was removed.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $destinationRoot 'cache.lock'))) -Message 'Live lock state was copied.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $destinationRoot 'daemon'))) -Message 'Daemon state was copied.'
    $sourceSnapshotAfter = Get-SourceSnapshot -Root $sourceFixture.root
    $destinationSnapshotAfterFirst = Get-SourceSnapshot -Root $destinationRoot
    Assert-True -Condition ((Get-SnapshotReparseEntries -Snapshot $sourceSnapshotBefore).Count -eq 0 -and (Get-SnapshotReparseEntries -Snapshot $sourceSnapshotAfter).Count -eq 0) -Message 'Baseline source snapshot contained a reparse entry.'
    Assert-True -Condition ((Get-SnapshotReparseEntries -Snapshot $destinationSnapshotAfterFirst).Count -eq 0) -Message 'Baseline destination snapshot contained a reparse entry.'
    $destinationSnapshotAfterFirstData = $destinationSnapshotAfterFirst | ConvertFrom-Json
    $destinationEntriesAfterFirst = @($destinationSnapshotAfterFirstData.entries)
    Assert-True -Condition (@($destinationEntriesAfterFirst | Where-Object { $_.path -eq 'empty-destination-directory' -and $_.type -eq 'directory' }).Count -eq 1) -Message 'Complete destination snapshot omitted the empty directory.'
    Assert-True -Condition ($sourceSnapshotBefore -eq $sourceSnapshotAfter) -Message 'Source tree changed during successful preparation.'
    Write-Output 'TEST_OK exact relative base, no nested/suffix destination, preservation, live-state exclusion, no source delete, empty-directory snapshot, staging residue cleanup'
    $script:passCount++

    $emptySnapshotProbe = Join-Path $destinationRoot 'snapshot-probe-empty'
    $snapshotBeforeEmptyProbe = Get-SourceSnapshot -Root $destinationRoot
    New-Item -ItemType Directory -Path $emptySnapshotProbe -Force | Out-Null
    $snapshotAfterEmptyProbe = Get-SourceSnapshot -Root $destinationRoot
    Assert-True -Condition ($snapshotBeforeEmptyProbe -ne $snapshotAfterEmptyProbe) -Message 'Tree snapshot did not detect an added empty directory.'
    Remove-Item -LiteralPath $emptySnapshotProbe -Force -ErrorAction Stop

    $attributeSnapshotProbe = Join-Path $destinationRoot 'snapshot-probe-attributes.txt'
    Write-Bytes -Path $attributeSnapshotProbe -Bytes ([Text.Encoding]::UTF8.GetBytes('attribute probe'))
    $snapshotBeforeAttributeProbe = Get-SourceSnapshot -Root $destinationRoot
    $originalAttributeProbeAttributes = [IO.File]::GetAttributes($attributeSnapshotProbe)
    [IO.File]::SetAttributes($attributeSnapshotProbe, $originalAttributeProbeAttributes -bor [IO.FileAttributes]::Hidden)
    $snapshotAfterAttributeProbe = Get-SourceSnapshot -Root $destinationRoot
    Assert-True -Condition ($snapshotBeforeAttributeProbe -ne $snapshotAfterAttributeProbe) -Message 'Tree snapshot did not detect an attribute change.'
    [IO.File]::SetAttributes($attributeSnapshotProbe, $originalAttributeProbeAttributes)
    Remove-Item -LiteralPath $attributeSnapshotProbe -Force -ErrorAction Stop

    $snapshotReparseTarget = Join-Path $fixtureRoot 'snapshot-reparse-target'
    New-Item -ItemType Directory -Path $snapshotReparseTarget -Force | Out-Null
    $snapshotReparseProbe = Join-Path $destinationRoot 'snapshot-probe-reparse'
    New-Item -ItemType Junction -Path $snapshotReparseProbe -Target $snapshotReparseTarget -ErrorAction Stop | Out-Null
    $snapshotWithReparseProbe = Get-SourceSnapshot -Root $destinationRoot
    Assert-True -Condition ((Get-SnapshotReparseEntries -Snapshot $snapshotWithReparseProbe).Count -eq 1) -Message 'Tree snapshot did not record the reparse entry.'
    Remove-Item -LiteralPath $snapshotReparseProbe -Force -ErrorAction Stop
    Write-Output 'TEST_OK tree snapshot detects empty directories, attributes, and reparse entries'
    $script:passCount++

    $destinationSnapshotBeforeRerun = Get-SourceSnapshot -Root $destinationRoot
    $secondReceipt = Invoke-Preparation `
        -Fixture $sourceFixture `
        -DestinationRoot $destinationRoot `
        -ReceiptPath $baselineReceiptPath
    $secondReceiptText = Get-Content -LiteralPath $baselineReceiptPath -Raw
    $thirdReceipt = Invoke-Preparation `
        -Fixture $sourceFixture `
        -DestinationRoot $destinationRoot `
        -ReceiptPath $baselineReceiptPath
    $thirdReceiptText = Get-Content -LiteralPath $baselineReceiptPath -Raw
    $destinationSnapshotAfterRerun = Get-SourceSnapshot -Root $destinationRoot
    Assert-True -Condition ([int] $secondReceipt.work.networkRequests -eq 0) -Message 'Second identical run used network.'
    Assert-True -Condition ([int] $secondReceipt.work.copyOperations -eq 0) -Message 'Second identical run performed copy work.'
    Assert-True -Condition ([int] $thirdReceipt.work.networkRequests -eq 0 -and [int] $thirdReceipt.work.copyOperations -eq 0) -Message 'Third identical run performed work.'
    Assert-True -Condition ($secondReceiptText -eq $thirdReceiptText) -Message 'Identical zero-work receipts were not deterministic.'
    Assert-True -Condition ([bool] $secondReceipt.idempotence.zeroNetworkAndCopyOnMatchingRerun) -Message 'Zero-work idempotence receipt flag was false.'
    Assert-True -Condition ($destinationSnapshotBeforeRerun -eq $destinationSnapshotAfterRerun) -Message 'Repeat no-mutation audit found destination changes.'
    Assert-True -Condition ((Get-SnapshotReparseEntries -Snapshot $destinationSnapshotAfterRerun).Count -eq 0) -Message 'Repeat destination snapshot contained a reparse entry.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging'))) -Message 'Matching rerun left its owned staging root behind.'
    Write-Output 'TEST_OK deterministic zero-network zero-copy idempotent rerun, complete tree identity, and residue-free repeat'
    $script:passCount++

    $mismatchedIndexFixture = New-AssetFixture -Root (Join-Path $fixtureRoot 'mismatched-index')
    $mismatchedIndexText = '{"objects":{}}'
    Write-Bytes -Path $mismatchedIndexFixture.indexPath -Bytes ([Text.UTF8Encoding]::new($false).GetBytes($mismatchedIndexText))
    $mismatchedIndexDestination = Join-Path $destinationTestRoot 'mismatched-index\caches\fabric-loom'
    New-Item -ItemType Directory -Path $mismatchedIndexDestination -Force | Out-Null
    $mismatchedIndexMarker = Join-Path $mismatchedIndexDestination 'marker.txt'
    Write-Bytes -Path $mismatchedIndexMarker -Bytes ([Text.Encoding]::UTF8.GetBytes('unchanged'))
    Invoke-ExpectedFailure `
        -Name 'mismatched cached index' `
        -ExpectedMessage 'Asset index identity mismatch' `
        -Fixture $mismatchedIndexFixture `
        -DestinationRoot $mismatchedIndexDestination `
        -ReceiptPath (Join-Path $destinationTestRoot 'mismatched-index-receipt.json')
    Assert-True -Condition ((Get-Content -LiteralPath $mismatchedIndexMarker -Raw) -eq 'unchanged') -Message 'Mismatched index failure mutated destination.'

    $corruptSourceFixture = New-AssetFixture `
        -Root (Join-Path $fixtureRoot 'corrupt-source-object') `
        -CorruptPrimaryObject
    Invoke-ExpectedFailure `
        -Name 'source object hash mismatch' `
        -ExpectedMessage 'Source asset object mismatch' `
        -Fixture $corruptSourceFixture `
        -DestinationRoot (Join-Path $destinationTestRoot 'corrupt-source-object\caches\fabric-loom') `
        -ReceiptPath (Join-Path $destinationTestRoot 'corrupt-source-object-receipt.json')

    $destinationMismatchRoot = Join-Path $destinationTestRoot 'destination-object-mismatch\caches\fabric-loom'
    New-Item -ItemType Directory -Path (Split-Path -Parent $destinationMismatchRoot) -Force | Out-Null
    $destinationMismatchObject = Join-Path $destinationMismatchRoot (Join-Path (Join-Path 'assets\objects' $sourceFixture.primaryHash.Substring(0, 2)) $sourceFixture.primaryHash)
    Write-Bytes -Path $destinationMismatchObject -Bytes ([Text.Encoding]::UTF8.GetBytes('wrong destination bytes'))
    Invoke-ExpectedFailure `
        -Name 'destination object hash mismatch' `
        -ExpectedMessage 'Destination asset object mismatch' `
        -Fixture $sourceFixture `
        -DestinationRoot $destinationMismatchRoot `
        -ReceiptPath (Join-Path $destinationTestRoot 'destination-object-mismatch-receipt.json')
    Write-Output 'TEST_OK mismatched cached index and object SHA-1 rejection'
    $script:passCount++

    $reparseSourceFixture = New-AssetFixture -Root (Join-Path $fixtureRoot 'reparse-source')
    $reparseSourceOutside = Join-Path $fixtureRoot 'reparse-source-outside'
    New-Item -ItemType Directory -Path $reparseSourceOutside -Force | Out-Null
    New-Item -ItemType Junction `
        -Path (Join-Path $reparseSourceFixture.root 'linked') `
        -Target $reparseSourceOutside `
        -ErrorAction Stop | Out-Null
    Invoke-ExpectedFailure `
        -Name 'source reparse path' `
        -ExpectedMessage 'reparse-point component' `
        -Fixture $reparseSourceFixture `
        -DestinationRoot (Join-Path $destinationTestRoot 'reparse-source\caches\fabric-loom') `
        -ReceiptPath (Join-Path $destinationTestRoot 'reparse-source-receipt.json')

    $reparseDestinationRoot = Join-Path $destinationTestRoot 'reparse-destination\caches\fabric-loom'
    New-Item -ItemType Directory -Path (Split-Path -Parent $reparseDestinationRoot) -Force | Out-Null
    $reparseDestinationOutside = Join-Path $fixtureRoot 'reparse-destination-outside'
    New-Item -ItemType Directory -Path $reparseDestinationOutside -Force | Out-Null
    New-Item -ItemType Junction `
        -Path (Join-Path $reparseDestinationRoot 'assets') `
        -Target $reparseDestinationOutside `
        -ErrorAction Stop | Out-Null
    Invoke-ExpectedFailure `
        -Name 'destination reparse path' `
        -ExpectedMessage 'reparse-point component' `
        -Fixture $sourceFixture `
        -DestinationRoot $reparseDestinationRoot `
        -ReceiptPath (Join-Path $destinationTestRoot 'reparse-destination-receipt.json')
    $fileAncestorFixture = New-AssetFixture -Root (Join-Path $fixtureRoot 'ordinary-file-ancestor')
    Remove-Item -LiteralPath (Join-Path $fileAncestorFixture.root 'assets\objects') -Recurse -Force
    Write-Bytes `
        -Path (Join-Path $fileAncestorFixture.root 'assets\objects') `
        -Bytes ([Text.Encoding]::UTF8.GetBytes('ordinary file where a directory is required'))
    Invoke-ExpectedFailure `
        -Name 'ordinary-file ancestor' `
        -ExpectedMessage 'non-directory ancestor' `
        -Fixture $fileAncestorFixture `
        -DestinationRoot (Join-Path $destinationTestRoot 'ordinary-file-ancestor\caches\fabric-loom') `
        -ReceiptPath (Join-Path $destinationTestRoot 'ordinary-file-ancestor-receipt.json')
    Write-Output 'TEST_OK source/destination reparse and ordinary-file-only enforcement'
    $script:passCount++

    $userInfoFixture = New-AssetFixture -Root (Join-Path $fixtureRoot 'userinfo-uri')
    $userInfoDestinationRoot = Join-Path $destinationTestRoot 'userinfo-uri\caches\fabric-loom'
    New-Item -ItemType Directory -Path $userInfoDestinationRoot -Force | Out-Null
    $userInfoReceiptPath = Join-Path $destinationTestRoot 'userinfo-uri-receipt.json'
    $userInfoDestinationBefore = Get-SourceSnapshot -Root $userInfoDestinationRoot
    $userInfoSecret = 'userinfo-secret-for-test'
    $userInfoResult = Invoke-ExpectedFailure `
        -Name 'userinfo URI rejection' `
        -ExpectedMessage 'may not contain URI credentials' `
        -Fixture $userInfoFixture `
        -DestinationRoot $userInfoDestinationRoot `
        -ReceiptPath $userInfoReceiptPath `
        -ObjectBaseUrl "https://audit-user:$userInfoSecret@resources.download.minecraft.net/" `
        -ReturnOutput
    $userInfoOutput = [string] $userInfoResult.text
    $userInfoReceiptText = Get-Content -LiteralPath $userInfoReceiptPath -Raw
    $userInfoReceipt = $userInfoReceiptText | ConvertFrom-Json
    Assert-True -Condition ($userInfoOutput -notmatch [Regex]::Escape($userInfoSecret)) -Message 'Userinfo secret appeared in process/error output.'
    Assert-True -Condition ($userInfoReceiptText -notmatch [Regex]::Escape($userInfoSecret)) -Message 'Userinfo secret appeared in the failure receipt.'
    Assert-True -Condition ($null -eq $userInfoReceipt.assetIndex.url -and $null -eq $userInfoReceipt.assetIndex.objectBaseUrl) -Message 'Credential-bearing URI values were serialized into the failure receipt.'
    Assert-True -Condition ([int] $userInfoReceipt.work.networkRequests -eq 0 -and [int] $userInfoReceipt.cleanup.ownedHelperProcessesStarted -eq 0) -Message 'Userinfo rejection started network or helper work.'
    Assert-True -Condition ($userInfoDestinationBefore -eq (Get-SourceSnapshot -Root $userInfoDestinationRoot)) -Message 'Userinfo rejection mutated the destination tree.'
    Assert-True -Condition (@(Get-ChildItem -LiteralPath $userInfoDestinationRoot -Force -Recurse -File).Count -eq 0) -Message 'Userinfo rejection created a destination file.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging'))) -Message 'Userinfo rejection left owned staging residue.'
    Write-Output 'TEST_OK userinfo URI rejection before process start with credential-safe output and receipt'
    $script:passCount++

    $fetchFixture = New-AssetFixture `
        -Root (Join-Path $fixtureRoot 'missing-object-fetch') `
        -IncludeSecondaryObject
    Remove-Item -LiteralPath $fetchFixture.secondaryPath -Force
    $fetchServerRoot = Join-Path $fixtureRoot 'missing-object-fetch-server-assets'
    $fetchServerObjectPath = Join-Path $fetchServerRoot (Join-Path (Join-Path 'assets\objects' $fetchFixture.secondaryHash.Substring(0, 2)) $fetchFixture.secondaryHash)
    Write-Bytes -Path $fetchServerObjectPath -Bytes $fetchFixture.secondaryBytes
    $fetchDestinationRoot = Join-Path $destinationTestRoot 'missing-object-fetch\caches\fabric-loom'
    New-Item -ItemType Directory -Path $fetchDestinationRoot -Force | Out-Null
    $fetchExtra = Join-Path $fetchDestinationRoot 'unrelated-extra.txt'
    Write-Bytes -Path $fetchExtra -Bytes ([Text.Encoding]::UTF8.GetBytes('keep during fetch'))

    $serverScript = Join-Path $fixtureRoot 'object-server.ps1'
    $serverSource = @'
param(
    [Parameter(Mandatory = $true)] [string] $Root,
    [Parameter(Mandatory = $true)] [string] $PortPath,
    [switch] $Stall
)
$ErrorActionPreference = 'Stop'
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
[IO.File]::WriteAllText($PortPath, ([string] $listener.LocalEndpoint.Port), [Text.Encoding]::ASCII)
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $null
        $reader = $null
        try {
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 4096, $true)
            $requestLine = $reader.ReadLine()
            while ($null -ne ($line = $reader.ReadLine()) -and $line -ne '') { }
            $parts = $requestLine.Split(' ')
            $requested = [Uri]::UnescapeDataString($parts[1]).TrimStart('/')
            if ($Stall) {
                $header = "HTTP/1.1 200 OK`r`nContent-Length: 2147483647`r`nConnection: keep-alive`r`n`r`n"
                $headerBytes = [Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Flush()
                Start-Sleep -Seconds 300
            } else {
                if ($requested.Contains('..')) {
                    $header = "HTTP/1.1 400 Bad Request`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
                    $body = [byte[]]::new(0)
                } else {
                    $path = Join-Path $Root ($requested.Replace('/', '\'))
                    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
                        $header = "HTTP/1.1 404 Not Found`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
                        $body = [byte[]]::new(0)
                    } else {
                        $body = [IO.File]::ReadAllBytes($path)
                        $header = "HTTP/1.1 200 OK`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
                    }
                }
                $headerBytes = [Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                if ($body.Length -gt 0) { $stream.Write($body, 0, $body.Length) }
                $stream.Flush()
            }
        } finally {
            if ($null -ne $reader) { $reader.Dispose() }
            if ($null -ne $stream) { $stream.Dispose() }
            $client.Dispose()
        }
    }
} finally {
    $listener.Stop()
}
'@
    [IO.File]::WriteAllText($serverScript, $serverSource, [Text.UTF8Encoding]::new($false))
    $fetchPortPath = Join-Path $fixtureRoot 'fetch-port.txt'
    $server = Start-ObjectServer `
        -Root (Join-Path $fetchServerRoot 'assets\objects') `
        -ServerScript $serverScript `
        -PortPath $fetchPortPath
    $fetchBaseUrl = "http://127.0.0.1:$($server.port)/"
    $fetchReceipt = Invoke-Preparation `
        -Fixture $fetchFixture `
        -DestinationRoot $fetchDestinationRoot `
        -ReceiptPath (Join-Path $destinationTestRoot 'missing-object-fetch-receipt.json') `
        -ObjectBaseUrl $fetchBaseUrl `
        -AllowLoopback
    Assert-True -Condition ([int] $fetchReceipt.work.networkRequests -eq 1) -Message 'Missing-object run did not fetch exactly one object.'
    Assert-True -Condition ([int] $fetchReceipt.work.assetObjectsFetched -eq 1) -Message 'Missing-object run did not record one fetched object.'
    Assert-True -Condition ([int] $fetchReceipt.work.assetObjectsCopiedFromCache -eq 1) -Message 'Missing-object run fetched an object that was already cached in source.'
    Assert-True -Condition ([int] $fetchReceipt.cleanup.ownedHelperProcessesStarted -eq 1) -Message 'Missing-object run did not record its owned helper.'
    Assert-True -Condition ([int] $fetchReceipt.cleanup.ownedHelperProcessesSurvived -eq 0 -and [bool] $fetchReceipt.cleanup.unrelatedProcessesTouched -eq $false) -Message 'Missing-object cleanup receipt was unsafe.'
    $fetchedDestinationPath = Join-Path $fetchDestinationRoot (Join-Path (Join-Path 'assets\objects' $fetchFixture.secondaryHash.Substring(0, 2)) $fetchFixture.secondaryHash)
    Assert-True -Condition (Test-Path -LiteralPath $fetchedDestinationPath -PathType Leaf) -Message 'Missing official object was not placed.'
    Assert-True -Condition ((Get-Sha1 -Path $fetchedDestinationPath) -eq $fetchFixture.secondaryHash) -Message 'Fetched official object failed SHA-1 verification.'
    Assert-True -Condition (Test-Path -LiteralPath $fetchExtra -PathType Leaf) -Message 'Unrelated destination extra was removed during fetch.'
    Stop-ObjectServer -Server $server
    $server = $null
    Write-Output 'TEST_OK missing-only official fetch, cached-object copy, object verification, extra preservation'
    $script:passCount++

    $stallFixture = New-AssetFixture `
        -Root (Join-Path $fixtureRoot 'bounded-stall') `
        -IncludeSecondaryObject
    Remove-Item -LiteralPath $stallFixture.secondaryPath -Force
    $stallDestinationRoot = Join-Path $destinationTestRoot 'bounded-stall\caches\fabric-loom'
    New-Item -ItemType Directory -Path $stallDestinationRoot -Force | Out-Null
    $stallPortPath = Join-Path $fixtureRoot 'stall-port.txt'
    $stallServer = Start-ObjectServer `
        -Root (Join-Path $stallFixture.root 'assets\objects') `
        -ServerScript $serverScript `
        -PortPath $stallPortPath `
        -Stall
    $stallBaseUrl = "http://127.0.0.1:$($stallServer.port)/"
    $stallReceiptPath = Join-Path $destinationTestRoot 'bounded-stall-receipt.json'
    Invoke-ExpectedFailure `
        -Name 'bounded stalled download' `
        -ExpectedMessage 'download stalled for more than' `
        -Fixture $stallFixture `
        -DestinationRoot $stallDestinationRoot `
        -ReceiptPath $stallReceiptPath `
        -ObjectBaseUrl $stallBaseUrl `
        -AllowLoopback `
        -StallTimeoutSeconds 1 `
        -MaxDownloadSeconds 3 `
        -CleanupTimeoutSeconds 2
    $stallReceipt = Get-Content -LiteralPath $stallReceiptPath -Raw | ConvertFrom-Json
    Assert-True -Condition ([int] $stallReceipt.cleanup.ownedHelperProcessesStarted -eq 1) -Message 'Stall receipt did not record an owned helper.'
    Assert-True -Condition ([int] $stallReceipt.cleanup.ownedHelperProcessesTerminated -eq 1) -Message 'Stall receipt did not record bounded owned-helper cleanup.'
    Assert-True -Condition ([int] $stallReceipt.cleanup.ownedHelperProcessesSurvived -eq 0 -and [bool] $stallReceipt.cleanup.cleanupBounded -eq $true) -Message 'Stall cleanup was not bounded.'
    Assert-True -Condition ([bool] $stallReceipt.cleanup.unrelatedProcessesTouched -eq $false) -Message 'Stall cleanup receipt claimed unrelated process control.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging'))) -Message 'Failed preparation left its owned staging root behind.'
    Stop-ObjectServer -Server $stallServer
    $stallServer = $null
    Write-Output 'TEST_OK bounded stall timeout and owned-only helper cleanup receipt'
    $script:passCount++

    Write-Output "PLAYER_CACHE_PREPARATION_TESTS_OK count=$script:passCount"
} finally {
    Stop-ObjectServer -Server $server
    Stop-ObjectServer -Server $stallServer
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $destinationTestRoot) {
        Remove-Item -LiteralPath $destinationTestRoot -Recurse -Force
    }
}
