[CmdletBinding(DefaultParameterSetName = 'Prepare')]
param(
    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [string] $SourceRoot,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [string] $DestinationRoot,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')]
    [string] $AssetIndexId,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $AssetIndexSha1,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [ValidateRange(0, [long]::MaxValue)]
    [long] $AssetIndexSize,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [string] $AssetIndexUrl,

    [Parameter(ParameterSetName = 'Prepare', Mandatory = $true)]
    [string] $ObjectBaseUrl,

    [Parameter(ParameterSetName = 'Prepare')]
    [string] $AssetIndexRelativePath,

    [Parameter(ParameterSetName = 'Prepare')]
    [string] $ReceiptPath,

    [Parameter(ParameterSetName = 'Prepare')]
    [ValidateRange(1, 600)]
    [int] $StallTimeoutSeconds = 20,

    [Parameter(ParameterSetName = 'Prepare')]
    [ValidateRange(2, 3600)]
    [int] $MaxDownloadSeconds = 120,

    [Parameter(ParameterSetName = 'Prepare')]
    [ValidateRange(1, 600)]
    [int] $CleanupTimeoutSeconds = 10,

    [Parameter(ParameterSetName = 'Prepare')]
    [switch] $AllowLoopbackUris,

    [Parameter(ParameterSetName = 'DownloadHelper', Mandatory = $true)]
    [switch] $DownloadHelper,

    [Parameter(ParameterSetName = 'DownloadHelper', Mandatory = $true)]
    [string] $DownloadUri,

    [Parameter(ParameterSetName = 'DownloadHelper', Mandatory = $true)]
    [string] $DownloadOutputPath,

    [Parameter(ParameterSetName = 'DownloadHelper', Mandatory = $true)]
    [string] $DownloadProgressPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$scriptPath = [IO.Path]::GetFullPath($PSCommandPath)

function Get-FullPathFromWorktree {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'A path may not be empty.'
    }

    $candidate = $Path
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $repositoryRoot $candidate
    }

    try {
        return [IO.Path]::GetFullPath($candidate)
    } catch {
        throw "Path is not valid: $Path"
    }
}

function Get-ComparisonPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($root)) {
        throw "Path has no filesystem root: $Path"
    }

    if ($fullPath.Equals($root, [StringComparison]::OrdinalIgnoreCase)) {
        return $root
    }
    return $fullPath.TrimEnd('\')
}

function Test-PathEqualOrUnder {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Root
    )

    $normalizedPath = Get-ComparisonPath -Path $Path
    $normalizedRoot = Get-ComparisonPath -Path $Root
    $rootPrefix = if ($normalizedRoot.EndsWith('\')) {
        $normalizedRoot
    } else {
        $normalizedRoot + '\'
    }
    return $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-NoReparsePathComponents {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    $pathRoot = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($pathRoot)) {
        throw "$Description has no usable filesystem root: $Path"
    }

    $rootItem = Get-Item -LiteralPath $pathRoot -Force -ErrorAction Stop
    $rootAttributes = [IO.FileAttributes] $rootItem.Attributes
    if (($rootAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description contains a reparse-point component: $pathRoot"
    }

    $currentPath = $pathRoot
    $remaining = $fullPath.Substring($pathRoot.Length)
    $components = $remaining.Split('\', [StringSplitOptions]::RemoveEmptyEntries)
    for ($index = 0; $index -lt $components.Count; $index++) {
        $component = $components[$index]
        $currentPath = [IO.Path]::Combine($currentPath, $component)
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) {
            break
        }

        $attributes = [IO.FileAttributes] $item.Attributes
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contains a reparse-point component: $currentPath"
        }
        if ($index -lt ($components.Count - 1) -and -not $item.PSIsContainer) {
            throw "$Description contains a non-directory ancestor: $currentPath"
        }
    }
}

function Assert-OrdinaryDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    Assert-NoReparsePathComponents -Path $Path -Description $Description
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (-not $item.PSIsContainer) {
        throw "$Description is not a directory: $Path"
    }

    $attributes = [IO.FileAttributes] $item.Attributes
    if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description is a reparse point: $Path"
    }
}

function Assert-OrdinaryFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    Assert-NoReparsePathComponents -Path $Path -Description $Description
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.PSIsContainer) {
        throw "$Description is not an ordinary file: $Path"
    }

    $attributes = [IO.FileAttributes] $item.Attributes
    if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        ($attributes -band [IO.FileAttributes]::Device) -ne 0) {
        throw "$Description is not an ordinary file: $Path"
    }
}

function Resolve-ExistingDirectoryRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    Assert-OrdinaryDirectory -Path $fullPath -Description $Description
    return [IO.Path]::GetFullPath((Get-Item -LiteralPath $fullPath -Force).FullName)
}

function Resolve-WorktreeLocalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description,

        [switch] $Directory
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    Assert-NoReparsePathComponents -Path $fullPath -Description $Description
    if (-not (Test-PathEqualOrUnder -Path $fullPath -Root $repositoryRoot)) {
        throw "$Description must stay under the worktree: $fullPath"
    }

    $item = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
    if ($null -ne $item) {
        $attributes = [IO.FileAttributes] $item.Attributes
        if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description is a reparse point: $fullPath"
        }
        if ($Directory -and -not $item.PSIsContainer) {
            throw "$Description is not a directory: $fullPath"
        }
    }
    return $fullPath
}

