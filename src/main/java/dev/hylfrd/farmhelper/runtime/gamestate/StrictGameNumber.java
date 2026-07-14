package dev.hylfrd.farmhelper.runtime.gamestate;

import java.math.BigDecimal;

/** Locale-independent, strict parsing for the numeric forms emitted by game text. */
final class StrictGameNumber {
    private StrictGameNumber() {
    }

    static BigDecimal decimal(String token, boolean scaledThousands) throws NumericFailure {
        String value = token.trim();
        boolean thousands = false;
        if (value.endsWith("k") || value.endsWith("K")) {
            if (!scaledThousands) {
                throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
            }
            thousands = true;
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty() || !value.matches("[0-9][0-9 ,.]*")) {
            throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
        }

        String canonical = canonical(value, thousands);
        try {
            BigDecimal parsed = new BigDecimal(canonical);
            if (thousands) {
                parsed = parsed.multiply(BigDecimal.valueOf(1_000L));
            }
            return parsed.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
        }
    }

    static long longValue(String token, boolean scaledThousands) throws NumericFailure {
        BigDecimal decimal = decimal(token, scaledThousands);
        if (decimal.scale() > 0) {
            throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
        }
        try {
            long value = decimal.longValueExact();
            if (value < 0) {
                throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
            }
            return value;
        } catch (ArithmeticException exception) {
            throw new NumericFailure(ParseDiagnosticCode.OVERFLOW);
        }
    }

    static int intValue(String token) throws NumericFailure {
        long value = longValue(token, false);
        if (value > 2_147_483_647L) {
            throw new NumericFailure(ParseDiagnosticCode.OVERFLOW);
        }
        return (int) value;
    }

    private static String canonical(String value, boolean scaledThousands) throws NumericFailure {
        if (value.contains(" ")) {
            if (!value.matches("[0-9]{1,3}(?: [0-9]{3})+(?:[.,][0-9]+)?")) {
                throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
            }
            String compact = value.replace(" ", "");
            int comma = compact.lastIndexOf(',');
            int dot = compact.lastIndexOf('.');
            if (comma >= 0 || dot >= 0) {
                char decimal = comma > dot ? ',' : '.';
                compact = compact.replace(decimal, '.');
            }
            return compact;
        }

        int commas = count(value, ',');
        int dots = count(value, '.');
        if (commas > 0 && dots > 0) {
            char decimal = value.lastIndexOf(',') > value.lastIndexOf('.') ? ',' : '.';
            char grouping = decimal == ',' ? '.' : ',';
            int split = value.lastIndexOf(decimal);
            String integer = value.substring(0, split);
            String fraction = value.substring(split + 1);
            String groupingRegex = "[0-9]{1,3}(?:\\" + grouping + "[0-9]{3})+";
            if (!integer.matches(groupingRegex) || !fraction.matches("[0-9]+")) {
                throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
            }
            return integer.replace(String.valueOf(grouping), "") + '.' + fraction;
        }

        char separator = commas > 0 ? ',' : '.';
        int separators = commas + dots;
        if (separators == 0) {
            return value;
        }
        if (separators > 1) {
            String regex = "[0-9]{1,3}(?:\\" + separator + "[0-9]{3})+";
            if (!value.matches(regex)) {
                throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
            }
            return value.replace(String.valueOf(separator), "");
        }

        int split = value.indexOf(separator);
        String left = value.substring(0, split);
        String right = value.substring(split + 1);
        if (left.isEmpty() || right.isEmpty()) {
            throw new NumericFailure(ParseDiagnosticCode.MALFORMED);
        }
        if (!scaledThousands && right.length() == 3 && left.length() <= 3) {
            return left + right;
        }
        return left + '.' + right;
    }

    private static int count(String value, char target) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == target) {
                count++;
            }
        }
        return count;
    }

    static final class NumericFailure extends Exception {
        private final ParseDiagnosticCode code;

        NumericFailure(ParseDiagnosticCode code) {
            this.code = code;
        }

        ParseDiagnosticCode code() {
            return code;
        }
    }
}
