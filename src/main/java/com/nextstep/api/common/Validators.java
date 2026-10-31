package com.nextstep.api.common;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared server-side validation rules from the Data Model and Business Rules notes. */
public final class Validators {

    public static final String CONTROL_MESSAGE = "Remove unsupported control characters.";
    private static final String SINGLE_LINE_MESSAGE = "Use a single line.";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    private Validators() {
    }

    /** Required single-line text: trimmed, blank-only values rejected. */
    public static String requiredText(JsonInput in, String field, int max, String blankMessage) {
        String raw = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            in.errors().add(field, blankMessage);
            return null;
        }
        String value = raw.strip();
        if (value.length() > max) {
            in.errors().add(field, "Use " + max + " characters or fewer.");
            return null;
        }
        if (isMultiLine(value)) {
            in.errors().add(field, SINGLE_LINE_MESSAGE);
            return null;
        }
        return value;
    }

    /** Optional single-line text: trimmed, empty becomes null. */
    public static String optionalLine(JsonInput in, String field, int max) {
        String raw = in.string(field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > max) {
            in.errors().add(field, "Use " + max + " characters or fewer.");
            return null;
        }
        if (isMultiLine(value)) {
            in.errors().add(field, SINGLE_LINE_MESSAGE);
            return null;
        }
        return value;
    }

    /** Optional plain-text notes: internal whitespace and line breaks are preserved; blank becomes null. */
    public static String optionalNotes(JsonInput in, String field, int max) {
        String raw = in.string(field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > max) {
            in.errors().add(field, "Use " + max + " characters or fewer.");
            return null;
        }
        return raw;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * True when the text contains a control character other than tab, line feed, or carriage return. PostgreSQL can't
     * store NUL at all, and the other control characters have no place in this product's text.
     */
    public static boolean hasUnsupportedControl(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMultiLine(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    public static boolean isEmail(String value) {
        return value != null && value.length() <= 254 && EMAIL.matcher(value).matches();
    }

    public static String optionalEmail(JsonInput in, String field) {
        String value = optionalLine(in, field, 254);
        if (value != null && !isEmail(value)) {
            in.errors().add(field, "Enter a valid email address.");
            return null;
        }
        return value;
    }

    public static boolean isHttpUrl(String value) {
        if (value == null || value.length() > 2048) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    public static String optionalHttpUrl(JsonInput in, String field) {
        String value = optionalLine(in, field, 2048);
        if (value != null && !isHttpUrl(value)) {
            in.errors().add(field, "Enter a full web address starting with http:// or https://.");
            return null;
        }
        return value;
    }

    /**
     * Java also knows the legacy SystemV/* IDs, which PostgreSQL's time zone database does not. Accepting one would make
     * every SQL time conversion for that record fail, so they are rejected.
     */
    public static boolean isZone(String value) {
        return value != null && value.length() <= 64 && !value.startsWith("SystemV/") && ZONES.contains(value);
    }

    public static ZoneId zone(JsonInput in, String field, boolean required) {
        String value = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            if (required) {
                in.errors().add(field, "Choose a time zone.");
            }
            return null;
        }
        if (!isZone(value.strip())) {
            in.errors().add(field, "Choose a valid time zone, such as Asia/Manila.");
            return null;
        }
        return ZoneId.of(value.strip());
    }

    public static <E extends Enum<E>> E enumValue(JsonInput in, String field, Class<E> type, boolean required,
                                                  String requiredMessage) {
        String value = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            if (required) {
                in.errors().add(field, requiredMessage);
            }
            return null;
        }
        E parsed = parseEnum(type, value.strip());
        if (parsed == null) {
            in.errors().add(field, "Choose one of the listed options.");
        }
        return parsed;
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        return null;
    }

    public static LocalDate parseDate(String value) {
        if (value == null || !DATE.matcher(value).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    public static LocalDate date(JsonInput in, String field, boolean required, String requiredMessage) {
        String value = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            if (required) {
                in.errors().add(field, requiredMessage);
            }
            return null;
        }
        LocalDate parsed = parseDate(value.strip());
        if (parsed == null) {
            in.errors().add(field, "Enter a valid date (YYYY-MM-DD).");
        }
        return parsed;
    }

    public static LocalTime time(JsonInput in, String field, boolean required, String requiredMessage) {
        String value = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            if (required) {
                in.errors().add(field, requiredMessage);
            }
            return null;
        }
        String text = value.strip();
        if (!TIME.matcher(text).matches()) {
            in.errors().add(field, "Enter a valid time (HH:mm).");
            return null;
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException ex) {
            in.errors().add(field, "Enter a valid time (HH:mm).");
            return null;
        }
    }

    public static UUID uuid(JsonInput in, String field, String requiredMessage) {
        String value = in.string(field);
        if (in.errors().has(field)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            in.errors().add(field, requiredMessage);
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException ex) {
            in.errors().add(field, requiredMessage);
            return null;
        }
    }

    public static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    /** Money amounts fit NUMERIC(12,2): at most 10 whole digits and 2 decimal places. */
    public static boolean isMoney(BigDecimal value) {
        if (value == null) {
            return true;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        int wholeDigits = stripped.precision() - stripped.scale();
        return scale <= 2 && wholeDigits <= 10;
    }
}