function Assert-RootsDisjoint {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Left,

        [Parameter(Mandatory = $true)]
        [string] $Right
    )

    if ((Test-PathEqualOrUnder -Path $Left -Root $Right) -or
        (Test-PathEqualOrUnder -Path $Right -Root $Left)) {
        throw "Source and destination roots must be disjoint: $Left ; $Right"
    }
}

function Resolve-RelativeUnderRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,

        [Parameter(Mandatory = $true)]
        [string] $RelativePath,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
        [IO.Path]::IsPathRooted($RelativePath)) {
        throw "$Description must be a non-empty relative path: $RelativePath"
    }

    $relativeForWindows = $RelativePath.Replace('/', '\')
    $fullPath = [IO.Path]::GetFullPath((Join-Path $Root $relativeForWindows))
    if (-not (Test-PathEqualOrUnder -Path $fullPath -Root $Root)) {
        throw "$Description escapes its root: $RelativePath"
    }
    Assert-NoReparsePathComponents -Path $fullPath -Description $Description
    return $fullPath
}

function Test-LiveStateRelativePath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RelativePath
    )

    $normalized = $RelativePath.Replace('/', '\')
    $segments = @($normalized.Split('\', [StringSplitOptions]::RemoveEmptyEntries))
    $leaf = if ($segments.Count -eq 0) { '' } else { $segments[$segments.Count - 1] }

    if ($segments | Where-Object { $_ -match '^(?i:daemon)(?:-[0-9]+)?$' }) {
        return $true
    }
    if ($segments | Where-Object { $_ -match '^(?i:\.gradle)$' }) {
        if ($segments | Where-Object { $_ -match '^(?i:daemon|workers)$' }) {
            return $true
        }
    }
    if ($leaf -match '^(?i:registry\.bin(?:\.lock)?|.*\.lock|.*\.lck|.*\.pid)$') {
        return $true
    }
    return $leaf -match '^(?i:.*\.tmp)$'
}

function Get-SourceFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root
    )

    $files = [System.Collections.Generic.List[object]]::new()
    $directories = [System.Collections.Generic.Stack[string]]::new()
    $directories.Push($Root)

    while ($directories.Count -gt 0) {
        $directory = $directories.Pop()
        Assert-OrdinaryDirectory -Path $directory -Description 'Source directory'
        $entries = @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop | Sort-Object Name)
        foreach ($entry in $entries) {
            Assert-NoReparsePathComponents -Path $entry.FullName -Description 'Source entry'
            $relativePath = [IO.Path]::GetRelativePath($Root, $entry.FullName)
            if (Test-LiveStateRelativePath -RelativePath $relativePath) {
                continue
            }

            if ($entry.PSIsContainer) {
                Assert-OrdinaryDirectory -Path $entry.FullName -Description 'Source directory entry'
                $directories.Push($entry.FullName)
                continue
            }

            Assert-OrdinaryFile -Path $entry.FullName -Description 'Source file'
            $files.Add([pscustomobject]@{
                relativePath = $relativePath.Replace('/', '\')
                path = [IO.Path]::GetFullPath($entry.FullName)
            })
        }
    }

    return @($files | Sort-Object relativePath)
}

function Get-FileFingerprint {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    Assert-OrdinaryFile -Path $Path -Description $Description
    $before = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant()
    Assert-OrdinaryFile -Path $Path -Description $Description
    $after = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ([int64] $before.Length -ne [int64] $after.Length) {
        throw "$Description changed while it was read: $Path"
    }

    return [pscustomobject]@{
        size = [int64] $after.Length
        sha1 = $hash
    }
}

