package com.bareminimum.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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

    /**
     * Paths are only worked out this deep. Deeper members still parse, but nobody offers them for
     * removal, and building ever-longer paths for pathological nesting would take quadratic memory.
     */
    public static final int PATH_DEPTH_LIMIT = 64;

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

    // --- Full tree, for minimizing nested members ------------------------------------------

    /**
     * One member of a JSON document below the root: an object member or an array element.
     * Nodes are listed in document order, so a node's descendants directly follow it.
     */
    public static final class Node {
        /** Display path, e.g. {@code user.address.city} or {@code items[2].id}; null below {@link #PATH_DEPTH_LIMIT}. */
        public final String path;
        /** The key exactly as written between its quotes, or null for an array element. */
        public final String rawKey;
        /** Index of the enclosing member in {@link Tree#nodes()}, or -1 for a member of the root. */
        public final int parent;
        /** 1 for members of the root, 2 for their members, and so on. */
        public final int depth;
        public final int valueStart;
        int valueEnd;

        Node(String path, String rawKey, int parent, int depth, int valueStart) {
            this.path = path;
            this.rawKey = rawKey;
            this.parent = parent;
            this.depth = depth;
            this.valueStart = valueStart;
        }

        public int valueEnd() {
            return valueEnd;
        }
    }

    /** A parsed JSON document whose root is an object or an array. */
    public record Tree(String text, int rootStart, int rootEnd, List<Node> nodes) {

        public String rawValue(Node node) {
            return text.substring(node.valueStart, node.valueEnd);
        }

        /**
         * Writes the document back out without the {@code removed} nodes (and everything inside
         * them). Containers with nothing removed inside them are copied verbatim; a container that
         * lost members is rewritten as {@code "key": value} pairs separated by {@code ", "}.
         */
        public String render(boolean[] removed) {
            int count = nodes.size();
            boolean[] changedBelow = new boolean[count];
            boolean rootChanged = false;
            List<List<Integer>> children = new ArrayList<>(count);
            List<Integer> rootChildren = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                children.add(null);
            }
            for (int k = 0; k < count; k++) {
                int parent = nodes.get(k).parent;
                if (parent < 0) {
                    rootChildren.add(k);
                } else {
                    List<Integer> list = children.get(parent);
                    if (list == null) {
                        list = new ArrayList<>();
                        children.set(parent, list);
                    }
                    list.add(k);
                }
            }
            for (int k = count - 1; k >= 0; k--) {
                if (removed[k] || changedBelow[k]) {
                    int parent = nodes.get(k).parent;
                    if (parent < 0) {
                        rootChanged = true;
                    } else {
                        changedBelow[parent] = true;
                    }
                }
            }
            if (!rootChanged) {
                return text;
            }
            StringBuilder sb = new StringBuilder(text.length());
            sb.append(text, 0, rootStart);
            renderContainer(sb, text.charAt(rootStart), rootChildren, removed, changedBelow, children);
            sb.append(text, rootEnd, text.length());
            return sb.toString();
        }

        private void renderContainer(StringBuilder sb, char open, List<Integer> members,
                                     boolean[] removed, boolean[] changedBelow, List<List<Integer>> children) {
            sb.append(open);
            boolean first = true;
            for (int k : members == null ? List.<Integer>of() : members) {
                if (removed[k]) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                Node node = nodes.get(k);
                if (node.rawKey != null) {
                    sb.append('"').append(node.rawKey).append("\": ");
                }
                if (changedBelow[k]) {
                    // Only reached for containers holding a removed member, so recursion depth is
                    // bounded by the depth of the removable members, not by the document.
                    renderContainer(sb, text.charAt(node.valueStart), children.get(k), removed, changedBelow, children);
                } else {
                    sb.append(text, node.valueStart, node.valueEnd);
                }
            }
            sb.append(open == '{' ? '}' : ']');
        }
    }

    private static final class Frame {
        final boolean object;
        final int node;      // -1 for the root
        final String path;   // "" for the root, null below PATH_DEPTH_LIMIT
        int count;
        boolean expectMember = true; // after '{', '[' or ','

        Frame(boolean object, int node, String path) {
            this.object = object;
            this.node = node;
            this.path = path;
        }
    }

    /**
     * Parses a whole JSON document whose root is an object or an array. Returns null if the input
     * is not well-formed. Iterative, so arbitrarily deep nesting cannot exhaust the stack.
     */
    public static Tree parseTree(String json) {
        if (json == null) {
            return null;
        }
        final int n = json.length();
        int i = skipWhitespace(json, 0, n);
        if (i >= n || (json.charAt(i) != '{' && json.charAt(i) != '[')) {
            return null;
        }
        int rootStart = i;
        List<Node> nodes = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(json.charAt(i) == '{', -1, ""));
        i++;

        while (true) {
            Frame frame = stack.peek();
            i = skipWhitespace(json, i, n);
            if (i >= n) {
                return null;
            }
            char c = json.charAt(i);
            char close = frame.object ? '}' : ']';

            if (c == close && (frame.count == 0 || !frame.expectMember)) {
                // "{}" / "[]", or the end after a member (a trailing comma is rejected above).
                stack.pop();
                if (frame.node >= 0) {
                    nodes.get(frame.node).valueEnd = i + 1;
                }
                i++;
                if (stack.isEmpty()) {
                    return skipWhitespace(json, i, n) == n ? new Tree(json, rootStart, i, List.copyOf(nodes)) : null;
                }
                stack.peek().expectMember = false;
                continue;
            }
            if (!frame.expectMember) {
                if (c != ',') {
                    return null;
                }
                frame.expectMember = true;
                i++;
                continue;
            }

            // A member: "key": value for objects, value for arrays.
            String rawKey = null;
            String path;
            if (frame.object) {
                if (c != '"') {
                    return null;
                }
                int keyEnd = findStringEnd(json, i, n);
                if (keyEnd < 0) {
                    return null;
                }
                rawKey = json.substring(i + 1, keyEnd);
                if (frame.path == null || stack.size() > PATH_DEPTH_LIMIT) {
                    path = null;
                } else {
                    String key = unescape(rawKey);
                    path = frame.path.isEmpty() ? key : frame.path + "." + key;
                }
                i = skipWhitespace(json, keyEnd + 1, n);
                if (i >= n || json.charAt(i) != ':') {
                    return null;
                }
                i = skipWhitespace(json, i + 1, n);
                if (i >= n) {
                    return null;
                }
            } else {
                path = frame.path == null || stack.size() > PATH_DEPTH_LIMIT ? null : frame.path + "[" + frame.count + "]";
            }
            frame.count++;
            frame.expectMember = false;

            int index = nodes.size();
            Node node = new Node(path, rawKey, frame.node, stack.size(), i);
            nodes.add(node);
            char first = json.charAt(i);
            if (first == '{' || first == '[') {
                stack.push(new Frame(first == '{', index, path));
                i++;
                continue;
            }
            int valueEnd;
            if (first == '"') {
                int endQuote = findStringEnd(json, i, n);
                valueEnd = endQuote < 0 ? -1 : endQuote + 1;
            } else {
                valueEnd = findValueEnd(json, i, n);
            }
            if (valueEnd <= i) {
                return null;
            }
            node.valueEnd = valueEnd;
            i = valueEnd;
        }
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
