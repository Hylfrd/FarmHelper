/**
 * Minecraft-free parsing of SkyBlock and Garden text snapshots.
 *
 * <p>The parsing semantics were migrated from
 * {@code src/main/java/com/jelly/farmhelperv2/handler/GameStateHandler.java} at upstream commit
 * {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}. They are rewritten for Fabric around
 * immutable snapshots and three-state observations; no Forge events, Minecraft objects, stale
 * state, notifications, or feature side effects are retained.</p>
 */
package dev.hylfrd.farmhelper.runtime.gamestate;
