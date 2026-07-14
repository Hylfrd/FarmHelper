/**
 * Minecraft-free inventory observation and guarded transaction control.
 *
 * <p>Recognition behavior is migrated from upstream
 * {@code src/main/java/com/jelly/farmhelperv2/util/InventoryUtils.java} at fixed commit
 * {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}. Its direct Forge-era window clicks are
 * intentionally rewritten for Fabric, immutable snapshots, revision rebasing, and fail-closed
 * client-thread validation.</p>
 */
package dev.hylfrd.farmhelper.control.inventory;
