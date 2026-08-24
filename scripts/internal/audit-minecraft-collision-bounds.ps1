[CmdletBinding()]
param(
    [string] $GradleUserHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$expectedMinecraftVersion = '26.1.2'
$expectedMetadataSha256 =
    '257A144E784CFF7FACBCC68B09D5D10C2BAC441565388E827B8BEBB2D4AE79D2'
$expectedLibrarySetSha256 =
    'BBAF6586E110F676A03300BD98C2412AD6F1D84044B590262C22D4818E720F44'
$expectedLibraryCount = 107
$expectedCommonJarSha256 =
    '2692634287E54AABD918F6E68E1877C9BB8E36D81C7B9FE10460FA3B24DA04CD'
$expectedCommonJarSize = 23536519
$expectedClasspathJarCount = 51
$expectedMetadataRelativePath = 'caches/fabric-loom/26.1.2/mojang_minecraft_info.json'
$expectedCommonJarRelativePath =
    'caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.1.2/' +
    'minecraft-common-deobf-26.1.2.jar'
$expectedLibraryCanonicalization =
    'ordinal-sort(name|downloads.artifact.path|downloads.artifact.sha1.lower|downloads.artifact.size)' +
    '+UTF-8+SHA-256+without-trailing-newline'

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

    $currentPath = $rootPath
    $rootItem = Get-Item -LiteralPath $rootPath -Force -ErrorAction Stop
    if (([IO.FileAttributes] $rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "${Description} contains a reparse-point component: $rootPath"
    }

    $relativePath = $fullPath.Substring($rootPath.Length)
    foreach ($component in $relativePath.Split('\', [StringSplitOptions]::RemoveEmptyEntries)) {
        $currentPath = Join-Path $currentPath $component
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) {
            break
        }
        if (([IO.FileAttributes] $item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "${Description} contains a reparse-point component: $currentPath"
        }
    }
}

function Get-CanonicalPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    Assert-NoReparsePathComponents -Path $Path -Description $Description
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (([IO.FileAttributes] $item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "${Description} is a reparse point: $Path"
    }
    return [IO.Path]::GetFullPath($item.FullName)
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

    Assert-NoReparsePathComponents -Path $Root -Description "${Description} root"
    Assert-NoReparsePathComponents -Path $Path -Description $Description
    $normalizedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $normalizedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (-not $normalizedPath.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
        -not $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Description} must remain under ${normalizedRoot}: $normalizedPath"
    }
}

function Get-LibrarySetFingerprint {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Metadata
    )

    $records = [System.Collections.Generic.List[string]]::new()
    foreach ($library in @($Metadata.libraries)) {
        if (-not ($library.PSObject.Properties.Name -contains 'downloads') -or
            $null -eq $library.downloads.artifact) {
            throw "Minecraft library '$($library.name)' has no artifact metadata."
        }
        $artifact = $library.downloads.artifact
        $name = [string] $library.name
        $path = [string] $artifact.path
        $sha1 = ([string] $artifact.sha1).ToLowerInvariant()
        $size = [int64] $artifact.size
        if ([string]::IsNullOrWhiteSpace($name) -or
            [string]::IsNullOrWhiteSpace($path) -or
            $sha1 -notmatch '^[0-9a-f]{40}$' -or
            $size -lt 0) {
            throw "Invalid pinned metadata for Minecraft library '$name'."
        }
        $records.Add(('{0}|{1}|{2}|{3}' -f $name, $path, $sha1, $size))
    }

    if ($records.Count -ne $expectedLibraryCount) {
        throw "Unexpected Minecraft metadata library count: $($records.Count), expected $expectedLibraryCount."
    }

    $sortedRecords = $records.ToArray()
    [Array]::Sort($sortedRecords, [StringComparer]::Ordinal)
    $canonical = [string]::Join("`n", $sortedRecords)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString(
            $algorithm.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($canonical)))
    } finally {
        $algorithm.Dispose()
    }
}

