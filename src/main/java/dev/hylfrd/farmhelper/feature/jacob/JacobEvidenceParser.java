package dev.hylfrd.farmhelper.feature.jacob;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic adapter from cleaned Jacob scoreboard lines to immutable evidence.
 *
 * <p>This class models only the server-text observation surface. It does not read a scoreboard or
 * a client object; callers provide a stable contest identity and monotonically increasing sequence.
 */
public final class JacobEvidenceParser {
    public static final int MAX_LINES = 128;
    public static final int MAX_LINE_CHARACTERS = 2_048;
    public static final int MAX_NUMERIC_TOKEN_CHARACTERS = 64;

    private static final Pattern REMAINING_TIME = Pattern.compile(
            "([0-9]|[12][0-9])m([0-9]|[1-5][0-9])s");
    private static final Pattern TIME_SHAPE = Pattern.compile("[0-9]+m[0-9]+s");
    private static final String COUNT_TOKEN = "[0-9](?:[0-9,. ]*[0-9])?";
    private static final Pattern COLLECTED = Pattern.compile("^Collected (" + COUNT_TOKEN + ")$");
    private static final Pattern MEDAL_THRESHOLD = Pattern.compile(
            "^(BRONZE|SILVER|GOLD|PLATINUM|DIAMOND) with (" + COUNT_TOKEN + ")$");

