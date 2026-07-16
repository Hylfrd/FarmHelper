# Minecraft collision-bound audit

FarmHelper's trusted local collision bounds are pinned to Minecraft 26.1.2's
offline common-deobfuscated JAR. Run the audit from the repository root with
PowerShell 7 and BellSoft JDK 25:

```powershell
$env:JAVA_HOME = 'C:\Program Files\BellSoft\LibericaJDK-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
pwsh -NoProfile -File scripts/internal/audit-minecraft-collision-bounds.ps1
```

The script performs no network access. It locates Loom's Minecraft metadata and
libraries beneath `GRADLE_USER_HOME` (or the current user's default `.gradle`
directory), verifies the full SHA-256 of the 26.1.2 common-deobfuscated JAR, and
runs a temporary Java source file against that exact 51-JAR offline classpath.
No generated source or binary is retained in the repository.

The Java audit invokes `PistonMovingBlockEntity.getExtendedProgress(float)` for
both extension directions across the valid progress interval and fails unless
translation is at most one block. It then bootstraps the Minecraft registry,
applies the movable-state restrictions represented by
`PistonBaseBlock.isPushable`, and enumerates every collision box. The pinned
result is 19,046 states, 43,820 boxes, zero errors, with base bounds X/Z `[0,1]`
and Y `[0,1.5]`. Four moving-piston oracles pin the resulting trusted extremes:
X/Z `[-1,2]` and Y `[-1,2.5]`. Every count, bound, oracle, runtime version, and
JAR hash is an assertion rather than informational output.

Expected JAR SHA-256:

```text
B61708F7D32C558419F7455F4A07D0F3659E8C5855AEFB96C4FAA5E567004141
```