function Test-FingerprintMatches {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Actual,

        [Parameter(Mandatory = $true)]
        [long] $ExpectedSize,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha1
    )

    return [int64] $Actual.size -eq $ExpectedSize -and
        ([string] $Actual.sha1).Equals($ExpectedSha1, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-NoUriUserInfo {
    param(
        [Parameter(Mandatory = $true)]
        [Uri] $Uri,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    if (-not [string]::IsNullOrEmpty($Uri.UserInfo)) {
        throw "$Description may not contain URI credentials."
    }
}

function Assert-OfficialUri {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Value,

        [Parameter(Mandatory = $true)]
        [string] $Description,

        [Parameter(Mandatory = $true)]
        [ValidateSet('Index', 'Object')]
        [string] $Kind,

        [switch] $AllowLoopback
    )

    try {
        $uri = [Uri]::new($Value, [UriKind]::Absolute)
    } catch {
        throw "$Description is not an absolute URI."
    }

    Assert-NoUriUserInfo -Uri $uri -Description $Description

    $hostName = $uri.DnsSafeHost.ToLowerInvariant()
    $loopback = $AllowLoopback -and
        ($hostName -eq 'localhost' -or $hostName -eq '127.0.0.1' -or $hostName -eq '::1')
    if ($loopback) {
        if ($uri.Scheme -ne 'http') {
            throw "$Description loopback URI must use http."
        }
    } else {
        if ($uri.Scheme -ne 'https') {
            throw "$Description must use https."
        }
        if ($Kind -eq 'Index' -and $hostName -notin @('piston-meta.mojang.com', 'launchermeta.mojang.com')) {
            throw "$Description is not an official Mojang metadata URI."
        }
        if ($Kind -eq 'Object' -and $hostName -ne 'resources.download.minecraft.net') {
            throw "$Description is not the official Minecraft object host."
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($uri.Query) -or
        -not [string]::IsNullOrWhiteSpace($uri.Fragment)) {
        throw "$Description may not contain a query or fragment."
    }
    return $uri
}

function Read-AssetIndex {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedId,

        [Parameter(Mandatory = $true)]
        [long] $ExpectedSize,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha1
    )

    $fingerprint = Get-FileFingerprint -Path $Path -Description 'Asset index'
    if (-not (Test-FingerprintMatches `
            -Actual $fingerprint `
            -ExpectedSize $ExpectedSize `
            -ExpectedSha1 $ExpectedSha1)) {
        throw (
            "Asset index identity mismatch at $Path. " +
            "Expected size=$ExpectedSize sha1=$($ExpectedSha1.ToLowerInvariant()); " +
            "found size=$($fingerprint.size) sha1=$($fingerprint.sha1)."
        )
    }

    try {
        $index = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "Pinned asset index is not valid JSON: $Path"
    }
    if ($null -eq $index.objects) {
        throw "Pinned asset index has no objects map: $Path"
    }

    $assets = [System.Collections.Generic.List[object]]::new()
    foreach ($property in @($index.objects.PSObject.Properties | Sort-Object Name)) {
        $value = $property.Value
        if ($null -eq $value -or
            -not ($value.PSObject.Properties.Name -contains 'hash') -or
            -not ($value.PSObject.Properties.Name -contains 'size')) {
            throw "Pinned asset index has incomplete object metadata: $($property.Name)"
        }

        $hash = ([string] $value.hash).ToLowerInvariant()
        if ($hash -notmatch '^[0-9a-f]{40}$') {
            throw "Pinned asset index has an invalid object SHA-1: $($property.Name)"
        }
        try {
            $size = [int64] $value.size
        } catch {
            throw "Pinned asset index has an invalid object size: $($property.Name)"
        }
        if ($size -lt 0) {
            throw "Pinned asset index has a negative object size: $($property.Name)"
        }

        $assetPath = ([string] $property.Name).Replace('/', '\')
        if ([string]::IsNullOrWhiteSpace($assetPath) -or
            [IO.Path]::IsPathRooted($assetPath) -or
            $assetPath.Split('\', [StringSplitOptions]::RemoveEmptyEntries) -contains '..') {
            throw "Pinned asset index has an unsafe logical object path: $($property.Name)"
        }

        $assets.Add([pscustomobject]@{
            logicalPath = $assetPath
            hash = $hash
            size = $size
            relativePath = Join-Path (Join-Path (Join-Path 'assets' 'objects') $hash.Substring(0, 2)) $hash
        })
    }

    return [pscustomobject]@{
        identity = [pscustomobject]@{
            id = $ExpectedId
            size = $ExpectedSize
            sha1 = $ExpectedSha1.ToLowerInvariant()
        }
        objectCount = $assets.Count
        objects = @($assets | Sort-Object relativePath)
    }
}

function Ensure-SafeDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    $item = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) {
        $parent = Split-Path -Parent $fullPath
        if ([string]::IsNullOrWhiteSpace($parent)) {
            throw "$Description has no parent directory: $fullPath"
        }
        Ensure-SafeDirectory -Path $parent -Description "$Description parent" | Out-Null
        [IO.Directory]::CreateDirectory($fullPath) | Out-Null
    }

    Assert-OrdinaryDirectory -Path $fullPath -Description $Description
    return $fullPath
}

function Ensure-SafeParentDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $parent = Split-Path -Parent (Get-FullPathFromWorktree -Path $Path)
    Ensure-SafeDirectory -Path $parent -Description "$Description parent" | Out-Null
}

function Copy-VerifiedFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $SourcePath,

        [Parameter(Mandatory = $true)]
        [string] $DestinationPath,

        [Parameter(Mandatory = $true)]
        [long] $ExpectedSize,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha1,

        [Parameter(Mandatory = $true)]
        [string] $Description,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Work
    )

    $sourceFingerprint = Get-FileFingerprint -Path $SourcePath -Description "$Description source"
    if (-not (Test-FingerprintMatches `
            -Actual $sourceFingerprint `
            -ExpectedSize $ExpectedSize `
            -ExpectedSha1 $ExpectedSha1)) {
        throw "$Description source changed or does not match its planned identity: $SourcePath"
    }

    Assert-NoReparsePathComponents -Path $DestinationPath -Description "$Description destination"
    $destinationItem = Get-Item -LiteralPath $DestinationPath -Force -ErrorAction SilentlyContinue
    if ($null -ne $destinationItem) {
        if ($destinationItem.PSIsContainer) {
            throw "$Description destination is a directory: $DestinationPath"
        }
        $destinationFingerprint = Get-FileFingerprint -Path $DestinationPath -Description "$Description destination"
        if (Test-FingerprintMatches `
                -Actual $destinationFingerprint `
                -ExpectedSize $ExpectedSize `
                -ExpectedSha1 $ExpectedSha1) {
            return $false
        }
    }

    Ensure-SafeParentDirectory -Path $DestinationPath -Description $Description
    [IO.File]::Copy($SourcePath, $DestinationPath, $true)
    $copiedFingerprint = Get-FileFingerprint -Path $DestinationPath -Description "$Description destination after copy"
    if (-not (Test-FingerprintMatches `
            -Actual $copiedFingerprint `
            -ExpectedSize $ExpectedSize `
            -ExpectedSha1 $ExpectedSha1)) {
        throw "$Description destination failed post-copy verification: $DestinationPath"
    }

    $Work.copyOperations = [int] $Work.copyOperations + 1
    $Work.copyBytes = [int64] $Work.copyBytes + $ExpectedSize
    return $true
}

function Get-PowerShellExecutable {
    $candidate = if ($PSEdition -eq 'Core') {
        Join-Path $PSHOME 'pwsh.exe'
    } else {
        Join-Path $PSHOME 'powershell.exe'
    }
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        return [IO.Path]::GetFullPath($candidate)
    }

    $command = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        $command = Get-Command powershell.exe -ErrorAction SilentlyContinue
    }
    if ($null -eq $command) {
        throw 'Unable to locate a PowerShell executable for the owned downloader helper.'
    }
    return [IO.Path]::GetFullPath($command.Source)
}

