# Module boundaries

This document fixes the top-level ownership and dependency direction established by S1-T1. It describes structure only; later tasks fill the currently empty modules.

## Source-set rule

- `src/main` contains version-independent state, contracts, pure behavior, and the generic Brigadier command tree plus the server-safe Fabric main entry adapter under `platform`.
- `src/client` contains Fabric registration and every direct Minecraft client, Screen, input, rendering, or HUD dependency.
- Platform adapters may depend on common modules. Common modules must not depend on `platform` or `dev.hylfrd.farmhelper.client`.

## Runtime ownership

`FarmHelperClient` is the client composition root. It creates exactly one `FarmHelperClientRuntime` for the client session and passes that instance to Fabric adapters.

`FarmHelperClientRuntime` owns:

- one common `FarmHelperRuntime`;
- one `ClientInputController`;
- one `ClientRotationController`;
- one `InventoryController` backed by `MinecraftInventoryPort` and the managed hotbar adapter;
- one deterministic client lifecycle and cancellation fanout;
- one on-demand loaded-chunk spatial capture port.

The client composition root also registers one `FarmHelperSettingsController`. It owns the native
settings key mapping and opens a new per-screen draft session; it does not own configuration state.

`FarmHelperRuntime` owns:

- one `FarmHelperConfig`;
- one `MacroManager`;
- one owner-cancellable `ClientTaskQueue`;
- one ticketed `NavigationController` and its bounded segmented spatial contract;
- one bounded typed expected motion/rotation/teleport ledger shared with later Failsafes;
- one `GameStateParser` and its current immutable parse result.

Navigation exposes `ControlOwner` plus an exact positive generation/world-epoch ticket. Internal
task identities are derived from that ticket. A terminal navigation boundary clears its exact task,
input, rotation, and expectation ownership; global client cancellation also clears navigation and
the expectation ledger independently so one failing participant cannot skip either release.

Long logical captures use ordered immutable `SpatialSnapshot` segments. Individual segments retain
the 8,192-cell and 256-block-axis limits. A body/collision query is valid only when one segment,
including its bounded seam halo, wholly contains the query; unknown, missing, unloaded, conflicting,
fluid, or collision-error evidence is never traversable.

The ordered client tick captures lifecycle and immutable client/text snapshots, publishes the
current parse result, advances due tasks, delivers runtime behavior, advances rotation, and finally
enforces input safety. Due stateful tasks therefore never observe the previous tick's GameState.

Commands and tick callbacks borrow these services through constructor or registration parameters. They do not own mutable runtime state. There is no mutable service locator.

## Logical modules

| Module | Source set | Responsibility | Must not own |
| --- | --- | --- | --- |
| `platform` | main + client | Fabric lifecycle, mod metadata, and Minecraft-to-domain adaptation | Domain policy |
| `runtime` | main + client composition | Service lifetime and explicit ownership | Presentation behavior |
| `control` | main contracts/state + client adapters | Managed input, rotation, inventory, and later path execution control | Macro decisions |
| `macro` | main | Macro lifecycle and crop state machines | Minecraft client objects |
| `feature` | main | Independently switchable automation features | Platform access |
| `failsafe` | main | Detection, priority, and reaction state machines | Rendering or Screen state |
| `navigation` | main | Path contracts, search, and execution policy | Direct Minecraft access |
| `config` | main | Typed configuration model, validation, and version-independent storage | Screen or Minecraft client state |
| `ui` | main settings/command models + client adapters | Generic command parsing, typed settings catalog/drafts, Fabric feedback adaptation, and native Screen presentation | Runtime ownership or business logic |
| `hud` | client | Read-only HUD rendering and view models | Business-state mutation |
| `utility` | main | Small stateless helpers with no higher-level ownership | Managers or mutable global state |

Empty modules are deliberate boundaries, not implemented product behavior.

Settings controls mutate only a `SettingsDraft` copy. Saving crosses the injected validated
`SettingsSaveService` boundary, which delegates to `FarmHelperClientRuntime` for atomic persistence
and rollback. Minecraft `Screen`, widgets, key mappings, rendering, and tooltips remain in
`src/client`; search, category filtering, validation, reset, dirty tracking, and scrolling state are
testable in `src/main` without launching Minecraft.
