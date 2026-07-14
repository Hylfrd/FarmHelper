/**
 * Unwired Fabric client capture for the Minecraft-free spatial runtime.
 *
 * <p>Collision behavior was studied from fixed upstream
 * {@code src/main/java/com/jelly/farmhelperv2/util/BlockUtils.java} at commit
 * {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a} and rewritten around modern BlockState,
 * player-sensitive VoxelShape collision, immutable snapshots, and no-load chunk access.</p>
 */
package dev.hylfrd.farmhelper.client.platform.spatial;
