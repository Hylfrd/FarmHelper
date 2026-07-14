/**
 * Minecraft-free immutable spatial observations and conservative tri-state queries.
 *
 * <p>Relative geometry, crops, collisions, rewarp points, and Garden plots were studied from
 * {@code src/main/java/com/jelly/farmhelperv2/util/BlockUtils.java}, {@code CropUtils.java},
 * {@code PlotUtils.java}, and {@code config/struct/Rewarp.java} at fixed upstream commit
 * {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}. Their non-trivial semantics are rewritten
 * for Fabric 26.1.2, immutable snapshots, bounded capture, and explicit unknown states.</p>
 */
package dev.hylfrd.farmhelper.runtime.spatial;
