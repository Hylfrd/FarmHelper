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
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, collection-only adapter for parser inputs. No Minecraft object escapes this class. */
public final class MinecraftGameTextSnapshotSource implements ClientGameTextSource {
    private static final int VANILLA_RENDERED_TAB_LIMIT = 80;
    private static final Comparator<PlayerScoreEntry> SCOREBOARD_RENDER_ORDER = Comparator
            .comparingInt(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);
    private static final Comparator<PlayerInfo> TAB_RENDER_ORDER = Comparator
            .comparingInt((PlayerInfo info) -> -info.getTabListOrder())
            .thenComparingInt(info -> info.getGameMode() == GameType.SPECTATOR ? 1 : 0)
            .thenComparing(info -> info.getTeam() == null ? "" : info.getTeam().getName())
            .thenComparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER);

    private final Minecraft client;
    private final BoundedChatBuffer chat = new BoundedChatBuffer();
    private final WorldTransitionTracker worldTransitions = new WorldTransitionTracker();
    private long generation;

    public MinecraftGameTextSnapshotSource(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void acceptChat(String channel, String text) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(text, "text");
        if (!client.isSameThread()) {
            chat.overflow();
            return;
        }
        chat.accept(channel, text);
    }

    @Override
    public void resetChat() {
        chat.reset();
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
            chat.reset();
            return RawGameTextSnapshot.unknown(currentGeneration);
        }
    }

    private Observation<String> scoreboardTitle() {
        if (client.level == null) {
            return Observation.absent();
        }
        Objective objective = visibleSidebarObjective(client.level.getScoreboard(), localPlayerName());
        return objective == null
                ? Observation.absent()
                : bounded(objective.getDisplayName());
    }

    private Observation<List<String>> scoreboardLines() {
        if (client.level == null) {
            return Observation.absent();
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = visibleSidebarObjective(scoreboard, localPlayerName());
        if (objective == null) {
            return Observation.present(List.of());
        }
        Observation<List<PlayerScoreEntry>> visible = visibleScoreboardEntries(
                scoreboard.listPlayerScores(objective));
        if (!visible.isPresent()) {
            return Observation.unknown();
        }
        List<PlayerScoreEntry> entries = visible.get();
        List<String> lines = new ArrayList<>(entries.size());
        for (PlayerScoreEntry entry : entries) {
            Observation<String> line = visibleScoreboardLine(scoreboard, entry);
            if (!line.isPresent()) {
                return Observation.unknown();
            }
            lines.add(line.get());
        }
        return Observation.present(lines);
    }

    private Observation<List<String>> tabLines() {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return Observation.absent();
        }
        if (connection.getListedOnlinePlayers().size() > GameTextInputBudget.MAX_TAB_LINES) {
            return Observation.unknown();
        }
        List<PlayerInfo> rendered = connection.getListedOnlinePlayers().stream()
                .sorted(TAB_RENDER_ORDER)
                .limit(VANILLA_RENDERED_TAB_LIMIT)
                .toList();
        List<String> lines = new ArrayList<>(rendered.size());
        for (PlayerInfo info : rendered) {
            Observation<String> line = bounded(client.gui.getTabList().getNameForDisplay(info));
            if (!line.isPresent()) {
                return Observation.unknown();
            }
            lines.add(line.get());
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
        int characterLimit = Math.toIntExact(Math.min(GameTextInputBudget.MAX_TOTAL_CHARACTERS,
                GameTextInputBudget.MAX_TAB_FOOTER_LINES
                        * (long) (GameTextInputBudget.MAX_LINE_CHARACTERS + 1)));
        String value = footer.getString(characterLimit + 1);
        if (value.length() > characterLimit) {
            return Observation.unknown();
        }
        List<String> lines = value.lines()
                .limit(GameTextInputBudget.MAX_TAB_FOOTER_LINES + 1L)
                .toList();
        return lines.size() > GameTextInputBudget.MAX_TAB_FOOTER_LINES
                || lines.stream().anyMatch(line -> line.length() > GameTextInputBudget.MAX_LINE_CHARACTERS)
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
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component component : lore.lines()) {
            Observation<String> line = bounded(component);
            if (!line.isPresent()) {
                return Observation.unknown();
            }
            lines.add(line.get());
        }
        return Observation.present(lines);
    }

    private Observation<List<RawChatMessage>> drainChat() {
        return chat.drain();
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
        return Observation.present(worldTransitions.observe(snapshot.world()));
    }

    private String localPlayerName() {
        return client.player == null ? null : client.player.getScoreboardName();
    }

    static Objective visibleSidebarObjective(Scoreboard scoreboard, String playerName) {
        Objects.requireNonNull(scoreboard, "scoreboard");
        if (playerName != null) {
            PlayerTeam team = scoreboard.getPlayersTeam(playerName);
            if (team != null) {
                DisplaySlot teamSlot = DisplaySlot.teamColorToSlot(team.getColor());
                Objective teamObjective = teamSlot == null
                        ? null
                        : scoreboard.getDisplayObjective(teamSlot);
                if (teamObjective != null) {
                    return teamObjective;
                }
            }
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    static Observation<List<PlayerScoreEntry>> visibleScoreboardEntries(
            Collection<PlayerScoreEntry> rawEntries) {
        Objects.requireNonNull(rawEntries, "rawEntries");
        if (rawEntries.size() > GameTextInputBudget.MAX_SCOREBOARD_RAW_ENTRIES) {
            return Observation.unknown();
        }
        return Observation.present(rawEntries.stream()
                .filter(entry -> !entry.isHidden())
                .sorted(SCOREBOARD_RENDER_ORDER)
                .limit(GameTextInputBudget.MAX_VISIBLE_SCOREBOARD_LINES)
                .toList());
    }

    static Observation<String> visibleScoreboardLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        return bounded(PlayerTeam.formatNameForTeam(team, entry.ownerName()));
    }

    static Comparator<PlayerInfo> tabRenderOrder() {
        return TAB_RENDER_ORDER;
    }

    static Comparator<PlayerScoreEntry> scoreboardRenderOrder() {
        return SCOREBOARD_RENDER_ORDER;
    }

    static Observation<String> bounded(Component component) {
        if (component == null) {
            return Observation.unknown();
        }
        String value = component.getString(GameTextInputBudget.MAX_LINE_CHARACTERS + 1);
        return value.length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                ? Observation.unknown()
                : Observation.present(value);
    }
}
