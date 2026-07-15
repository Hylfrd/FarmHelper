package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.platform.mixin.PlayerTabOverlayAccessor;
import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import dev.hylfrd.farmhelper.runtime.gamestate.PlayerFacts;
import dev.hylfrd.farmhelper.runtime.gamestate.RawChatMessage;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.WorldTransition;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, collection-only adapter for parser inputs. No Minecraft object escapes this class. */
public final class MinecraftGameTextSnapshotSource implements ClientGameTextSource {
    private final Minecraft client;
    private final ArrayDeque<RawChatMessage> chat = new ArrayDeque<>();
    private long nextChatSequence;
    private long generation;
    private boolean chatOverflow;
    private boolean worldObserved;
    private Observation<?> previousWorld = Observation.unknown();

    public MinecraftGameTextSnapshotSource(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void acceptChat(String channel, String text) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(text, "text");
        if (!client.isSameThread() || nextChatSequence == Long.MAX_VALUE
                || chat.size() >= GameTextInputBudget.MAX_CHAT_MESSAGES) {
            chatOverflow = true;
            return;
        }
        chat.addLast(new RawChatMessage(nextChatSequence++, channel, text));
    }

    @Override
    public RawGameTextSnapshot snapshot(ClientSnapshot clientSnapshot) {
        Objects.requireNonNull(clientSnapshot, "clientSnapshot");
        long currentGeneration = generation;
        if (generation != Long.MAX_VALUE) {
            generation++;
        }
        if (!client.isSameThread()) {
            return RawGameTextSnapshot.unknown(currentGeneration);
        }
        try {
            return new RawGameTextSnapshot(
                    scoreboardTitle(), scoreboardLines(), tabLines(), tabFooter(), vacuumLore(),
                    drainChat(), playerFacts(), worldTransition(clientSnapshot), currentGeneration);
        } catch (RuntimeException exception) {
            chat.clear();
            chatOverflow = false;
            return RawGameTextSnapshot.unknown(currentGeneration);
        }
    }

    private Observation<String> scoreboardTitle() {
        if (client.level == null) {
            return Observation.absent();
        }
        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective == null
                ? Observation.absent()
                : Observation.present(objective.getDisplayName().getString());
    }

    private Observation<List<String>> scoreboardLines() {
        if (client.level == null) {
            return Observation.absent();
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return Observation.present(List.of());
        }
        List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
                .filter(entry -> !entry.isHidden())
                .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed()
                        .thenComparing(PlayerScoreEntry::owner))
                .limit(GameTextInputBudget.MAX_SCOREBOARD_LINES + 1L)
                .toList();
        if (entries.size() > GameTextInputBudget.MAX_SCOREBOARD_LINES) {
            return Observation.unknown();
        }
        return Observation.present(entries.stream()
                .map(entry -> entry.ownerName().getString())
                .toList());
    }

    private Observation<List<String>> tabLines() {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return Observation.absent();
        }
        List<String> lines = new ArrayList<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (lines.size() == GameTextInputBudget.MAX_TAB_LINES) {
                return Observation.unknown();
            }
            lines.add(client.gui.getTabList().getNameForDisplay(info).getString());
        }
        return Observation.present(lines);
    }

    private Observation<List<String>> tabFooter() {
        if (client.getConnection() == null) {
            return Observation.absent();
        }
        Component footer = ((PlayerTabOverlayAccessor) client.gui.getTabList())
                .farmhelper$getFooter();
        if (footer == null) {
            return Observation.present(List.of());
        }
        List<String> lines = footer.getString().lines()
                .limit(GameTextInputBudget.MAX_TAB_FOOTER_LINES + 1L)
                .toList();
        return lines.size() > GameTextInputBudget.MAX_TAB_FOOTER_LINES
                ? Observation.unknown()
                : Observation.present(lines);
    }

    private Observation<List<String>> vacuumLore() {
        if (client.player == null) {
            return Observation.absent();
        }
        ItemStack item = client.player.getMainHandItem();
        if (item.isEmpty()) {
            return Observation.absent();
        }
        ItemLore lore = item.get(DataComponents.LORE);
        if (lore == null) {
            return Observation.present(List.of());
        }
        if (lore.lines().size() > GameTextInputBudget.MAX_VACUUM_LORE_LINES) {
            return Observation.unknown();
        }
        return Observation.present(lore.lines().stream().map(Component::getString).toList());
    }

    private Observation<List<RawChatMessage>> drainChat() {
        if (chatOverflow) {
            chat.clear();
            chatOverflow = false;
            return Observation.unknown();
        }
        List<RawChatMessage> drained = List.copyOf(chat);
        chat.clear();
        return Observation.present(drained);
    }

    private PlayerFacts playerFacts() {
        if (client.player == null) {
            return PlayerFacts.unknown();
        }
        return new PlayerFacts(
                Observation.present(client.player.getInventory().isEmpty()),
                Observation.present(client.player.experienceLevel),
                Observation.present((double) client.player.getAbilities().getWalkingSpeed()));
    }

    private Observation<WorldTransition> worldTransition(ClientSnapshot snapshot) {
        Observation<?> current = snapshot.world();
        WorldTransition transition = worldObserved && !previousWorld.equals(current)
                ? WorldTransition.CHANGING
                : WorldTransition.STABLE;
        worldObserved = true;
        previousWorld = current;
        return Observation.present(transition);
    }
}
