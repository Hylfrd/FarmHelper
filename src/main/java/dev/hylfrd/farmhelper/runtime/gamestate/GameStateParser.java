package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for immutable game-text inputs. */
public final class GameStateParser {
    private static final long MAX_SECONDS = 2_147_483_647L;
    private static final double MAX_SPEED = 2_147_483_647D;
    private static final BigDecimal MAX_EXACT_CURRENCY = new BigDecimal("9223372036854775807");
    private static final Pattern FORMAT_CODE = Pattern.compile(
            "(?i)§x(?:§[0-9a-f]){6}|§[0-9a-fk-or]");
    private static final Pattern LABELED_NUMBER = Pattern.compile(
            "^([0-9][0-9 ,.]*)(?:\\s*\\(\\+\\s*([0-9][0-9 ,.]*?)\\s*\\))?$");
    private static final Pattern TIME = Pattern.compile("(?:^|\\s)(\\d{1,9})m\\s*(\\d{1,9})s(?:\\s|$)");
    private static final Pattern STARTS_IN = Pattern.compile(
            "^Starts In:\\s*(?:(\\d{1,9})m)?\\s*(?:(\\d{1,9})s)?$");
    private static final Pattern SERVER_CLOSING = Pattern.compile(
            "^Server closing:\\s*(\\d{1,9}):(\\d{2})(?:\\s+.*)?$");
    private static final Pattern GUESTS = Pattern.compile("^Guests(?:\\s+|\\s*\\()(\\d+)(?:\\))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VACUUM = Pattern.compile("^Vacuum Bag:\\s*(.+?)\\s+Pests?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSTER_RESOURCE = Pattern.compile(
            "^(Organic Matter|Fuel):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUEST_CHAT = Pattern.compile(
            "^(?:\\[SkyBlock] )?[^:]+ is visiting Your Garden!$");

    private static final Map<String, SemanticLocation> LOCATIONS = Map.ofEntries(
            Map.entry("Private Island", SemanticLocation.PRIVATE_ISLAND),
            Map.entry("Hub", SemanticLocation.HUB),
            Map.entry("The Park", SemanticLocation.THE_PARK),
            Map.entry("The Farming Islands", SemanticLocation.THE_FARMING_ISLANDS),
            Map.entry("Spider's Den", SemanticLocation.SPIDERS_DEN),
            Map.entry("The End", SemanticLocation.THE_END),
            Map.entry("Crimson Isle", SemanticLocation.CRIMSON_ISLE),
            Map.entry("Gold Mine", SemanticLocation.GOLD_MINE),
            Map.entry("Deep Caverns", SemanticLocation.DEEP_CAVERNS),
            Map.entry("Dwarven Mines", SemanticLocation.DWARVEN_MINES),
            Map.entry("Crystal Hollows", SemanticLocation.CRYSTAL_HOLLOWS),
            Map.entry("Jerry's Workshop", SemanticLocation.JERRYS_WORKSHOP),
            Map.entry("Dungeon Hub", SemanticLocation.DUNGEON_HUB),
            Map.entry("Garden", SemanticLocation.GARDEN),
            Map.entry("Dungeon", SemanticLocation.DUNGEON));

    private final DiagnosticSink diagnosticSink;

    public GameStateParser() {
        this(DiagnosticSink.NOOP);
    }

    public GameStateParser(DiagnosticSink diagnosticSink) {
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    public GameStateParseResult parse(ClientSnapshot client, RawGameTextSnapshot raw) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(raw, "raw");
        Context context = new Context();
        Observation<List<GameChatSignal>> signals = parseChat(raw.chatBatch(), context);
        Observation<SemanticLocation> location = parseLocation(client, raw, signals, context);
        Observation<Boolean> skyBlock = parseSkyBlock(raw.scoreboardTitle(), location);
        Observation<Boolean> inGarden = location.map(value -> value == SemanticLocation.GARDEN);

        EconomySnapshot economy = new EconomySnapshot(
                parseDecimalLabel(raw.scoreboardLines(), "economy.purse", "Purse:", "Piggy:", context),
                parseLongLabel(raw.scoreboardLines(), "economy.bits", false, context, "Bits:"),
                parseLongLabel(raw.scoreboardLines(), "economy.copper", false, context, "Copper:"),
                parseSpeed(raw.playerFacts().walkSpeedFactor(), context));
        JacobStateSnapshot jacob = new JacobStateSnapshot(
                parseCurrentJacob(raw.scoreboardLines(), context),
                parseNextJacob(raw.tabLines(), context));
        BuffSnapshot buffs = parseBuffs(raw.tabLines(), raw.tabFooter(), context);
        GardenStateSnapshot garden = new GardenStateSnapshot(
                parseGuests(raw.tabLines(), context),
                parseTotalPests(raw.scoreboardLines(), context),
                parseCurrentPlotPests(raw.scoreboardLines(), context),
                parseInfestedPlots(raw.tabLines(), context),
                parseVacuum(raw.vacuumLore(), context),
                parseComposterResource(raw.tabLines(), "garden.organic", "Organic Matter", context),
                parseComposterResource(raw.tabLines(), "garden.fuel", "Fuel", context));

        GameStateSnapshot snapshot = new GameStateSnapshot(
                raw.generation(), location, skyBlock, inGarden,
                parseServerClosing(raw.scoreboardLines(), context),
                economy, jacob, buffs, garden);
        for (ParseDiagnostic diagnostic : context.diagnostics) {
            try {
                diagnosticSink.accept(diagnostic);
            } catch (RuntimeException ignored) {
                // Diagnostics must never change or prevent a parse result.
            }
        }
        return new GameStateParseResult(snapshot, signals, context.diagnostics);
    }

    private static Observation<SemanticLocation> parseLocation(
            ClientSnapshot client,
            RawGameTextSnapshot raw,
            Observation<List<GameChatSignal>> signals,
            Context context
    ) {
        if (client.connection().isUnknown() || client.player().isUnknown() || client.world().isUnknown()) {
            return Observation.unknown();
        }
        if (client.connection().isAbsent() || client.player().isAbsent() || client.world().isAbsent()) {
            return Observation.absent();
        }
        if (client.connection().get().mode() == ConnectionSnapshot.Mode.SINGLEPLAYER) {
            return Observation.absent();
        }

        boolean limboChat = signals.isPresent() && signals.get().stream()
                .anyMatch(signal -> signal.type() == GameChatSignalType.LIMBO_ENTERED);
        if (limboChat || completeLimboEvidence(client, raw)) {
            return Observation.present(SemanticLocation.LIMBO);
        }
        if (raw.worldTransition().isPresent()
                && raw.worldTransition().get() == WorldTransition.CHANGING) {
            return Observation.present(SemanticLocation.TELEPORTING);
        }

        Observation<SemanticLocation> area = resolve(raw.tabLines(), "location.area", line -> {
            if (!line.startsWith("Area:")) {
                return Match.irrelevant();
            }
            if (!line.startsWith("Area: ")) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            SemanticLocation location = LOCATIONS.get(line.substring("Area: ".length()).trim());
            return location == null
                    ? Match.invalid(ParseDiagnosticCode.UNKNOWN_FORMAT)
                    : Match.valid(location);
        }, context);
        if (!area.isAbsent()) {
            return area;
        }

        if (raw.scoreboardTitle().isPresent() && raw.scoreboardLines().isPresent()) {
            boolean skyBlockTitle = upper(clean(raw.scoreboardTitle().get())).contains("SKYBLOCK");
            boolean lobbySignature = raw.scoreboardLines().get().stream()
                    .map(GameStateParser::clean)
                    .anyMatch(line -> line.contains("www.hypixel.net"));
            if (!skyBlockTitle && lobbySignature) {
                return Observation.present(SemanticLocation.LOBBY);
            }
        }
        if (raw.tabLines().isUnknown() || raw.scoreboardTitle().isUnknown()
                || raw.scoreboardLines().isUnknown() || raw.worldTransition().isUnknown()) {
            return Observation.unknown();
        }
        return Observation.unknown();
    }

    private static boolean completeLimboEvidence(ClientSnapshot client, RawGameTextSnapshot raw) {
        if (!raw.scoreboardLines().isPresent() || !raw.scoreboardLines().get().isEmpty()
                || !raw.playerFacts().inventoryEmpty().isPresent()
                || !raw.playerFacts().inventoryEmpty().get()
                || !raw.playerFacts().experienceLevel().isPresent()
                || raw.playerFacts().experienceLevel().get() != 0) {
            return false;
        }
        Observation<ResourceIdentifier> dimension = client.world().get().dimension();
        return dimension.isPresent()
                && dimension.get().namespace().equals("minecraft")
                && dimension.get().path().equals("the_end");
    }

    private static Observation<Boolean> parseSkyBlock(
            Observation<String> title,
            Observation<SemanticLocation> location
    ) {
        if (location.isPresent()) {
            SemanticLocation value = location.get();
            if (value == SemanticLocation.LOBBY || value == SemanticLocation.LIMBO) {
                return Observation.present(false);
            }
            if (value != SemanticLocation.TELEPORTING) {
                return Observation.present(true);
            }
        }
        if (title.isPresent()) {
            return Observation.present(upper(clean(title.get())).contains("SKYBLOCK"));
        }
        return title.isAbsent() ? Observation.absent() : Observation.unknown();
    }

    private static Observation<List<GameChatSignal>> parseChat(
            Observation<List<RawChatMessage>> source,
            Context context
    ) {
        if (!source.isPresent()) {
            return source.isAbsent() ? Observation.absent() : Observation.unknown();
        }
        Map<Long, String> messages = new LinkedHashMap<>();
        Set<Long> conflicts = new LinkedHashSet<>();
        for (RawChatMessage message : source.get()) {
            String normalized = clean(message.text());
            String previous = messages.putIfAbsent(message.sequence(), normalized);
            if (previous != null && !previous.equals(normalized)) {
                conflicts.add(message.sequence());
            }
        }
        if (!conflicts.isEmpty()) {
            context.add("chat.sequence", ParseDiagnosticCode.DUPLICATE_SEQUENCE);
        }
        List<GameChatSignal> signals = new ArrayList<>();
        for (Map.Entry<Long, String> entry : messages.entrySet()) {
            if (conflicts.contains(entry.getKey())) {
                continue;
            }
            GameChatSignalType type = chatType(entry.getValue());
            if (type != null) {
                signals.add(new GameChatSignal(entry.getKey(), type));
            }
        }
        return Observation.present(List.copyOf(signals));
    }

    private static GameChatSignalType chatType(String message) {
        if (message.equals("You were spawned in Limbo.")
                || message.equals("You are AFK. Move around to return from AFK.")) {
            return GameChatSignalType.LIMBO_ENTERED;
        }
        if (GUEST_CHAT.matcher(message).matches()) {
            return GameChatSignalType.GUEST_ARRIVED;
        }
        if (message.startsWith("YUCK!") || message.startsWith("EWW!")
                || message.startsWith("GROSS!")) {
            return GameChatSignalType.PEST_SPAWNED;
        }
        if (message.startsWith("YUM! ൠ Pests will now spawn")) {
            return GameChatSignalType.REPELLENT_ACTIVATED;
        }
        if (message.equals("Server is restarting! Evacuate!")) {
            return GameChatSignalType.EVACUATION_REQUIRED;
        }
        return null;
    }

    private static Observation<BigDecimal> parseDecimalLabel(
            Observation<List<String>> source,
            String field,
            String firstLabel,
            String secondLabel,
            Context context
    ) {
        return resolve(source, field, line -> {
            String label = line.startsWith(firstLabel) ? firstLabel
                    : line.startsWith(secondLabel) ? secondLabel : null;
            if (label == null) {
                return Match.irrelevant();
            }
            String token = line.substring(label.length()).trim();
            try {
                return Match.valid(parseLabeledDecimal(token));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<Long> parseLongLabel(
            Observation<List<String>> source,
            String field,
            boolean scaledThousands,
            Context context,
            String label
    ) {
        return resolve(source, field, line -> {
            if (!line.startsWith(label)) {
                return Match.irrelevant();
            }
            try {
                return Match.valid(parseLabeledLong(line.substring(label.length()).trim(), scaledThousands));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static BigDecimal parseLabeledDecimal(String token) throws StrictGameNumber.NumericFailure {
        Matcher matcher = LABELED_NUMBER.matcher(token);
        if (!matcher.matches()) {
            throw new StrictGameNumber.NumericFailure(ParseDiagnosticCode.MALFORMED);
        }
        BigDecimal value = boundedDecimal(matcher.group(1));
        if (matcher.group(2) != null) {
            boundedDecimal(matcher.group(2));
        }
        return value;
    }

    private static BigDecimal boundedDecimal(String token) throws StrictGameNumber.NumericFailure {
        BigDecimal value = StrictGameNumber.decimal(token, false);
        if (value.compareTo(MAX_EXACT_CURRENCY) > 0) {
            throw new StrictGameNumber.NumericFailure(ParseDiagnosticCode.OVERFLOW);
        }
        return value;
    }

    private static long parseLabeledLong(String token, boolean scaledThousands)
            throws StrictGameNumber.NumericFailure {
        Matcher matcher = LABELED_NUMBER.matcher(token);
        if (!matcher.matches()) {
            throw new StrictGameNumber.NumericFailure(ParseDiagnosticCode.MALFORMED);
        }
        long value = StrictGameNumber.longValue(matcher.group(1), scaledThousands);
        if (matcher.group(2) != null) {
            StrictGameNumber.longValue(matcher.group(2), scaledThousands);
        }
        return value;
    }

    private static Observation<Integer> parseSpeed(Observation<Double> source, Context context) {
        if (!source.isPresent()) {
            return source.isAbsent() ? Observation.absent() : Observation.unknown();
        }
        double factor = source.get();
        double scaled = factor * 1_000.0D;
        if (!Double.isFinite(factor) || factor < 0 || scaled > MAX_SPEED
                || scaled != Math.rint(scaled)) {
            context.add("economy.speed", !Double.isFinite(scaled) || scaled > MAX_SPEED
                    ? ParseDiagnosticCode.OVERFLOW : ParseDiagnosticCode.MALFORMED);
            return Observation.unknown();
        }
        return Observation.present((int) scaled);
    }

    private static Observation<Integer> parseServerClosing(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "server.closing", line -> {
            if (!line.startsWith("Server closing:")) {
                return Match.irrelevant();
            }
            Matcher matcher = SERVER_CLOSING.matcher(line);
            if (!matcher.matches()) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            try {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                if (seconds >= 60) {
                    return Match.invalid(ParseDiagnosticCode.MALFORMED);
                }
                long total = Math.addExact(Math.multiplyExact(minutes, 60L), seconds);
                if (total > MAX_SECONDS) {
                    return Match.invalid(ParseDiagnosticCode.OVERFLOW);
                }
                return Match.valid((int) total);
            } catch (NumberFormatException | ArithmeticException exception) {
                return Match.invalid(ParseDiagnosticCode.OVERFLOW);
            }
        }, context);
    }

    private static Observation<JacobContestSnapshot> parseCurrentJacob(
            Observation<List<String>> source,
            Context context
    ) {
        if (!source.isPresent()) {
            return source.isAbsent() ? Observation.absent() : Observation.unknown();
        }
        List<String> lines = source.get().stream().map(GameStateParser::clean).toList();
        boolean present = lines.stream().anyMatch(line -> upper(line).contains("JACOB'S CONTEST"));
        if (!present) {
            return Observation.absent();
        }

        Observation<Integer> remaining = resolvePresent(lines, "jacob.current.time", line -> {
            Matcher matcher = TIME.matcher(line);
            if (!matcher.find()) {
                return Match.irrelevant();
            }
            return parseTime(matcher.group(1), matcher.group(2));
        }, context);
        if (remaining.isAbsent()) {
            context.add("jacob.current.time", ParseDiagnosticCode.INCOMPLETE);
            remaining = Observation.unknown();
        }

        Observation<GardenCrop> crop = resolvePresent(lines, "jacob.current.crop", line -> {
            if (!TIME.matcher(line).find()) {
                return Match.irrelevant();
            }
            GardenCrop value = crop(line);
            return value == null ? Match.invalid(ParseDiagnosticCode.UNKNOWN_FORMAT) : Match.valid(value);
        }, context);
        if (crop.isAbsent()) {
            context.add("jacob.current.crop", ParseDiagnosticCode.INCOMPLETE);
            crop = Observation.unknown();
        }

        Observation<Long> collected = resolvePresent(lines, "jacob.current.collected", line -> {
            if (!line.startsWith("Collected") && !line.contains(" with ")) {
                return Match.irrelevant();
            }
            String token = line.substring(line.lastIndexOf(' ') + 1);
            try {
                return Match.valid(StrictGameNumber.longValue(token, false));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
        Observation<JacobMedal> medal = resolvePresent(lines, "jacob.current.medal", line -> {
            if (!line.contains(" with ")) {
                return Match.irrelevant();
            }
            String normalized = upper(line);
            for (JacobMedal value : JacobMedal.values()) {
                if (normalized.contains(value.name() + " WITH")) {
                    return Match.valid(value);
                }
            }
            return Match.irrelevant();
        }, context);
        return Observation.present(new JacobContestSnapshot(remaining, crop, collected, medal));
    }

    private static Observation<JacobNextContestSnapshot> parseNextJacob(
            Observation<List<String>> source,
            Context context
    ) {
        if (!source.isPresent()) {
            return source.isAbsent() ? Observation.absent() : Observation.unknown();
        }
        List<String> lines = source.get().stream().map(GameStateParser::clean).toList();
        List<Integer> starts = new ArrayList<>();
        Observation<Integer> startsIn = Observation.absent();
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("Starts In:")) {
                starts.add(index);
            }
        }
        if (starts.isEmpty()) {
            return Observation.absent();
        }
        if (starts.size() > 1) {
            context.add("jacob.next", ParseDiagnosticCode.CONFLICT);
            return Observation.unknown();
        }
        int start = starts.getFirst();
        Matcher matcher = STARTS_IN.matcher(lines.get(start));
        if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
            context.add("jacob.next.time", ParseDiagnosticCode.MALFORMED);
            startsIn = Observation.unknown();
        } else {
            Match<Integer> parsed = parseTime(
                    matcher.group(1) == null ? "0" : matcher.group(1),
                    matcher.group(2) == null ? "0" : matcher.group(2));
            if (parsed.error != null) {
                context.add("jacob.next.time", parsed.error);
                startsIn = Observation.unknown();
            } else {
                startsIn = Observation.present(parsed.value);
            }
        }

        if (lines.size() < start + 4) {
            context.add("jacob.next.crops", ParseDiagnosticCode.INCOMPLETE);
            return Observation.present(new JacobNextContestSnapshot(startsIn, Observation.unknown()));
        }
        LinkedHashSet<GardenCrop> crops = new LinkedHashSet<>();
        for (int index = start + 1; index <= start + 3; index++) {
            GardenCrop value = crop(lines.get(index));
            if (value != null) {
                crops.add(value);
            }
        }
        Observation<List<GardenCrop>> cropObservation;
        if (crops.size() != 3) {
            context.add("jacob.next.crops", ParseDiagnosticCode.UNKNOWN_FORMAT);
            cropObservation = Observation.unknown();
        } else {
            cropObservation = Observation.present(List.copyOf(crops));
        }
        return Observation.present(new JacobNextContestSnapshot(startsIn, cropObservation));
    }

    private static Match<Integer> parseTime(String minutesText, String secondsText) {
        try {
            long minutes = Long.parseLong(minutesText);
            long seconds = Long.parseLong(secondsText);
            if (seconds >= 60) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            long total = Math.addExact(Math.multiplyExact(minutes, 60L), seconds);
            return total > MAX_SECONDS
                    ? Match.invalid(ParseDiagnosticCode.OVERFLOW)
                    : Match.valid((int) total);
        } catch (NumberFormatException | ArithmeticException exception) {
            return Match.invalid(ParseDiagnosticCode.OVERFLOW);
        }
    }

    private static BuffSnapshot parseBuffs(
            Observation<List<String>> tab,
            Observation<List<String>> footer,
            Context context
    ) {
        Observation<BuffStatus> cookie;
        Observation<BuffStatus> godPotion;
        Observation<BuffStatus> repellent;
        if (!footer.isPresent()) {
            cookie = footer.isAbsent() ? Observation.absent() : Observation.unknown();
            godPotion = cookie;
            repellent = cookie;
        } else {
            List<String> lines = footer.get().stream().map(GameStateParser::clean).toList();
            int activeEffects = indexContaining(lines, "Active Effects");
            if (activeEffects < 0) {
                context.add("buff.footer", ParseDiagnosticCode.INCOMPLETE);
                cookie = Observation.unknown();
                godPotion = Observation.unknown();
                repellent = Observation.unknown();
            } else {
                int cookieIndex = indexContaining(lines, "Cookie Buff");
                boolean cookieInactive = cookieIndex >= 0
                        && ((lines.get(cookieIndex).contains("Not active"))
                        || (cookieIndex + 1 < lines.size() && lines.get(cookieIndex + 1).contains("Not active")));
                cookie = Observation.present(cookieIndex >= 0 && !cookieInactive
                        ? BuffStatus.ACTIVE : BuffStatus.INACTIVE);
                godPotion = Observation.present(lines.stream()
                        .anyMatch(line -> line.contains("You have a God Potion active!"))
                        ? BuffStatus.ACTIVE : BuffStatus.INACTIVE);
                repellent = Observation.present(lines.stream().anyMatch(line ->
                        line.contains("Pest Repellant") || line.contains("Pest Repellent"))
                        ? BuffStatus.ACTIVE : BuffStatus.INACTIVE);
            }
        }

        Observation<BuffStatus> pestHunter = resolve(tab, "buff.pesthunter", line -> {
            if (!line.contains("Bonus:")) {
                return Match.irrelevant();
            }
            if (line.contains("Bonus: INACTIVE")) {
                return Match.valid(BuffStatus.INACTIVE);
            }
            if (line.contains("Bonus: +")) {
                return Match.valid(BuffStatus.ACTIVE);
            }
            return Match.invalid(ParseDiagnosticCode.UNKNOWN_FORMAT);
        }, context);
        Observation<BuffStatus> spray = resolve(tab, "buff.sprayonator", line -> {
            if (!line.startsWith("Spray:")) {
                return Match.irrelevant();
            }
            String value = line.substring("Spray:".length()).trim();
            if (value.isEmpty()) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            return Match.valid(value.equalsIgnoreCase("None") ? BuffStatus.INACTIVE : BuffStatus.ACTIVE);
        }, context);
        Observation<BuffStatus> composter = resolve(tab, "buff.composter", line -> {
            if (!line.startsWith("Time Left:")) {
                return Match.irrelevant();
            }
            String value = line.substring("Time Left:".length()).trim();
            if (value.isEmpty()) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            return Match.valid(value.equalsIgnoreCase("INACTIVE") ? BuffStatus.INACTIVE : BuffStatus.ACTIVE);
        }, context);
        return new BuffSnapshot(cookie, godPotion, repellent, pestHunter, spray, composter);
    }

    private static Observation<Boolean> parseGuests(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "garden.guests", line -> {
            if (!upper(line).startsWith("GUESTS")) {
                return Match.irrelevant();
            }
            Matcher matcher = GUESTS.matcher(line);
            if (!matcher.matches()) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            try {
                return Match.valid(StrictGameNumber.intValue(matcher.group(1)) > 0);
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<Integer> parseTotalPests(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "garden.pests.total", line -> {
            if (!line.contains("The Garden") || !line.contains("ൠ")) {
                return Match.irrelevant();
            }
            int marker = line.lastIndexOf('x');
            if (marker < 0 || marker == line.length() - 1) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            try {
                return Match.valid(StrictGameNumber.intValue(line.substring(marker + 1).trim()));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<Integer> parseCurrentPlotPests(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "garden.pests.plot", line -> {
            if (!line.matches(".*\\bPlot\\b.*")) {
                return Match.irrelevant();
            }
            int marker = line.lastIndexOf('x');
            if (marker < 0) {
                return Match.valid(0);
            }
            if (marker == line.length() - 1) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            try {
                return Match.valid(StrictGameNumber.intValue(line.substring(marker + 1).trim()));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<List<Integer>> parseInfestedPlots(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "garden.plots.infested", line -> {
            if (!line.startsWith("Plots:")) {
                return Match.irrelevant();
            }
            String value = line.substring("Plots:".length()).trim();
            if (value.isEmpty() || value.equalsIgnoreCase("None")) {
                return Match.valid(List.of());
            }
            if (!value.matches("[0-9]+(?:\\s*,\\s*[0-9]+)*")) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            List<Integer> plots = new ArrayList<>();
            try {
                for (String token : value.split(",")) {
                    int plot = StrictGameNumber.intValue(token.trim());
                    if (!plots.contains(plot)) {
                        plots.add(plot);
                    }
                }
                return Match.valid(List.copyOf(plots));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<Integer> parseVacuum(
            Observation<List<String>> source,
            Context context
    ) {
        return resolve(source, "garden.vacuum", line -> {
            if (!line.startsWith("Vacuum Bag:")) {
                return Match.irrelevant();
            }
            Matcher matcher = VACUUM.matcher(line);
            if (!matcher.matches()) {
                return Match.invalid(ParseDiagnosticCode.MALFORMED);
            }
            try {
                return Match.valid(StrictGameNumber.intValue(matcher.group(1)));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static Observation<Long> parseComposterResource(
            Observation<List<String>> source,
            String field,
            String resource,
            Context context
    ) {
        return resolve(source, field, line -> {
            Matcher matcher = COMPOSTER_RESOURCE.matcher(line);
            if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(resource)) {
                return Match.irrelevant();
            }
            try {
                return Match.valid(StrictGameNumber.longValue(matcher.group(2), true));
            } catch (StrictGameNumber.NumericFailure failure) {
                return Match.invalid(failure.code());
            }
        }, context);
    }

    private static GardenCrop crop(String line) {
        String value = upper(line);
        if (value.contains("WHEAT")) return GardenCrop.WHEAT;
        if (value.contains("CARROT")) return GardenCrop.CARROT;
        if (value.contains("POTATO")) return GardenCrop.POTATO;
        if (value.contains("NETHER") || value.contains("WART")) return GardenCrop.NETHER_WART;
        if (value.contains("SUGAR") || value.contains("CANE")) return GardenCrop.SUGAR_CANE;
        if (value.contains("MUSHROOM")) return GardenCrop.MUSHROOM;
        if (value.contains("MELON")) return GardenCrop.MELON;
        if (value.contains("PUMPKIN")) return GardenCrop.PUMPKIN;
        if (value.contains("COCOA") || value.contains("BEAN")) return GardenCrop.COCOA_BEANS;
        if (value.contains("CACTUS")) return GardenCrop.CACTUS;
        return null;
    }

    private static int indexContaining(List<String> lines, String target) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(target)) {
                return index;
            }
        }
        return -1;
    }

    private static <T> Observation<T> resolve(
            Observation<List<String>> source,
            String field,
            Function<String, Match<T>> decoder,
            Context context
    ) {
        if (!source.isPresent()) {
            return source.isAbsent() ? Observation.absent() : Observation.unknown();
        }
        return resolvePresent(source.get().stream().map(GameStateParser::clean).toList(), field, decoder, context);
    }

    private static <T> Observation<T> resolvePresent(
            List<String> lines,
            String field,
            Function<String, Match<T>> decoder,
            Context context
    ) {
        Set<T> values = new LinkedHashSet<>();
        ParseDiagnosticCode error = null;
        for (String line : lines) {
            Match<T> match = decoder.apply(line);
            if (!match.relevant) {
                continue;
            }
            if (match.error != null) {
                error = match.error;
            } else {
                values.add(match.value);
            }
        }
        if (error != null) {
            context.add(field, error);
            return Observation.unknown();
        }
        if (values.isEmpty()) {
            return Observation.absent();
        }
        if (values.size() > 1) {
            context.add(field, ParseDiagnosticCode.CONFLICT);
            return Observation.unknown();
        }
        return Observation.present(values.iterator().next());
    }

    static String clean(String raw) {
        return FORMAT_CODE.matcher(raw)
                .replaceAll("")
                .replace('\u00a0', ' ')
                .replace('\u202f', ' ')
                .trim()
                .replaceAll("[ \\t]+", " ");
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static final class Context {
        private final List<ParseDiagnostic> diagnostics = new ArrayList<>();

        private void add(String field, ParseDiagnosticCode code) {
            ParseDiagnostic diagnostic = new ParseDiagnostic(field, code);
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }
    }

    private static final class Match<T> {
        private final boolean relevant;
        private final T value;
        private final ParseDiagnosticCode error;

        private Match(boolean relevant, T value, ParseDiagnosticCode error) {
            this.relevant = relevant;
            this.value = value;
            this.error = error;
        }

        private static <T> Match<T> irrelevant() {
            return new Match<>(false, null, null);
        }

        private static <T> Match<T> valid(T value) {
            return new Match<>(true, Objects.requireNonNull(value, "value"), null);
        }

        private static <T> Match<T> invalid(ParseDiagnosticCode error) {
            return new Match<>(true, null, Objects.requireNonNull(error, "error"));
        }
    }
}
