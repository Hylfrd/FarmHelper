/**
 * Pure Jacob contest threshold evidence and decision domain.
 *
 * <p>The observation boundary accepts already-cleaned scoreboard lines and emits immutable,
 * privacy-safe evidence. The decision boundary consumes only that evidence and an explicit
 * threshold table; it has no Minecraft, client, chat, scoreboard, UI, configuration, or remote
 * control dependency.
 *
 * <p>Fixed upstream fidelity anchors are from commit
 * {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}:
 * {@code com.jelly.farmhelperv2.handler.GameStateHandler#checkJacob(String)} uses the text
 * anchors {@code jacob's contest}, {@code Collected}, the medal {@code with} lines, and
 * {@code jacobsRemainingTimePattern}; {@code
 * com.jelly.farmhelperv2.failsafe.impl.JacobFailsafe#onTickDetection(ClientTickEvent)} maps the
 * crop caps and triggers when the count is greater than or equal to the selected cap. The first
 * class is server-specific observation; the second comparison is the pure decision rule kept
 * here.
 */
package dev.hylfrd.farmhelper.feature.jacob;
