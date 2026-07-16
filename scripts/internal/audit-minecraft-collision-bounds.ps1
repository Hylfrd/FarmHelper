[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$expectedMinecraftVersion = '26.1.2'
$expectedCommonJarSha256 =
    'B61708F7D32C558419F7455F4A07D0F3659E8C5855AEFB96C4FAA5E567004141'

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

$gradleHome = if ($env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME
} else {
    Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
}
$cache = Join-Path $gradleHome 'caches'
$modules = Join-Path $cache 'modules-2\files-2.1'
$loom = Join-Path $cache 'fabric-loom'
$infoPath = Join-Path $loom "$expectedMinecraftVersion\mojang_minecraft_info.json"
$commonJar = Join-Path $loom (
    'minecraftMaven\net\minecraft\minecraft-common-deobf\' +
    "$expectedMinecraftVersion\minecraft-common-deobf-$expectedMinecraftVersion.jar"
)

foreach ($requiredPath in @($infoPath, $commonJar)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required offline Minecraft artifact is missing: $requiredPath"
    }
}

$actualCommonJarSha256 =
    (Get-FileHash -LiteralPath $commonJar -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualCommonJarSha256 -ne $expectedCommonJarSha256) {
    throw (
        'Unexpected Minecraft common-deobf JAR SHA-256. ' +
        "Expected $expectedCommonJarSha256, found $actualCommonJarSha256."
    )
}

$info = Get-Content -LiteralPath $infoPath -Raw | ConvertFrom-Json
$jars = [System.Collections.Generic.List[string]]::new()
$jars.Add($commonJar)

foreach ($library in $info.libraries) {
    $parts = $library.name -split ':'
    if ($parts.Count -ne 3) {
        continue
    }

    $directory = Join-Path $modules ($parts[0] + '\' + $parts[1] + '\' + $parts[2])
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        continue
    }

    $artifact = Get-ChildItem `
        -LiteralPath $directory `
        -Recurse `
        -File `
        -Filter "$($parts[1])-$($parts[2]).jar" |
        Sort-Object -Property FullName |
        Select-Object -First 1

    if ($null -ne $artifact) {
        $jars.Add($artifact.FullName)
    }
}

if ($jars.Count -ne 51) {
    throw "Unexpected offline Minecraft audit classpath: $($jars.Count) JARs, expected 51."
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
    $auditOutput | Write-Output
} finally {
    if (Test-Path -LiteralPath $sourcePath) {
        Remove-Item -LiteralPath $sourcePath -Force
    }
}
