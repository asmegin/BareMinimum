package com.bareminimum.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Zero-dependency parser/rebuilder for the top level of a JSON object.
 *
 * <p>Lets BareMinimum minimize top-level JSON body fields and compare the top-level keys of JSON
 * responses. The input is untrusted (it comes straight from HTTP messages), so the parser is
 * iterative (no recursion that a deeply nested document could exhaust), runs in linear time, and
 * rejects anything that is not a well-formed top-level object rather than guessing.
 *
 * <p>Fields keep their raw source text, so rebuilding an object never re-encodes a key or value.
 */
public final class JsonBodyHelper {

    private static final Pattern PRIMITIVE =
            Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false|null");

    /**
     * One top-level member of a JSON object.
     *
     * @param name     the decoded key, used for display and for matching by name
     * @param rawKey   the key exactly as written between its quotes (escapes untouched)
     * @param rawValue the value exactly as written
     */
    public record JsonField(String name, String rawKey, String rawValue) {
    }

    private JsonBodyHelper() {
    }

    /**
     * Parses a JSON object into its top-level members, in document order (duplicates preserved).
     * Returns an empty list if the input is not a well-formed JSON object.
     */
    public static List<JsonField> parseTopLevelObject(String json) {
        if (json == null) {
            return Collections.emptyList();
        }
        final int n = json.length();
        int i = skipWhitespace(json, 0, n);
        if (i >= n || json.charAt(i) != '{') {
            return Collections.emptyList();
        }
        i = skipWhitespace(json, i + 1, n);

        List<JsonField> fields = new ArrayList<>();
        if (i < n && json.charAt(i) == '}') {
            return skipWhitespace(json, i + 1, n) == n ? fields : Collections.emptyList();
        }

        while (true) {
            if (i >= n || json.charAt(i) != '"') {
                return Collections.emptyList();
            }
            int keyEnd = findStringEnd(json, i, n);
            if (keyEnd < 0) {
                return Collections.emptyList();
            }
            String rawKey = json.substring(i + 1, keyEnd);

            i = skipWhitespace(json, keyEnd + 1, n);
            if (i >= n || json.charAt(i) != ':') {
                return Collections.emptyList();
            }
            i = skipWhitespace(json, i + 1, n);
            if (i >= n) {
                return Collections.emptyList();
            }

            int valueEnd = findValueEnd(json, i, n);
            if (valueEnd <= i) {
                return Collections.emptyList();
            }
            fields.add(new JsonField(unescape(rawKey), rawKey, json.substring(i, valueEnd)));

            i = skipWhitespace(json, valueEnd, n);
            if (i >= n) {
                return Collections.emptyList();
            }
            char c = json.charAt(i);
            if (c == ',') {
                i = skipWhitespace(json, i + 1, n);
            } else if (c == '}') {
                return skipWhitespace(json, i + 1, n) == n ? fields : Collections.emptyList();
            } else {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Returns the decoded top-level key names of a JSON object (empty if not a JSON object).
     */
    public static Set<String> topLevelKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonField field : parseTopLevelObject(json)) {
            keys.add(field.name());
        }
        return keys;
    }

    /**
     * Renders a single member exactly as it will appear in a rebuilt object.
     */
    public static String renderField(JsonField field) {
        return "\"" + field.rawKey() + "\": " + field.rawValue();
    }

    /**
     * Rebuilds a JSON object from the given members, reusing their raw key and value text.
     */
    public static String rebuildJsonObject(List<JsonField> keptFields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (JsonField field : keptFields) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(renderField(field));
        }
        return sb.append('}').toString();
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int skipWhitespace(String s, int idx, int limit) {
        while (idx < limit && isJsonWhitespace(s.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    /** Returns the index of the closing quote of the string starting at {@code quoteStart}, or -1. */
    private static int findStringEnd(String s, int quoteStart, int limit) {
        int i = quoteStart + 1;
        while (i < limit) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Returns the exclusive end index of the value starting at {@code start}, or -1 if malformed. */
    private static int findValueEnd(String s, int start, int limit) {
        char first = s.charAt(start);
        if (first == '"') {
            int endQuote = findStringEnd(s, start, limit);
            return endQuote < 0 ? -1 : endQuote + 1;
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            int i = start;
            while (i < limit) {
                char c = s.charAt(i);
                if (c == '"') {
                    int endQuote = findStringEnd(s, i, limit);
                    if (endQuote < 0) {
                        return -1;
                    }
                    i = endQuote + 1;
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return -1;
        }
        // Primitive: number, true, false or null.
        int i = start;
        while (i < limit) {
            char c = s.charAt(i);
            if (c == ',' || c == '}' || c == ']' || isJsonWhitespace(c)) {
                break;
            }
            i++;
        }
        return PRIMITIVE.matcher(s.substring(start, i)).matches() ? i : -1;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                sb.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    int codeUnit = parseHex4(raw, i + 1);
                    if (codeUnit >= 0) {
                        sb.append((char) codeUnit);
                        i += 4;
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append(next); // covers \" \\ \/ and tolerates unknown escapes
            }
        }
        return sb.toString();
    }

    private static int parseHex4(String s, int from) {
        if (from + 4 > s.length()) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(s.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