$provenancePath = Join-Path $repositoryRoot 'dependency-provenance.json'
Assert-NoReparsePathComponents -Path $repositoryRoot -Description 'Repository root'
Assert-NoReparsePathComponents -Path $provenancePath -Description 'Dependency provenance'
if (-not (Test-Path -LiteralPath $provenancePath -PathType Leaf)) {
    throw "Dependency provenance is missing: $provenancePath"
}
$repositoryProvenance = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
$collisionPin = $repositoryProvenance.minecraftCollisionAudit
if ($null -eq $collisionPin -or
    [int] $collisionPin.schemaVersion -ne 1 -or
    [string] $collisionPin.minecraftVersion -ne $expectedMinecraftVersion -or
    [string] $collisionPin.metadataRelativePath -ne $expectedMetadataRelativePath -or
    [string] $collisionPin.metadataSha256 -ne $expectedMetadataSha256 -or
    [string] $collisionPin.librarySetSha256 -ne $expectedLibrarySetSha256 -or
    [int] $collisionPin.libraryCount -ne $expectedLibraryCount -or
    [int] $collisionPin.classpathJarCount -ne $expectedClasspathJarCount -or
    [string] $collisionPin.commonJarRelativePath -ne $expectedCommonJarRelativePath -or
    [string] $collisionPin.commonJarSha256 -ne $expectedCommonJarSha256 -or
    [int64] $collisionPin.commonJarSize -ne $expectedCommonJarSize -or
    [string] $collisionPin.libraryCanonicalization -ne $expectedLibraryCanonicalization) {
    throw 'Repository Minecraft collision provenance pin is missing, stale, or inconsistent.'
}

$javaProperties = (& java -XshowSettings:properties -version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the active Java runtime.'
}
if ($javaProperties -notmatch '(?m)^\s*java\.vendor\s*=\s*BellSoft\s*$') {
    throw 'This audit requires the BellSoft Java runtime.'
}
if ($javaProperties -notmatch '(?m)^\s*java\.version\s*=\s*25(?:\.|\s|$)') {
    throw 'This audit requires JDK 25.'
}

$gradleHomeInput = if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome
} elseif (-not [string]::IsNullOrWhiteSpace([string] $env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME
} else {
    Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
}
$gradleHome = Get-CanonicalPath -Path $gradleHomeInput -Description 'Gradle user home'
if (-not (Test-Path -LiteralPath $gradleHome -PathType Container)) {
    throw "Gradle user home is not a directory: $gradleHome"
}
$cache = Join-Path $gradleHome 'caches'
$modules = Join-Path $cache 'modules-2\files-2.1'
$loom = Join-Path $cache 'fabric-loom'
Assert-NoReparsePathComponents -Path $cache -Description 'Gradle cache'
Assert-NoReparsePathComponents -Path $modules -Description 'Gradle library cache'
Assert-NoReparsePathComponents -Path $loom -Description 'Loom cache'
$infoPath = Join-Path $loom "$expectedMinecraftVersion\mojang_minecraft_info.json"
$commonJar = Join-Path $loom (
    'minecraftMaven\net\minecraft\minecraft-common-deobf\' +
    "$expectedMinecraftVersion\minecraft-common-deobf-$expectedMinecraftVersion.jar"
)

Assert-NoReparsePathComponents -Path $infoPath -Description 'Minecraft metadata'
if (-not (Test-Path -LiteralPath $infoPath -PathType Leaf)) {
    throw "Required offline Minecraft metadata is missing: $infoPath"
}
Assert-NoReparsePathComponents -Path $commonJar -Description 'Minecraft common-deobf JAR'
if (-not (Test-Path -LiteralPath $commonJar -PathType Leaf)) {
    throw "Required offline Minecraft common-deobf JAR is missing: $commonJar"
}

$infoPath = Get-CanonicalPath -Path $infoPath -Description 'Minecraft metadata'
$commonJar = Get-CanonicalPath -Path $commonJar -Description 'Minecraft common-deobf JAR'

$actualCommonJarSha256 =
    (Get-FileHash -LiteralPath $commonJar -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualCommonJarSha256 -ne $expectedCommonJarSha256 -or
    (Get-Item -LiteralPath $commonJar).Length -ne $expectedCommonJarSize) {
    throw (
        'Unexpected Minecraft common-deobf JAR SHA-256. ' +
        "Expected $expectedCommonJarSha256 and size $expectedCommonJarSize, " +
        "found $actualCommonJarSha256 and size $((Get-Item -LiteralPath $commonJar).Length)."
    )
}

$actualMetadataSha256 =
    (Get-FileHash -LiteralPath $infoPath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualMetadataSha256 -ne $expectedMetadataSha256) {
    throw (
        'Unexpected Minecraft metadata SHA-256. ' +
        "Expected $expectedMetadataSha256, found $actualMetadataSha256."
    )
}

$info = Get-Content -LiteralPath $infoPath -Raw | ConvertFrom-Json
$actualLibrarySetSha256 = Get-LibrarySetFingerprint -Metadata $info
if ($actualLibrarySetSha256 -ne $expectedLibrarySetSha256) {
    throw (
        'Unexpected Minecraft library-set SHA-256. ' +
        "Expected $expectedLibrarySetSha256, found $actualLibrarySetSha256."
    )
}
$jars = [System.Collections.Generic.List[string]]::new()
$jars.Add($commonJar)

function Test-CurrentLibraryRules {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Library
    )

    if (-not ($Library.PSObject.Properties.Name -contains 'rules')) {
        return $true
    }

    $currentOs = if ($IsWindows) {
        'windows'
    } elseif ($IsLinux) {
        'linux'
    } elseif ($IsMacOS) {
        'osx'
    } else {
        throw 'Unable to determine the current operating system for library rules.'
    }

    $allowed = $false
    foreach ($rule in $Library.rules) {
        $matches = $true
        if ($null -ne $rule.os -and $null -ne $rule.os.name) {
            $matches = $rule.os.name -eq $currentOs
        }

        if (-not $matches) {
            continue
        }

        if ($rule.action -eq 'allow') {
            $allowed = $true
        } elseif ($rule.action -eq 'disallow') {
            $allowed = $false
        } else {
            throw "Unsupported Minecraft library rule action '$($rule.action)'."
        }
    }

    return $allowed
}

