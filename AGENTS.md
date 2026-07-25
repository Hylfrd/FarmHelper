# FarmHelper Repository Instructions

## Authority and startup

- `progress.md` is the authoritative changing project state. Read it completely before project work when it exists in the current workspace.
- This file contains stable rules only. Do not copy changing commits, task IDs, CI runs or candidate status here.
- A session is the development Main only when Apostrophe explicitly designates it as Main. Designer, audit or one-off sessions must not silently activate the development team.
- If `progress.md` is absent from a managed worktree, use the exact facts in the dispatch prompt and return a compact progress delta to the parent. Do not guess current state.
- Preserve concurrent user work. Unexpected changes are not permission to revert, reset, clean or overwrite them.

## Product mission

Port `JellyLabScripts/FarmHelper` from Minecraft 1.8.9 Forge to Minecraft 26.1.2 Fabric on Java 25 while preserving observable FarmHelper behavior.

The fixed behavioral reference is upstream commit:

    eacb323fbde3eff94d4f2ee7baacb059d84b8e3a

Core behavior must be derived from upstream evidence, not invented. Preserve applicable state transitions, priorities, timing constants, bounded random ranges and RNG draw order, input ownership, navigation, AntiStuck, recovery, cancellation and all retained Failsafe behavior.

A necessary modern difference must document:

1. upstream class/method and old behavior;
2. why the old mechanism cannot be used on Fabric/Minecraft 26.1.2;
3. the new observable behavior;
4. focused regression evidence;
5. independent review disposition.

Missing or ambiguous upstream evidence is a blocker, not permission to design a substitute.

Never restore telemetry, analytics uploads, remote control, Discord/JDA/Webhook commands, updater, Proxy UI/control, production DevAuth, OneConfig or Forge/LaunchWrapper infrastructure.

## Session architecture

- Project execution uses separate sidebar-visible Codex tasks, not hidden internal `spawn_agent` or collaboration children.
- Main and deputy coordinators use `gpt-5.6-sol` with `xhigh` reasoning.
- Bounded implementation, investigation, review, automated testing and GUI work use `gpt-5.6-luna` with `max` reasoning.
- Sol sessions coordinate, assign ownership, resolve conflicts, consolidate evidence and wait. Delegate routine execution to Luna whenever possible.
- There is no arbitrary task-count cap and no serial-creation rule. Parallelize non-overlapping work as far as real dependencies, compute resources, file ownership and GUI ownership allow.
- A Luna task owns one bounded work unit. Archive it after its handoff is recorded. New work receives a new task; do not keep permanent A/B/C workers alive by repeatedly changing their duties.
- Only one session may own `master`, a specific integration branch, a shared physical worktree or the GUI lease at a time.

## Required independent roles

Every runtime-affecting candidate uses three distinct Luna max tasks on the same immutable commit/tree:

- **Builder:** writes the bounded change, cites upstream evidence, runs focused and broad automated gates, and commits one candidate.
- **Fidelity Reviewer:** did not author the candidate; independently compares target behavior with upstream and reports ordered findings before approval.
- **Player:** did not author or review the candidate; runs the exact candidate with `runClient`, observes Minecraft through Computer Use and executes the relevant manual cases.

Reviewer and Player may run in parallel after the immutable candidate is automated-green. Any code change invalidates earlier Reviewer and affected Player evidence. Remediation is a new bounded Builder task, followed by fresh independent gates.

At project startup, Main creates a dedicated Sol xhigh Review Deputy. That deputy uses multiple Luna max review tasks to audit the already integrated P0-P3 code and recovered P3/P4 work against upstream. Review tasks are initially read-only and must not quietly repair findings.

Main also creates a dedicated Sol xhigh Test Deputy. The Test Deputy owns the GUI test queue, assigns bounded campaigns to Luna max Players, schedules the single GUI lease, receives their reports and turns credible failures into defect/retest packages. Main or the responsible Implementation Deputy assigns each defect package to a new Luna max Builder.

A Player campaign may test several closely related cases on one exact commit/tree. It tests and reports only; it does not edit code or perform fidelity review. Archive it when the campaign is complete, and create a new Player for the next baseline, candidate or retest campaign.

## Computer Use and Player evidence

- Real `runClient` plus real Computer Use is required for client/runtime candidates. Logs, window titles, browser automation and automated tests do not substitute for visible interaction.
- Observe a fresh Minecraft window state before each click or key sequence. Never perform blind desktop input.
- Serialize GUI work through one lease. Verify the exact commit/tree before launch and close only the test-owned process chain afterward.
- Record relevant `test.md` cases as `PASS`, `FAIL`, `BLOCKED` or `NOT RUN`, with screenshots/video when possible, logs and SHA-256, observed command feedback, exact PIDs and zero-owned-process cleanup.
- If Computer Use is absent or capture is unsafe, stop GUI input and mark the Player gate blocked. Do not claim acceptance from logs.
- Player reports go to the Test Deputy. Each failure report includes the exact SHA/tree, reproduction steps, expected and actual behavior, evidence, severity and affected surface. The Test Deputy sends a bounded defect package to Main/Implementation Deputy for Builder assignment, then schedules a fresh Player retest on the fix candidate.

## Git, review and CI closure

- Do not integrate a candidate until Builder, Fidelity Reviewer and Player gates resolve for the same commit/tree. A precisely justified Player `N/A` is allowed only for truly non-runtime work; packaging is not automatically exempt.
- Use coherent candidate commits and preserve unique interrupted work under named refs/bundles before risky integration.
- The assigned Integration/CI Deputy alone owns integration and push operations for its branch.
- After push, monitor every required GitHub Actions run to a terminal result and record pushed SHA, run IDs/URLs and conclusions in `progress.md`.
- A green local build, a commit or a successful push without terminal CI is not completion.
- Never force-push, create a Release or restore removed product surfaces without Apostrophe's explicit instruction.
- Never commit or push `progress.md`, runtime accounts/configuration, logs, screenshots, server details, tokens, credentials or other private data.

## Progress maintenance

- Main remains accountable for continuous `progress.md` maintenance and may assign exactly one Sol progress editor.
- Update it when tasks start/end, ownership changes, candidates or blockers change, review/Player verdicts arrive, integration occurs, a commit is pushed or CI reaches a terminal result.
- Replace stale facts instead of appending an unbounded diary.
- Every execution handoff reports exact base/candidate/tree, owned surface, upstream evidence, tests or GUI cases, findings, blockers and a compact progress delta.
