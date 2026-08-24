# Player cache preparation

`prepare-player-cache.ps1` prepares a worktree-local Fabric Loom cache for a
future Player attempt. It is a standalone provisioning step and does not start
Minecraft, inspect a GUI, or modify the active Player cache.

## Root contract

`SourceRoot` is the directory whose contents are copied. For a Loom cache it is
the exact `caches/fabric-loom` directory, not its `caches` parent. `DestinationRoot`
is the exact target `caches/fabric-loom` directory and must be below this
worktree. Every source and destination path is made absolute before use.

The relative path for every ordinary source file is calculated as:

```text
relative = GetRelativePath(SourceRoot, sourceFile)
destinationFile = DestinationRoot + relative
```

This keeps a pre-existing destination from producing a nested
`fabric-loom/fabric-loom` directory or a suffix staging directory. Source and
destination roots must be disjoint. Existing unrelated destination files and
asset objects are never removed.

Official-object downloads use the exact worktree-local staging root
`build/verification/player-cache-preparation-staging`. The tool removes only
the owned `.part` and `.progress` files it created, then removes that exact
staging root only after verifying that it is an ordinary empty directory. An
unexpected entry is preserved and causes the run to fail; cleanup never
recursively broadens beyond the owned files and root.

All existing components of a root, copied file, read file, and destination
parent are checked for `ReparsePoint` before use. Source and destination
directories, asset objects, indexes, and staging files must be ordinary files
or directories. Junctions, symbolic links, device files, and other reparse
paths are rejected; the recursive source walk does not follow them. The tool
never deletes or moves source content.

Known live state is excluded from the source plan: lock, LCK, PID, and temporary
files, plus daemon and worker state directories. The exclusion is reported by
absence from the copy plan; it does not remove the source state.

## Pinned asset contract

The caller supplies all of these values explicitly:

- `AssetIndexId`: an identifier safe for one index filename component.
- `AssetIndexRelativePath`: the index path below both roots. If omitted, it is
  `assets/indexes/<AssetIndexId>.json`.
- `AssetIndexSize` and `AssetIndexSha1`: the expected byte size and SHA-1 of the
  official index file.
- `AssetIndexUrl`: an HTTPS URL on `piston-meta.mojang.com` or
  `launchermeta.mojang.com`.
- `ObjectBaseUrl`: an HTTPS URL on `resources.download.minecraft.net`, normally
  `https://resources.download.minecraft.net/`.

Both official URLs must have empty URI user information. Credentials are
rejected before any helper process or network request starts. URL validation
errors and failure receipts do not echo the supplied URI, and receipt URL
fields remain empty until the URI has passed validation.

The source index must match the pinned size and SHA-1 before any destination
write. An existing destination index must match as well. Every object entry in
the pinned index is checked for a 40-character SHA-1 and non-negative byte size.
For each required object:

1. A present source object must match the pinned size and SHA-1.
2. A present destination object must match the pinned size and SHA-1.
3. A matching source object may be copied to a missing destination object.
4. Only an object missing from both roots is fetched from the official object
   URL `<ObjectBaseUrl>/<first-two-hash-characters>/<hash>`.
5. A fetched object is size- and SHA-1-verified before it is placed.

Files below `assets/objects` that are not required by the pinned index are not
copied. Unrelated objects already in the destination remain untouched.

`-AllowLoopbackUris` exists only for the synthetic suite's loopback HTTP
fixture. It is not part of an official provisioning invocation.

## Progress and receipt

Missing-object downloads run in one owned PowerShell helper process at a time.
The parent enforces both a maximum stall interval and a total download bound.
On failure it terminates only the exact helper `Process` instance it started,
waits for the configured cleanup bound, and records whether that helper
survived. It never searches for or terminates unrelated processes.

The default receipt is:

```text
build/verification/player-cache-preparation-receipt.json
```

Use `-ReceiptPath` to select another worktree-local receipt file. The receipt
schema is `farmhelper.player-cache-preparation.receipt.v1` and has these stable
sections:

- `sourceRoot`, `destinationRoot`, and `relativePathBase` record the resolved
  roots and the exact relative-path rule.
- `assetIndex` records the pinned identity, URLs, and verified object count.
- `work` records source files, copy operations/bytes, cached-object copies,
  fetched objects, and network requests/bytes.
- `progress` records all bounded timeout values.
- `cleanup` records owned helper start/completion/termination/survival counts,
  bounded cleanup, and the fixed false value for unrelated process control.
- `idempotence.zeroNetworkAndCopyOnMatchingRerun` is true only for a run that
  performed zero network requests and zero copy operations.

Successful matching reruns do not create staging files, start helpers, fetch
objects, or copy files. Receipt output contains no timestamps or process IDs,
so equivalent runs produce deterministic JSON. The synthetic no-mutation audit
compares a deterministic snapshot of the complete destination tree, including
ordinary files, directories, empty directories, entry types, numeric file
attributes, and SHA-1/size fingerprints. Reparse entries are recorded by the
audit and must remain zero; the preparation tool rejects them before use.

This tool deliberately does not invoke Loom's `downloadAssets` task. With Loom
1.17.17, `DownloadAssetsTask.getAssetIndex` calls `downloadString` even when a
correct pinned index is already present, so `downloadAssets --offline` is not a
valid idempotence gate and is expected to fail in an offline environment. The
preparation gate is the official-index plus full object size/SHA-1 audit above;
the synthetic suite repeats the operation and compares the complete destination
tree for no mutation.

Future Player readiness should continue in this order:

1. Run this preparation step with the official pinned index and object identity.
2. Run the full asset index/object size and SHA-1 audit, then repeat it as a
   complete-tree no-mutation audit with zero network and zero copy work.
3. Use an isolated worktree-local Gradle home for offline `runClient` or the
   repository's offline configuration/readiness check. Do not substitute
   `downloadAssets --offline` for that readiness check.

Online fetching is limited to the initial missing official objects. A matching
rerun never performs network access, and a mismatched cached index or object is
rejected rather than replaced automatically.

The focused synthetic suite is:

```powershell
.\scripts\internal\test-player-cache-preparation.ps1
```

It uses temporary fixtures and worktree-local destinations only. It does not
read the user cache, require credentials, launch Minecraft, or touch active
Player evidence.