function Get-LibraryPathInfo {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Library
    )

    $parts = [string] $Library.name -split ':'
    if ($parts.Count -lt 3 -or $parts.Count -gt 4 -or
        $parts[0] -notmatch '^[A-Za-z0-9_.+~-]+$' -or
        $parts[1] -notmatch '^[A-Za-z0-9_.+~-]+$' -or
        $parts[2] -notmatch '^[A-Za-z0-9_.+~-]+$' -or
        ($parts.Count -eq 4 -and $parts[3] -notmatch '^[A-Za-z0-9_.+~-]+$')) {
        throw "Unsafe Minecraft library coordinate: $($Library.name)"
    }

    if (-not ($Library.PSObject.Properties.Name -contains 'downloads') -or
        $null -eq $Library.downloads.artifact) {
        throw "Minecraft library '$($Library.name)' has no artifact checksum metadata."
    }

    $artifactMetadata = $Library.downloads.artifact
    $artifactRelativePath = [string] $artifactMetadata.path
    $expectedSha1 = ([string] $artifactMetadata.sha1).ToLowerInvariant()
    $expectedSize = [int64] $artifactMetadata.size
    if ([string]::IsNullOrWhiteSpace($artifactRelativePath) -or
        [IO.Path]::IsPathRooted($artifactRelativePath) -or
        $artifactRelativePath -match '(^|[\\/])\.\.([\\/]|$)' -or
        $expectedSha1 -notmatch '^[0-9a-f]{40}$' -or
        $expectedSize -lt 0) {
        throw "Invalid checksum metadata for Minecraft library '$($Library.name)'."
    }

    $artifactRelativePath = $artifactRelativePath.Replace('/', '\')
    $directory = Join-Path $modules ($parts[0] + '\' + $parts[1] + '\' + $parts[2])
    $expectedArtifactPath = Join-Path $modules $artifactRelativePath
    Assert-PathUnder `
        -Path $directory `
        -Root $modules `
        -Description "Minecraft library directory '$($Library.name)'"
    Assert-PathUnder `
        -Path $expectedArtifactPath `
        -Root $modules `
        -Description "Minecraft library artifact '$($Library.name)'"
    return [pscustomobject]@{
        directory = $directory
        expectedArtifactPath = $expectedArtifactPath
        expectedFileName = Split-Path -Leaf $artifactRelativePath
        expectedSha1 = $expectedSha1
        expectedSize = $expectedSize
    }
}

function Get-SafeLibraryFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Directory,

        [Parameter(Mandatory = $true)]
        [string] $FileName
    )

    Assert-NoReparsePathComponents -Path $Directory -Description 'Minecraft library directory'
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return @()
    }

    $pending = [System.Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($Directory)
    $files = [System.Collections.Generic.List[string]]::new()
    while ($pending.Count -gt 0) {
        $current = $pending.Dequeue()
        foreach ($entry in @(Get-ChildItem -LiteralPath $current -Force -ErrorAction Stop)) {
            Assert-NoReparsePathComponents -Path $entry.FullName -Description 'Minecraft library cache entry'
            if ($entry.PSIsContainer) {
                $pending.Enqueue($entry.FullName)
            } elseif ($entry.Name -eq $FileName) {
                $files.Add((Get-CanonicalPath -Path $entry.FullName -Description 'Minecraft library JAR'))
            }
        }
    }
    return @($files.ToArray())
}