function Add-ProcessArgument {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.ProcessStartInfo] $StartInfo,

        [Parameter(Mandatory = $true)]
        [string] $Value
    )

    if ($null -ne $StartInfo.ArgumentList) {
        [void] $StartInfo.ArgumentList.Add($Value)
        return
    }

    $escaped = $Value.Replace('\', '\\').Replace('"', '\"')
    $StartInfo.Arguments += ' "' + $escaped + '"'
}

function Stop-OwnedDownloadHelper {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.Process] $Process,

        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds,

        [Parameter(Mandatory = $true)]
        [hashtable] $State
    )

    if ($Process.HasExited) {
        $State.completed = $true
        $State.exitCode = [int] $Process.ExitCode
        return
    }

    $State.cleanupAttempted = $true
    $Process.Kill()
    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        $State.survived = $true
        throw "Owned downloader helper did not exit within the cleanup bound."
    }
    $State.terminated = $true
    $State.completed = $true
    $State.exitCode = [int] $Process.ExitCode
}

function Invoke-OwnedObjectDownload {
    param(
        [Parameter(Mandatory = $true)]
        [Uri] $Uri,

        [Parameter(Mandatory = $true)]
        [string] $OutputPath,

        [Parameter(Mandatory = $true)]
        [string] $ProgressPath,

        [Parameter(Mandatory = $true)]
        [int] $StallTimeout,

        [Parameter(Mandatory = $true)]
        [int] $MaxSeconds,

        [Parameter(Mandatory = $true)]
        [int] $CleanupTimeout,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Work,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[object]] $OwnedHelpers
    )

    Assert-NoUriUserInfo -Uri $Uri -Description 'Downloader URI'
    Ensure-SafeParentDirectory -Path $OutputPath -Description 'Downloader staging file'
    Assert-NoReparsePathComponents -Path $OutputPath -Description 'Downloader staging file'
    Assert-NoReparsePathComponents -Path $ProgressPath -Description 'Downloader progress file'
    if ($null -ne (Get-Item -LiteralPath $OutputPath -Force -ErrorAction SilentlyContinue)) {
        throw "Downloader staging file already exists: $OutputPath"
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = Get-PowerShellExecutable
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    Add-ProcessArgument -StartInfo $startInfo -Value '-NoProfile'
    Add-ProcessArgument -StartInfo $startInfo -Value '-NonInteractive'
    Add-ProcessArgument -StartInfo $startInfo -Value '-File'
    Add-ProcessArgument -StartInfo $startInfo -Value $scriptPath
    Add-ProcessArgument -StartInfo $startInfo -Value '-DownloadHelper'
    Add-ProcessArgument -StartInfo $startInfo -Value '-DownloadUri'
    Add-ProcessArgument -StartInfo $startInfo -Value $Uri.AbsoluteUri
    Add-ProcessArgument -StartInfo $startInfo -Value '-DownloadOutputPath'
    Add-ProcessArgument -StartInfo $startInfo -Value $OutputPath
    Add-ProcessArgument -StartInfo $startInfo -Value '-DownloadProgressPath'
    Add-ProcessArgument -StartInfo $startInfo -Value $ProgressPath

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw 'Unable to start the owned downloader helper.'
        }
    } catch {
        $process.Dispose()
        throw
    }

    $state = @{
        processType = 'owned-downloader-helper'
        cleanupAttempted = $false
        completed = $false
        terminated = $false
        survived = $false
        exitCode = $null
    }
    $OwnedHelpers.Add($state)
    $Work.networkRequests = [int] $Work.networkRequests + 1

    $started = [DateTime]::UtcNow
    $lastProgress = $started
    $lastBytes = 0L
    try {
        while (-not $process.HasExited) {
            $progressItem = Get-Item -LiteralPath $ProgressPath -Force -ErrorAction SilentlyContinue
            if ($null -ne $progressItem) {
                $progressText = Get-Content -LiteralPath $ProgressPath -Raw -ErrorAction SilentlyContinue
                $progressBytes = 0L
                if ([long]::TryParse(([string] $progressText).Trim(), [ref] $progressBytes)) {
                    if ($progressBytes -gt $lastBytes -or
                        $progressItem.LastWriteTimeUtc -gt $lastProgress) {
                        $lastBytes = $progressBytes
                        $lastProgress = $progressItem.LastWriteTimeUtc
                    }
                }
            }

            $now = [DateTime]::UtcNow
            if (($now - $lastProgress).TotalSeconds -gt $StallTimeout) {
                throw "Official object download stalled for more than $StallTimeout seconds: $($Uri.AbsoluteUri)"
            }
            if (($now - $started).TotalSeconds -gt $MaxSeconds) {
                throw "Official object download exceeded the $MaxSeconds second bound: $($Uri.AbsoluteUri)"
            }
            [void] $process.WaitForExit(100)
        }

        $process.WaitForExit()
        $state.completed = $true
        $state.exitCode = [int] $process.ExitCode
        if ($process.ExitCode -ne 0) {
            $helperError = $process.StandardError.ReadToEnd().Trim()
            if ([string]::IsNullOrWhiteSpace($helperError)) {
                $helperError = $process.StandardOutput.ReadToEnd().Trim()
            }
            throw "Owned downloader helper failed with exit code $($process.ExitCode): $($Uri.AbsoluteUri); $helperError"
        }
    } finally {
        if (-not $process.HasExited) {
            Stop-OwnedDownloadHelper `
                -Process $process `
                -TimeoutSeconds $CleanupTimeout `
                -State $state
        } elseif (-not $state.completed) {
            $state.completed = $true
            $state.exitCode = [int] $process.ExitCode
        }
        $process.Dispose()
    }
}

function Remove-OwnedStagingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $StagingRoot
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    if (-not (Test-PathEqualOrUnder -Path $fullPath -Root $StagingRoot)) {
        throw "Refusing to clean a path outside owned staging: $fullPath"
    }
    $item = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) {
        return
    }
    Assert-NoReparsePathComponents -Path $fullPath -Description 'Owned staging file'
    if ($item.PSIsContainer) {
        throw "Refusing to remove an owned staging directory as a file: $fullPath"
    }
    Remove-Item -LiteralPath $fullPath -Force -ErrorAction Stop
}

function Remove-OwnedStagingRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $StagingRoot
    )

    $fullPath = Get-FullPathFromWorktree -Path $Path
    $expectedRoot = Get-FullPathFromWorktree `
        -Path (Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging')
    $normalizedStagingRoot = Get-FullPathFromWorktree -Path $StagingRoot
    if (-not $fullPath.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not $fullPath.Equals($normalizedStagingRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the exact owned staging root: $fullPath"
    }

    $item = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) {
        return
    }
    Assert-NoReparsePathComponents -Path $fullPath -Description 'Owned staging root'
    if (-not $item.PSIsContainer) {
        throw "Refusing to remove the owned staging root because it is not a directory: $fullPath"
    }

    $entries = @(Get-ChildItem -LiteralPath $fullPath -Force -ErrorAction Stop)
    if ($entries.Count -ne 0) {
        throw "Refusing to remove the owned staging root because it is not empty: $fullPath"
    }
    Remove-Item -LiteralPath $fullPath -Force -ErrorAction Stop
}

function Invoke-DownloadHelper {
    Assert-NoReparsePathComponents -Path $DownloadOutputPath -Description 'Downloader output'
    Assert-NoReparsePathComponents -Path $DownloadProgressPath -Description 'Downloader progress'
    Ensure-SafeParentDirectory -Path $DownloadOutputPath -Description 'Downloader output'
    Ensure-SafeParentDirectory -Path $DownloadProgressPath -Description 'Downloader progress'

    try {
        $uri = [Uri]::new($DownloadUri, [UriKind]::Absolute)
    } catch {
        throw 'Downloader URI is not absolute.'
    }
    Assert-NoUriUserInfo -Uri $uri -Description 'Downloader URI'
    if ($uri.Scheme -notin @('http', 'https')) {
        throw "Downloader URI has unsupported scheme: $($uri.Scheme)"
    }
    if ($null -ne (Get-Item -LiteralPath $DownloadOutputPath -Force -ErrorAction SilentlyContinue)) {
        throw "Downloader output already exists: $DownloadOutputPath"
    }

    $progressText = '0'
    [IO.File]::WriteAllText($DownloadProgressPath, $progressText, [Text.Encoding]::ASCII)
    $client = [Net.Http.HttpClient]::new()
    $response = $null
    $stream = $null
    $output = $null
    try {
        $response = $client.GetAsync($uri, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Downloader received HTTP status $([int] $response.StatusCode)."
        }

        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $output = [IO.FileStream]::new(
            $DownloadOutputPath,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None)
        $buffer = [byte[]]::new(65536)
        $total = 0L
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $output.Write($buffer, 0, $read)
            $output.Flush()
            $total += $read
            [IO.File]::WriteAllText($DownloadProgressPath, ([string] $total), [Text.Encoding]::ASCII)
        }
        $output.Flush($true)
        [IO.File]::WriteAllText($DownloadProgressPath, ([string] $total), [Text.Encoding]::ASCII)
    } finally {
        if ($null -ne $output) {
            $output.Dispose()
        }
        if ($null -ne $stream) {
            $stream.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
        $client.Dispose()
    }
}

if ($DownloadHelper) {
    Invoke-DownloadHelper
    exit 0
}

$requestedReceiptPath = $ReceiptPath
$resolvedSourceRoot = $null
$resolvedDestinationRoot = $null
$resolvedReceiptPath = $null
$stagingRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build\verification\player-cache-preparation-staging'))
$stagingFiles = [System.Collections.Generic.List[string]]::new()
$ownedHelpers = [System.Collections.Generic.List[object]]::new()
$work = [ordered]@{
    sourceFileCount = 0
    regularFilesPlanned = 0
    regularFilesCopied = 0
    regularFilesAlreadyMatching = 0
    assetObjectsVerified = 0
    assetObjectsCopiedFromCache = 0
    assetObjectsFetched = 0
    copyOperations = 0
    copyBytes = 0L
    networkRequests = 0
    networkBytes = 0L
}
$failureMessage = $null
$status = 'failed'
$receipt = [ordered]@{
    schema = 'farmhelper.player-cache-preparation.receipt.v1'
    status = 'failed'
    sourceRoot = $null
    destinationRoot = $null
    relativePathBase = 'sourceRoot'
    assetIndex = [ordered]@{
        id = $AssetIndexId
        relativePath = $null
        url = $null
        size = $AssetIndexSize
        sha1 = if ($AssetIndexSha1) { $AssetIndexSha1.ToLowerInvariant() } else { $null }
        objectBaseUrl = $null
        objectCount = 0
        verifiedObjectCount = 0
    }
    work = $work
    progress = [ordered]@{
        stallTimeoutSeconds = $StallTimeoutSeconds
        maxDownloadSeconds = $MaxDownloadSeconds
        cleanupTimeoutSeconds = $CleanupTimeoutSeconds
        bounded = $true
    }
    cleanup = [ordered]@{
        ownedHelperProcessesStarted = 0
        ownedHelperProcessesCompleted = 0
        ownedHelperProcessesTerminated = 0
        ownedHelperProcessesSurvived = 0
        cleanupBounded = $true
        unrelatedProcessesTouched = $false
    }
    idempotence = [ordered]@{
        zeroNetworkAndCopyOnMatchingRerun = $false
    }
    error = $null
}

try {
    if ($MaxDownloadSeconds -lt $StallTimeoutSeconds) {
        throw 'MaxDownloadSeconds must be greater than or equal to StallTimeoutSeconds.'
    }

    $resolvedSourceRoot = Resolve-ExistingDirectoryRoot -Path $SourceRoot -Description 'Source root'
    $resolvedDestinationRoot = Resolve-WorktreeLocalPath `
        -Path $DestinationRoot `
        -Description 'Destination root' `
        -Directory
    Assert-NoReparsePathComponents -Path $resolvedSourceRoot -Description 'Source root'
    Assert-NoReparsePathComponents -Path $resolvedDestinationRoot -Description 'Destination root'
    Assert-RootsDisjoint -Left $resolvedSourceRoot -Right $resolvedDestinationRoot

    if ($null -eq $AssetIndexRelativePath -or [string]::IsNullOrWhiteSpace($AssetIndexRelativePath)) {
        $AssetIndexRelativePath = Join-Path (Join-Path 'assets' 'indexes') "$AssetIndexId.json"
    }
    $assetIndexSourcePath = Resolve-RelativeUnderRoot `
        -Root $resolvedSourceRoot `
        -RelativePath $AssetIndexRelativePath `
        -Description 'Source asset index path'
    $assetIndexDestinationPath = Resolve-RelativeUnderRoot `
        -Root $resolvedDestinationRoot `
        -RelativePath $AssetIndexRelativePath `
        -Description 'Destination asset index path'
    $receiptAssetIndexRelativePath = $AssetIndexRelativePath.Replace('/', '\')
    $receipt.assetIndex.relativePath = $receiptAssetIndexRelativePath

    $assetIndexUri = Assert-OfficialUri `
        -Value $AssetIndexUrl `
        -Description 'Asset index URL' `
        -Kind Index
    $objectBaseUri = Assert-OfficialUri `
        -Value $ObjectBaseUrl `
        -Description 'Object base URL' `
        -Kind Object `
        -AllowLoopback:$AllowLoopbackUris
    if (-not $objectBaseUri.AbsolutePath.EndsWith('/')) {
        $objectBaseUri = [Uri]::new($objectBaseUri.AbsoluteUri + '/')
    }
    if ($AllowLoopbackUris) {
        [void] (Assert-OfficialUri `
                -Value $AssetIndexUrl `
                -Description 'Asset index URL' `
                -Kind Index `
                -AllowLoopback:$AllowLoopbackUris)
    }
    $receipt.assetIndex.url = $assetIndexUri.AbsoluteUri
    $receipt.assetIndex.objectBaseUrl = $objectBaseUri.AbsoluteUri

    $sourceFiles = @(Get-SourceFiles -Root $resolvedSourceRoot)
    $work.sourceFileCount = $sourceFiles.Count
    $sourceIndexEntry = @($sourceFiles | Where-Object {
        ([string] $_.relativePath).Equals($receiptAssetIndexRelativePath, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($sourceIndexEntry.Count -ne 1) {
        throw "Source root must contain exactly one pinned asset index at $AssetIndexRelativePath."
    }

    $index = Read-AssetIndex `
        -Path $assetIndexSourcePath `
        -ExpectedId $AssetIndexId `
        -ExpectedSize $AssetIndexSize `
        -ExpectedSha1 $AssetIndexSha1
    $receipt.assetIndex.objectCount = $index.objectCount
    $receipt.assetIndex.verifiedObjectCount = $index.objectCount

    $destinationIndexItem = Get-Item -LiteralPath $assetIndexDestinationPath -Force -ErrorAction SilentlyContinue
    if ($null -ne $destinationIndexItem) {
        if ($destinationIndexItem.PSIsContainer) {
            throw "Destination asset index is a directory: $assetIndexDestinationPath"
        }
        $destinationIndexFingerprint = Get-FileFingerprint `
            -Path $assetIndexDestinationPath `
            -Description 'Destination asset index'
        if (-not (Test-FingerprintMatches `
                -Actual $destinationIndexFingerprint `
                -ExpectedSize $AssetIndexSize `
                -ExpectedSha1 $AssetIndexSha1)) {
            throw "Destination asset index does not match the pinned identity: $assetIndexDestinationPath"
        }
    }

    $assetObjectPlans = [System.Collections.Generic.List[object]]::new()
    foreach ($asset in $index.objects) {
        $sourceObjectPath = Resolve-RelativeUnderRoot `
            -Root $resolvedSourceRoot `
            -RelativePath $asset.relativePath `
            -Description "Source asset object $($asset.logicalPath)"
        $destinationObjectPath = Resolve-RelativeUnderRoot `
            -Root $resolvedDestinationRoot `
            -RelativePath $asset.relativePath `
            -Description "Destination asset object $($asset.logicalPath)"

        $sourceObjectItem = Get-Item -LiteralPath $sourceObjectPath -Force -ErrorAction SilentlyContinue
        $destinationObjectItem = Get-Item -LiteralPath $destinationObjectPath -Force -ErrorAction SilentlyContinue
        $sourceFingerprint = $null
        $destinationFingerprint = $null
        if ($null -ne $sourceObjectItem) {
            if ($sourceObjectItem.PSIsContainer) {
                throw "Source asset object is a directory: $sourceObjectPath"
            }
            $sourceFingerprint = Get-FileFingerprint `
                -Path $sourceObjectPath `
                -Description "Source asset object $($asset.logicalPath)"
            if (-not (Test-FingerprintMatches `
                    -Actual $sourceFingerprint `
                    -ExpectedSize $asset.size `
                    -ExpectedSha1 $asset.hash)) {
                throw (
                    "Source asset object mismatch for $($asset.logicalPath): " +
                    "expected size=$($asset.size) sha1=$($asset.hash); " +
                    "found size=$($sourceFingerprint.size) sha1=$($sourceFingerprint.sha1)."
                )
            }
        }
        if ($null -ne $destinationObjectItem) {
            if ($destinationObjectItem.PSIsContainer) {
                throw "Destination asset object is a directory: $destinationObjectPath"
            }
            $destinationFingerprint = Get-FileFingerprint `
                -Path $destinationObjectPath `
                -Description "Destination asset object $($asset.logicalPath)"
            if (-not (Test-FingerprintMatches `
                    -Actual $destinationFingerprint `
                    -ExpectedSize $asset.size `
                    -ExpectedSha1 $asset.hash)) {
                throw (
                    "Destination asset object mismatch for $($asset.logicalPath): " +
                    "expected size=$($asset.size) sha1=$($asset.hash); " +
                    "found size=$($destinationFingerprint.size) sha1=$($destinationFingerprint.sha1)."
                )
            }
        }

        $action = if ($null -ne $destinationObjectItem) {
            'already-matching'
        } elseif ($null -ne $sourceObjectItem) {
            'copy-cache'
        } else {
            'fetch-official'
        }
        $assetObjectPlans.Add([pscustomobject]@{
            logicalPath = $asset.logicalPath
            relativePath = $asset.relativePath
            hash = $asset.hash
            size = $asset.size
            sourcePath = $sourceObjectPath
            destinationPath = $destinationObjectPath
            action = $action
        })
        $work.assetObjectsVerified = [int] $work.assetObjectsVerified + 1
    }

    $regularPlans = [System.Collections.Generic.List[object]]::new()
    foreach ($sourceFile in $sourceFiles) {
        $relativePath = ([string] $sourceFile.relativePath).Replace('/', '\')
        if ($relativePath.Equals('assets\objects', [StringComparison]::OrdinalIgnoreCase) -or
            $relativePath.StartsWith('assets\objects\', [StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        $destinationPath = Resolve-RelativeUnderRoot `
            -Root $resolvedDestinationRoot `
            -RelativePath $relativePath `
            -Description "Destination file $relativePath"
        $fingerprint = Get-FileFingerprint `
            -Path ([string] $sourceFile.path) `
            -Description "Source file $relativePath"
        $destinationItem = Get-Item -LiteralPath $destinationPath -Force -ErrorAction SilentlyContinue
        $alreadyMatching = $false
        if ($null -ne $destinationItem) {
            if ($destinationItem.PSIsContainer) {
                throw "Destination file path is a directory: $destinationPath"
            }
            $destinationFingerprint = Get-FileFingerprint `
                -Path $destinationPath `
                -Description "Destination file $relativePath"
            $alreadyMatching = Test-FingerprintMatches `
                -Actual $destinationFingerprint `
                -ExpectedSize $fingerprint.size `
                -ExpectedSha1 $fingerprint.sha1
        }
        $regularPlans.Add([pscustomobject]@{
            relativePath = $relativePath
            sourcePath = [string] $sourceFile.path
            destinationPath = $destinationPath
            size = [int64] $fingerprint.size
            sha1 = [string] $fingerprint.sha1
            alreadyMatching = $alreadyMatching
        })
    }
    $work.regularFilesPlanned = $regularPlans.Count

    Ensure-SafeDirectory `
        -Path $stagingRoot `
        -Description 'Owned downloader staging root' | Out-Null
    Assert-RootsDisjoint -Left $resolvedSourceRoot -Right $stagingRoot
    Assert-RootsDisjoint -Left $resolvedDestinationRoot -Right $stagingRoot

    foreach ($plan in $assetObjectPlans | Where-Object action -eq 'fetch-official' | Sort-Object relativePath) {
        $objectUri = [Uri]::new($objectBaseUri, "$($plan.hash.Substring(0, 2))/$($plan.hash)")
        $stagePath = Join-Path $stagingRoot "$($plan.hash).part"
        $progressPath = Join-Path $stagingRoot "$($plan.hash).progress"
        $stagingFiles.Add($stagePath)
        $stagingFiles.Add($progressPath)
        Invoke-OwnedObjectDownload `
            -Uri $objectUri `
            -OutputPath $stagePath `
            -ProgressPath $progressPath `
            -StallTimeout $StallTimeoutSeconds `
            -MaxSeconds $MaxDownloadSeconds `
            -CleanupTimeout $CleanupTimeoutSeconds `
            -Work $work `
            -OwnedHelpers $ownedHelpers

        $stageFingerprint = Get-FileFingerprint `
            -Path $stagePath `
            -Description "Downloaded asset object $($plan.logicalPath)"
        if (-not (Test-FingerprintMatches `
                -Actual $stageFingerprint `
                -ExpectedSize $plan.size `
                -ExpectedSha1 $plan.hash)) {
            throw (
                "Downloaded asset object mismatch for $($plan.logicalPath): " +
                "expected size=$($plan.size) sha1=$($plan.hash); " +
                "found size=$($stageFingerprint.size) sha1=$($stageFingerprint.sha1)."
            )
        }
        Add-Member `
            -InputObject $plan `
            -MemberType NoteProperty `
            -Name stagingPath `
            -Value $stagePath
    }

    Ensure-SafeDirectory -Path $resolvedDestinationRoot -Description 'Destination root' | Out-Null
    foreach ($plan in $assetObjectPlans | Sort-Object relativePath) {
        if ($plan.action -eq 'copy-cache') {
            if (Copy-VerifiedFile `
                    -SourcePath $plan.sourcePath `
                    -DestinationPath $plan.destinationPath `
                    -ExpectedSize $plan.size `
                    -ExpectedSha1 $plan.hash `
                    -Description "Asset object $($plan.logicalPath)" `
                    -Work $work) {
                $work.assetObjectsCopiedFromCache = [int] $work.assetObjectsCopiedFromCache + 1
            }
        } elseif ($plan.action -eq 'fetch-official') {
            if (Copy-VerifiedFile `
                    -SourcePath $plan.stagingPath `
                    -DestinationPath $plan.destinationPath `
                    -ExpectedSize $plan.size `
                    -ExpectedSha1 $plan.hash `
                    -Description "Downloaded asset object $($plan.logicalPath)" `
                    -Work $work) {
                $work.assetObjectsFetched = [int] $work.assetObjectsFetched + 1
                $work.networkBytes = [int64] $work.networkBytes + [int64] $plan.size
            }
        }
    }

    foreach ($plan in $regularPlans | Sort-Object relativePath) {
        if (Copy-VerifiedFile `
                -SourcePath $plan.sourcePath `
                -DestinationPath $plan.destinationPath `
                -ExpectedSize $plan.size `
                -ExpectedSha1 $plan.sha1 `
                -Description "File $($plan.relativePath)" `
                -Work $work) {
            $work.regularFilesCopied = [int] $work.regularFilesCopied + 1
        } else {
            $work.regularFilesAlreadyMatching = [int] $work.regularFilesAlreadyMatching + 1
        }
    }

    $status = 'succeeded'
    $receipt.idempotence.zeroNetworkAndCopyOnMatchingRerun =
        [int] $work.networkRequests -eq 0 -and [int] $work.copyOperations -eq 0
} catch {
    $failureMessage = $_.Exception.Message
    $receipt.error = $failureMessage
    throw
} finally {
    foreach ($stagingFile in $stagingFiles) {
        try {
            Remove-OwnedStagingFile -Path $stagingFile -StagingRoot $stagingRoot
        } catch {
            if ($null -eq $failureMessage) {
                $failureMessage = $_.Exception.Message
                $receipt.error = $failureMessage
                $status = 'failed'
            }
        }
    }
    try {
        Remove-OwnedStagingRoot -Path $stagingRoot -StagingRoot $stagingRoot
    } catch {
        if ($null -eq $failureMessage) {
            $failureMessage = $_.Exception.Message
            $receipt.error = $failureMessage
            $status = 'failed'
        }
    }

    $receipt.status = $status
    $receipt.sourceRoot = $resolvedSourceRoot
    $receipt.destinationRoot = $resolvedDestinationRoot
    $receipt.cleanup.ownedHelperProcessesStarted = $ownedHelpers.Count
    $receipt.cleanup.ownedHelperProcessesCompleted = @($ownedHelpers | Where-Object completed).Count
    $receipt.cleanup.ownedHelperProcessesTerminated = @($ownedHelpers | Where-Object terminated).Count
    $receipt.cleanup.ownedHelperProcessesSurvived = @($ownedHelpers | Where-Object survived).Count
    if ($receipt.cleanup.ownedHelperProcessesSurvived -gt 0) {
        $receipt.cleanup.cleanupBounded = $false
    }
    if ($null -eq $requestedReceiptPath -or [string]::IsNullOrWhiteSpace($requestedReceiptPath)) {
        $requestedReceiptPath = Join-Path $repositoryRoot 'build\verification\player-cache-preparation-receipt.json'
    }
    try {
        $resolvedReceiptPath = Resolve-WorktreeLocalPath `
            -Path $requestedReceiptPath `
            -Description 'Receipt path'
        Ensure-SafeParentDirectory -Path $resolvedReceiptPath -Description 'Receipt file'
        [IO.File]::WriteAllText(
            $resolvedReceiptPath,
            ($receipt | ConvertTo-Json -Depth 20),
            [Text.UTF8Encoding]::new($false))
    } catch {
        if ($null -eq $failureMessage) {
            throw
        }
        Write-Error "Unable to write preparation receipt: $($_.Exception.Message)"
    }
}

if ($status -ne 'succeeded') {
    throw ($receipt.error ?? 'Player cache preparation failed.')
}

Write-Output ($receipt | ConvertTo-Json -Depth 20)
