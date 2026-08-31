/**
 * Privacy-safe, local usage accounting with no client, storage, network, or identity boundary.
 *
 * <p>Upstream anchors are fixed release {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}, the
 * tracker introduction in {@code 66d58c1499f755d270d59ccea1de926b8119dd3e}, and the usage HUD
 * behavior finalized through {@code 2f0143c5a717cc178ba9d87ec58f448e368496a}. The applicable
 * local behavior is macro-active elapsed time with 24-hour, 7-day, 30-day, and lifetime views.
 * This package independently expresses that behavior through explicit monotonic tick evidence.</p>
 *
 * <p>The upstream {@code UsageStatsTracker} also depended on {@code BanInfoWS}, wall-clock dates,
 * gzip JSON files named with a player UUID, and fields such as {@code id}, {@code modVersion},
 * {@code fastBreak}, and {@code timestamp}. Those identity-bearing persistence details are
 * intentionally removed. The deletion authority is the upstream-parity matrix's BanInfoWS and
 * analytics-removal entries: no BanInfoWS, analytics upload, WebSocket transport, remote command,
 * telemetry, settings, or client wiring is part of this domain.</p>
 */
package dev.hylfrd.farmhelper.feature.usage;