function Resolve-OfflineLibraryArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [object] $PathInfo,

        [Parameter(Mandatory = $true)]
        [object] $Library
    )

    $directory = [string] $PathInfo.directory
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        return $null
    }

    $candidates = @(Get-SafeLibraryFiles `
        -Directory $directory `
        -FileName ([string] $PathInfo.expectedFileName))
    if ($candidates.Count -eq 0) {
        return $null
    }

    $candidates = @(
        $candidates | ForEach-Object {
            Get-Item -LiteralPath $_ -Force -ErrorAction Stop
        }
    )

    $matchingCandidates = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    foreach ($candidate in $candidates) {
        $canonicalCandidate = Get-CanonicalPath `
            -Path $candidate.FullName `
            -Description 'Minecraft library JAR'
        if ((Get-Item -LiteralPath $canonicalCandidate).Length -ne [int64] $PathInfo.expectedSize) {
            continue
        }

        $actualSha1 =
            (Get-FileHash -LiteralPath $canonicalCandidate -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($actualSha1 -eq [string] $PathInfo.expectedSha1) {
            $matchingCandidates.Add((Get-Item -LiteralPath $canonicalCandidate -Force))
        }
    }

    if ($matchingCandidates.Count -eq 0) {
        $candidatePaths = $candidates.FullName -join ', '
        throw (
            "No checksum-matching artifact for Minecraft library '$($Library.name)'. " +
            "Expected SHA-1 $($PathInfo.expectedSha1) and size $($PathInfo.expectedSize); " +
            "candidates: $candidatePaths."
        )
    }
    if ($matchingCandidates.Count -gt 1) {
        $matchingPaths = $matchingCandidates.FullName -join ', '
        throw (
            "Ambiguous checksum-matching artifacts for Minecraft library '$($Library.name)': " +
            $matchingPaths
        )
    }

    return $matchingCandidates[0].FullName
}

foreach ($library in $info.libraries) {
    $pathInfo = Get-LibraryPathInfo -Library $library
    $parts = [string] $library.name -split ':'
    if ($parts.Count -ne 3) {
        continue
    }

    if (-not (Test-CurrentLibraryRules -Library $library)) {
        continue
    }

    $artifactPath = Resolve-OfflineLibraryArtifact `
        -PathInfo $pathInfo `
        -Library $library
    if ($null -ne $artifactPath) {
        if ($jars.Contains($artifactPath)) {
            throw "Duplicate offline Minecraft classpath artifact: $artifactPath"
        }
        $jars.Add($artifactPath)
    }
}

if ($jars.Count -ne $expectedClasspathJarCount) {
    throw (
        "Unexpected offline Minecraft audit classpath: $($jars.Count) JARs, " +
        "expected $expectedClasspathJarCount."
    )
}

$classPath = $jars -join [IO.Path]::PathSeparator
$sourcePath = Join-Path (
    [IO.Path]::GetTempPath()
) ("farmhelper-collision-audit-{0}.java" -f [Guid]::NewGuid().ToString('N'))

$program = @'
import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;

