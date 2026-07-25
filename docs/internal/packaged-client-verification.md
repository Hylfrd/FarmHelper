# Packaged client verification

`verifyPackagedClient` is the repository-owned launch task for checking the
actual distributable FarmHelper JAR. It uses Loom's
`ClientProductionRunTask`, not `runClient`: the production task launches
`net.fabricmc.loader.impl.launch.knot.KnotClient` with the Minecraft client
classpath and passes the supplied JAR through Fabric Loader's
`fabric.addMods` property.

## Reproducible offline run

Build the deterministic candidate and record its SHA-256:

```powershell
.\gradlew.bat --offline --no-daemon clean jar
$sha256 = (Get-FileHash .\build\libs\FarmHelper-26.1.2.jar -Algorithm SHA256).Hash
```

Run the verifier with that exact hash:

```powershell
.\scripts\internal\verify-packaged-client.ps1 -ExpectedSha256 $sha256
```

The script rejects a missing, wrong, or ambiguous hash match; a candidate
outside `build\libs`; a malformed or non-FarmHelper JAR; an existing run
directory; and any entry in the isolated `mods` directory. It checks the
Minecraft asset index and every indexed asset path in the local Loom cache
before launch. Gradle and Loom are invoked with `--offline`, so an incomplete
cache fails instead of attempting a download.

The run directory is a new directory below
`build\verification\packaged-client`. It is the Minecraft `gameDir`, and the
script does not copy accounts, servers, worlds, or user runtime configuration.
Its `mods` directory must remain empty. FarmHelper is supplied as exactly one
candidate `fabric.addMods` path, and the declared required platform dependency
is supplied as the only other explicit production mod path:
`net.fabricmc.fabric-api:fabric-api:0.153.0+26.1.2`, SHA-256
`2A604CCC66C1294F860ACB8D0763C8887E927B3ED34AA262AC79E26D8626B94C`.
The verifier requires that dependency to be present exactly once in the local
Gradle cache and to agree with `dependency-provenance.json`; no arbitrary
mods are copied or discovered. The client is started without GUI input. After
both proof signals are durable, the script terminates only the owned
KnotClient descendant and records that controlled termination.

## Machine-verifiable proof

`evidence.json` records the candidate path, size, SHA-256, the allowlisted
dependency coordinate/path/size/SHA-256, Minecraft/Loader versions, isolated
paths, production task type, and the exact process command line. Two
independent runtime signals are required:

1. `logs\latest.log` contains `FarmHelper client initialized.` from the real
   client entrypoint.
2. Java class-load logging records
   `dev.hylfrd.farmhelper.client.FarmHelperClient` with a `file:` source that
   resolves to the candidate path exactly.

The script hashes the candidate and the approved dependency again after
launch. The combination of exactly one FarmHelper candidate among the
effective production mod paths, the allowlisted dependency, the exact class
source, the entrypoint marker, and unchanged pre/post SHA-256 is the
artifact-to-loaded-code proof.

Use `-PreflightOnly -RunDirectory <existing-fixture>` to exercise the guarded
selection checks without starting Minecraft. The focused negative harness is:

```powershell
.\scripts\internal\test-packaged-client-harness.ps1
```

For a reproducibility/content audit, preserve one JAR from the first offline
clean build and compare it with the second build:

```powershell
.\scripts\internal\audit-packaged-jar.ps1 -FirstArtifact <first-jar-path>
```

The audit compares full SHA-256, size, ZIP entry metadata, required metadata,
license/notice entries, and forbidden archive content.

## Deliberate modern packaging behavior

Minecraft `26.1.2` is a non-obfuscated Loom environment in this project.
Loom therefore does not register `remapJar`; the deterministic `jar` task's
`FarmHelper-26.1.2.jar` is already the production artifact. The verifier does
not add runtime hooks, alter FarmHelper code, or invent gameplay behavior.
It only uses the upstream Loom production launcher and Fabric Loader's normal
mod discovery path.
