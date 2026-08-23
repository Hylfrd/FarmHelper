/**
 * Minecraft-free parsing of SkyBlock and Garden text snapshots.
 *
 * <p>The core state semantics were migrated from
 * {@code src/main/java/com/jelly/farmhelperv2/handler/GameStateHandler.java}. Identity-free chat
 * signals also preserve local recognition behavior from
 * {@code src/main/java/com/jelly/farmhelperv2/failsafe/impl/GuestVisitFailsafe.java},
 * {@code src/main/java/com/jelly/farmhelperv2/feature/impl/PestFarmer.java},
 * {@code src/main/java/com/jelly/farmhelperv2/feature/impl/AutoRepellent.java}, and
 * {@code src/main/java/com/jelly/farmhelperv2/failsafe/FailsafeManager.java}. All anchors refer to
 * upstream commit {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}. The code is rewritten for
 * Fabric around immutable snapshots and three-state observations; no event hooks, Minecraft
 * objects, stale state, notifications, or feature side effects are retained.</p>
 */
package dev.hylfrd.farmhelper.runtime.gamestate;