class FarmHelperCollisionBoundsAudit {
    private static final int EXPECTED_STATES = 19_046;
    private static final int EXPECTED_BOXES = 43_820;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        auditPistonTranslation();
        auditMovableStateBounds();
        auditMovingShapeOracles();
        System.out.println("AUDIT_OK");
    }

    private static void auditPistonTranslation() throws Exception {
        Method getExtendedProgress = PistonMovingBlockEntity.class
                .getDeclaredMethod("getExtendedProgress", float.class);
        getExtendedProgress.setAccessible(true);

        PistonMovingBlockEntity extending = movingEntity(
                Blocks.STONE.defaultBlockState(), Direction.EAST, true);
        PistonMovingBlockEntity retracting = movingEntity(
                Blocks.STONE.defaultBlockState(), Direction.EAST, false);
        float[] validProgress = {0.0F, 0.25F, 0.5F, 0.75F, 1.0F};

        float minExtending = Float.POSITIVE_INFINITY;
        float maxExtending = Float.NEGATIVE_INFINITY;
        float minRetracting = Float.POSITIVE_INFINITY;
        float maxRetracting = Float.NEGATIVE_INFINITY;
        float maxAbsoluteTranslation = 0.0F;
        for (float progress : validProgress) {
            float extendingTranslation =
                    (float) getExtendedProgress.invoke(extending, progress);
            float retractingTranslation =
                    (float) getExtendedProgress.invoke(retracting, progress);

            require(
                    Float.compare(extendingTranslation, progress - 1.0F) == 0,
                    "Unexpected extending piston translation at progress " + progress);
            require(
                    Float.compare(retractingTranslation, 1.0F - progress) == 0,
                    "Unexpected retracting piston translation at progress " + progress);

            minExtending = Math.min(minExtending, extendingTranslation);
            maxExtending = Math.max(maxExtending, extendingTranslation);
            minRetracting = Math.min(minRetracting, retractingTranslation);
            maxRetracting = Math.max(maxRetracting, retractingTranslation);
            maxAbsoluteTranslation = Math.max(
                    maxAbsoluteTranslation,
                    Math.max(Math.abs(extendingTranslation), Math.abs(retractingTranslation)));
        }

        require(maxAbsoluteTranslation <= 1.0F, "Piston translation exceeded one block");
        System.out.printf(
                Locale.ROOT,
                "TRANSLATION progress=[0.000000,1.000000] "
                        + "extending=[%.6f,%.6f] retracting=[%.6f,%.6f] max_abs=%.6f%n",
                minExtending,
                maxExtending,
                minRetracting,
                maxRetracting,
                maxAbsoluteTranslation);
    }

    private static void auditMovableStateBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        int states = 0;
        int boxes = 0;
        int errors = 0;

        for (var block : BuiltInRegistries.BLOCK) {
            for (var state : block.getStateDefinition().getPossibleStates()) {
                try {
                    PushReaction reaction = state.getPistonPushReaction();
                    boolean movableReaction =
                            reaction == PushReaction.NORMAL
                                    || reaction == PushReaction.PUSH_ONLY
                                    || reaction == PushReaction.IGNORE;
                    float hardness = state.getDestroySpeed(
                            EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    boolean movable =
                            movableReaction
                                    && !state.hasBlockEntity()
                                    && hardness != -1.0F
                                    && !state.is(Blocks.OBSIDIAN)
                                    && !state.is(Blocks.CRYING_OBSIDIAN)
                                    && !state.is(Blocks.RESPAWN_ANCHOR)
                                    && !state.is(Blocks.REINFORCED_DEEPSLATE)
                                    && (!(state.is(Blocks.PISTON)
                                                    || state.is(Blocks.STICKY_PISTON))
                                            || !state.getValue(PistonBaseBlock.EXTENDED));
                    if (!movable) {
                        continue;
                    }

                    states++;
                    for (AABB box : state.getCollisionShape(
                            EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
                        boxes++;
                        minX = Math.min(minX, box.minX);
                        minY = Math.min(minY, box.minY);
                        minZ = Math.min(minZ, box.minZ);
                        maxX = Math.max(maxX, box.maxX);
                        maxY = Math.max(maxY, box.maxY);
                        maxZ = Math.max(maxZ, box.maxZ);
                    }
                } catch (Throwable throwable) {
                    errors++;
                }
            }
        }

        require(states == EXPECTED_STATES, "Unexpected movable-state count: " + states);
        require(boxes == EXPECTED_BOXES, "Unexpected collision-box count: " + boxes);
        require(errors == 0, "Movable-state enumeration errors: " + errors);
        requireBounds(minX, maxX, minY, maxY, minZ, maxZ, 0.0, 1.0, 0.0, 1.5, 0.0, 1.0);
        System.out.printf(
                Locale.ROOT,
                "SUMMARY states=%d boxes=%d errors=%d "
                        + "base=[%.6f,%.6f]x[%.6f,%.6f]x[%.6f,%.6f]%n",
                states, boxes, errors, minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static void auditMovingShapeOracles() {
        BlockState westConnected = Blocks.OAK_FENCE.defaultBlockState()
                .setValue(CrossCollisionBlock.WEST, true);
        BlockState eastConnected = Blocks.OAK_FENCE.defaultBlockState()
                .setValue(CrossCollisionBlock.EAST, true);

        auditMovingShape(
                Direction.DOWN,
                Blocks.OAK_FENCE.defaultBlockState(),
                0.375, 0.625, 1.0, 2.5, 0.375, 0.625);
        auditMovingShape(
                Direction.UP,
                Blocks.OAK_FENCE.defaultBlockState(),
                0.375, 0.625, -1.0, 0.5, 0.375, 0.625);
        auditMovingShape(
                Direction.EAST,
                westConnected,
                -1.0, -0.375, 0.0, 1.5, 0.375, 0.625);
        auditMovingShape(
                Direction.WEST,
                eastConnected,
                1.375, 2.0, 0.0, 1.5, 0.375, 0.625);
    }

    private static void auditMovingShape(
            Direction direction,
            BlockState state,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ) {
        AABB actual = movingEntity(state, direction, true)
                .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                .bounds();
        requireBounds(
                actual.minX,
                actual.maxX,
                actual.minY,
                actual.maxY,
                actual.minZ,
                actual.maxZ,
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ);
        System.out.println("ORACLE " + direction.name().toLowerCase(Locale.ROOT) + " " + actual);
    }

    private static PistonMovingBlockEntity movingEntity(
            BlockState state, Direction direction, boolean extending) {
        return new PistonMovingBlockEntity(
                BlockPos.ZERO,
                Blocks.MOVING_PISTON.defaultBlockState(),
                state,
                direction,
                extending,
                false);
    }

    private static void requireBounds(
            double actualMinX,
            double actualMaxX,
            double actualMinY,
            double actualMaxY,
            double actualMinZ,
            double actualMaxZ,
            double expectedMinX,
            double expectedMaxX,
            double expectedMinY,
            double expectedMaxY,
            double expectedMinZ,
            double expectedMaxZ) {
        require(Double.compare(actualMinX, expectedMinX) == 0, "Unexpected minX: " + actualMinX);
        require(Double.compare(actualMaxX, expectedMaxX) == 0, "Unexpected maxX: " + actualMaxX);
        require(Double.compare(actualMinY, expectedMinY) == 0, "Unexpected minY: " + actualMinY);
        require(Double.compare(actualMaxY, expectedMaxY) == 0, "Unexpected maxY: " + actualMaxY);
        require(Double.compare(actualMinZ, expectedMinZ) == 0, "Unexpected minZ: " + actualMinZ);
        require(Double.compare(actualMaxZ, expectedMaxZ) == 0, "Unexpected maxZ: " + actualMaxZ);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
'@

try {
    Set-Content -LiteralPath $sourcePath -Value $program -Encoding utf8
    $auditOutput = @(& java --class-path $classPath $sourcePath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $auditOutput | Write-Output
        throw "Minecraft collision-bound audit failed with exit code $LASTEXITCODE."
    }

    Write-Output "JDK=BellSoft 25"
    Write-Output "MINECRAFT=$expectedMinecraftVersion"
    Write-Output "CLASSPATH_JARS=$($jars.Count)"
    Write-Output "COMMON_JAR_SHA256=$actualCommonJarSha256"
    Write-Output "METADATA_SHA256=$actualMetadataSha256"
    Write-Output "LIBRARY_SET_SHA256=$actualLibrarySetSha256"
    $auditOutput | Write-Output
} finally {
    if (Test-Path -LiteralPath $sourcePath) {
        Remove-Item -LiteralPath $sourcePath -Force
    }
}