    /**
     * Parses one already-cleaned scoreboard batch. No input line is retained in the result.
     */
    public JacobEvidenceParseResult parse(
            JacobContestIdentity contest,
            long sequence,
            List<String> cleanedLines
    ) {
        Objects.requireNonNull(contest, "contest");
        requireSequence(sequence);
        Objects.requireNonNull(cleanedLines, "cleanedLines");

        List<String> copiedLines;
        try {
            copiedLines = List.copyOf(cleanedLines);
        } catch (NullPointerException exception) {
            return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.INPUT_LIMIT);
        }
        if (copiedLines.size() > MAX_LINES) {
            return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.INPUT_LIMIT);
        }
        for (String line : copiedLines) {
            if (line.length() > MAX_LINE_CHARACTERS) {
                return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.INPUT_LIMIT);
            }
        }

        List<String> lines = copiedLines.stream().map(String::trim).toList();
        boolean contestVisible = lines.stream()
                .map(line -> line.toUpperCase(Locale.ROOT))
                .anyMatch(line -> line.contains("JACOB'S CONTEST"));
        if (!contestVisible) {
            return JacobEvidenceParseResult.absent(contest, sequence);
        }

        Set<JacobCrop> crops = EnumSet.noneOf(JacobCrop.class);
        for (String line : lines) {
            Matcher validTime = REMAINING_TIME.matcher(line);
            if (validTime.find()) {
                Optional<JacobCrop> crop = JacobCrop.fromLine(line);
                if (crop.isEmpty()) {
                    return JacobEvidenceParseResult.unknown(
                            contest, sequence, JacobEvidenceIssue.UNKNOWN_FORMAT);
                }
                crops.add(crop.get());
            } else if (TIME_SHAPE.matcher(line).find()) {
                return JacobEvidenceParseResult.unknown(
                        contest, sequence, JacobEvidenceIssue.MALFORMED);
            }
        }
        if (crops.isEmpty()) {
            return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.INCOMPLETE);
        }
        if (crops.size() > 1) {
            return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.CONFLICT);
        }

        CountParse count = parseCount(lines);
        if (count.issue() != JacobEvidenceIssue.NONE) {
            return JacobEvidenceParseResult.unknown(contest, sequence, count.issue());
        }
        JacobContestEvidence evidence = new JacobContestEvidence(
                contest, crops.iterator().next(), count.value(), sequence);
        return JacobEvidenceParseResult.present(evidence);
    }

    /** Creates a fail-closed result for a source that could not be captured. */
    public JacobEvidenceParseResult unknown(JacobContestIdentity contest, long sequence) {
        return JacobEvidenceParseResult.unknown(contest, sequence, JacobEvidenceIssue.SOURCE_UNKNOWN);
    }

    private static CountParse parseCount(List<String> lines) {
        List<String> explicit = lines.stream()
                .filter(line -> line.toUpperCase(Locale.ROOT).startsWith("COLLECTED"))
                .toList();
        if (!explicit.isEmpty()) {
            return decodeCountLines(explicit, COLLECTED, 1);
        }

        List<String> thresholdLines = lines.stream()
                .filter(JacobEvidenceParser::containsWithToken)
                .toList();
        if (thresholdLines.isEmpty()) {
            return new CountParse(0L, JacobEvidenceIssue.INCOMPLETE);
        }
        return decodeCountLines(thresholdLines, MEDAL_THRESHOLD, 2);
    }

    private static CountParse decodeCountLines(
            List<String> lines,
            Pattern pattern,
            int countGroup
    ) {
        Long value = null;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (!matcher.matches()) {
                return new CountParse(0L, JacobEvidenceIssue.UNKNOWN_FORMAT);
            }
            long parsed;
            try {
                parsed = parseInteger(matcher.group(countGroup));
            } catch (CountFailure failure) {
                return new CountParse(0L, failure.issue());
            }
            if (value != null && value.longValue() != parsed) {
                return new CountParse(0L, JacobEvidenceIssue.CONFLICT);
            }
            value = parsed;
        }
        return new CountParse(value == null ? 0L : value, JacobEvidenceIssue.NONE);
    }

    private static boolean containsWithToken(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.equals("WITH")
                || upper.startsWith("WITH ")
                || upper.endsWith(" WITH")
                || upper.contains(" WITH ");
    }

    private static long parseInteger(String token) throws CountFailure {
        if (token.length() > MAX_NUMERIC_TOKEN_CHARACTERS) {
            throw new CountFailure(JacobEvidenceIssue.INPUT_LIMIT);
        }
        if (!token.matches(COUNT_TOKEN)) {
            throw new CountFailure(JacobEvidenceIssue.MALFORMED);
        }

        String canonical;
        if (token.indexOf(' ') >= 0) {
            if (token.indexOf(',') >= 0 || token.indexOf('.') >= 0) {
                throw new CountFailure(JacobEvidenceIssue.MALFORMED);
            }
            canonical = groupedInteger(token, ' ');
        } else {
            boolean comma = token.indexOf(',') >= 0;
            boolean dot = token.indexOf('.') >= 0;
            if (comma && dot) {
                throw new CountFailure(JacobEvidenceIssue.MALFORMED);
            }
            if (comma) {
                canonical = groupedInteger(token, ',');
            } else if (dot) {
                canonical = groupedInteger(token, '.');
            } else {
                canonical = token;
            }
        }

        try {
            BigInteger value = new BigInteger(canonical);
            if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new CountFailure(JacobEvidenceIssue.OVERFLOW);
            }
            return value.longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new CountFailure(JacobEvidenceIssue.MALFORMED);
        }
    }

    private static String groupedInteger(String value, char separator) throws CountFailure {
        String[] groups = value.split(Pattern.quote(String.valueOf(separator)), -1);
        if (groups.length < 2 || groups[0].length() < 1 || groups[0].length() > 3
                || !digits(groups[0])) {
            throw new CountFailure(JacobEvidenceIssue.MALFORMED);
        }
        StringBuilder compact = new StringBuilder(value.length());
        compact.append(groups[0]);
        for (int index = 1; index < groups.length; index++) {
            if (groups[index].length() != 3 || !digits(groups[index])) {
                throw new CountFailure(JacobEvidenceIssue.MALFORMED);
            }
            compact.append(groups[index]);
        }
        return compact.toString();
    }

    private static boolean digits(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static void requireSequence(long sequence) {
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }

    private record CountParse(long value, JacobEvidenceIssue issue) {
    }

    private static final class CountFailure extends Exception {
        private final JacobEvidenceIssue issue;

        private CountFailure(JacobEvidenceIssue issue) {
            this.issue = issue;
        }

        private JacobEvidenceIssue issue() {
            return issue;
        }
    }
}
