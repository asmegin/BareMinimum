package com.bareminimum.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Zero-dependency reader for XML request bodies (SOAP, XML-RPC, plain XML APIs) that lets
 * BareMinimum remove individual elements and attributes.
 *
 * <p>The input is untrusted, so this is deliberately not an XML processor: it only locates tags
 * and slices the original text. It never expands entities, never reads a DTD and never resolves
 * anything external (no XXE), runs in linear time without recursion, and rejects anything that is
 * not a single well-formed root element rather than guessing. Rebuilding a body only cuts removed
 * elements and attributes out of the original text, so everything that is kept, including
 * namespace declarations, comments and formatting, stays byte-for-byte as it was.
 */
public final class XmlBodyHelper {

    /** Paths are only worked out this deep (see {@link JsonBodyHelper#PATH_DEPTH_LIMIT}). */
    public static final int PATH_DEPTH_LIMIT = JsonBodyHelper.PATH_DEPTH_LIMIT;

    private XmlBodyHelper() {
    }

    /**
     * An element below the root, or an attribute of any element. Nodes are listed in document
     * order with an element's attributes directly after it, so a node's descendants follow it.
     */
    public static final class Node {
        /** Display path, e.g. {@code soap:Body/m:GetUser/m:id} or {@code order/@currency}; null below {@link #PATH_DEPTH_LIMIT}. */
        public final String path;
        public final boolean attribute;
        /** Index of the enclosing element in {@link Tree#nodes()}, or -1 when that is the root. */
        public final int parent;
        /** 1 for children and attributes of the root, 2 below them, and so on. */
        public final int depth;
        /** Start of the text removed with this node, including whitespace in front of it. */
        final int cutStart;
        int end;
        int valueStart;
        int valueEnd;

        Node(String path, boolean attribute, int parent, int depth, int cutStart, int valueStart, int valueEnd) {
            this.path = path;
            this.attribute = attribute;
            this.parent = parent;
            this.depth = depth;
            this.cutStart = cutStart;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
        }

        /** Start of the attribute value, or of the element's content without surrounding whitespace. */
        public int valueStart() {
            return valueStart;
        }

        public int valueEnd() {
            return valueEnd;
        }
    }

    /** A parsed XML document. */
    public record Tree(String text, List<Node> nodes) {

        /** The attribute value, or the element's content, as written (for display). */
        public String value(Node node) {
            return text.substring(node.valueStart, node.valueEnd);
        }

        /** Writes the document back out without the {@code removed} nodes and their contents. */
        public String render(boolean[] removed) {
            int count = nodes.size();
            boolean[] gone = new boolean[count];
            StringBuilder sb = null;
            int pos = 0;
            for (int k = 0; k < count; k++) {
                Node node = nodes.get(k);
                boolean parentGone = node.parent >= 0 && gone[node.parent];
                gone[k] = removed[k] || parentGone;
                if (removed[k] && !parentGone) {
                    if (sb == null) {
                        sb = new StringBuilder(text.length());
                    }
                    int from = Math.max(pos, node.cutStart);
                    sb.append(text, pos, from);
                    pos = Math.max(pos, node.end);
                }
            }
            if (sb == null) {
                return text;
            }
            return sb.append(text, pos, text.length()).toString();
        }
    }

    private record Open(String name, int node, String path, int contentStart) {
    }

    /** Parses an XML document with a single root element, or returns null if it is not one. */
    public static Tree parse(String xml) {
        if (xml == null) {
            return null;
        }
        final int n = xml.length();
        int i = n > 0 && xml.charAt(0) == '﻿' ? 1 : 0;
        List<Node> nodes = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
        boolean rootSeen = false;
        boolean rootClosed = false;

        while (i < n) {
            char c = xml.charAt(i);
            if (c != '<') {
                int next = xml.indexOf('<', i);
                if (next < 0) {
                    next = n;
                }
                if (stack.isEmpty() && !isBlank(xml, i, next)) {
                    return null; // text outside the root element
                }
                i = next;
                continue;
            }
            if (xml.startsWith("<?", i)) {
                int end = xml.indexOf("?>", i + 2);
                if (end < 0) {
                    return null;
                }
                i = end + 2;
                continue;
            }
            if (xml.startsWith("<!--", i)) {
                int end = xml.indexOf("-->", i + 4);
                if (end < 0) {
                    return null;
                }
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<![CDATA[", i)) {
                int end = xml.indexOf("]]>", i + 9);
                if (stack.isEmpty() || end < 0) {
                    return null;
                }
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<!", i)) {
                // A DOCTYPE: skipped as text, never interpreted.
                int end = rootSeen ? -1 : skipDeclaration(xml, i + 2);
                if (end < 0) {
                    return null;
                }
                i = end;
                continue;
            }
            if (xml.startsWith("</", i)) {
                int end = xml.indexOf('>', i + 2);
                if (end < 0 || stack.isEmpty()) {
                    return null;
                }
                Open open = stack.pop();
                if (!open.name().equals(xml.substring(i + 2, end).trim())) {
                    return null;
                }
                if (open.node() >= 0) {
                    Node node = nodes.get(open.node());
                    node.end = end + 1;
                    int from = open.contentStart();
                    int to = i;
                    while (from < to && isWhitespace(xml.charAt(from))) {
                        from++;
                    }
                    while (to > from && isWhitespace(xml.charAt(to - 1))) {
                        to--;
                    }
                    node.valueStart = from;
                    node.valueEnd = to;
                }
                rootClosed = stack.isEmpty();
                i = end + 1;
                continue;
            }

            // A start tag.
            if (rootClosed) {
                return null; // a second root element
            }
            int j = i + 1;
            while (j < n && isNameChar(xml.charAt(j))) {
                j++;
            }
            if (j == i + 1) {
                return null;
            }
            String name = xml.substring(i + 1, j);
            int depth = stack.size();
            String parentPath = depth == 0 ? "" : stack.peek().path();
            String path = depth == 0 ? name
                    : parentPath == null || depth > PATH_DEPTH_LIMIT ? null : parentPath + "/" + name;
            int element = -1;
            if (depth > 0) {
                element = nodes.size();
                nodes.add(new Node(path, false, stack.peek().node(), depth, elementCutStart(xml, i), j, j));
            }

            boolean selfClosing;
            while (true) {
                int spaceStart = j;
                while (j < n && isWhitespace(xml.charAt(j))) {
                    j++;
                }
                if (j >= n) {
                    return null;
                }
                char d = xml.charAt(j);
                if (d == '>') {
                    selfClosing = false;
                    j++;
                    break;
                }
                if (d == '/') {
                    if (j + 1 < n && xml.charAt(j + 1) == '>') {
                        selfClosing = true;
                        j += 2;
                        break;
                    }
                    return null;
                }
                if (j == spaceStart) {
                    return null; // attributes must be separated by whitespace
                }
                int nameStart = j;
                while (j < n && isNameChar(xml.charAt(j))) {
                    j++;
                }
                if (j == nameStart) {
                    return null;
                }
                String attributeName = xml.substring(nameStart, j);
                while (j < n && isWhitespace(xml.charAt(j))) {
                    j++;
                }
                if (j >= n || xml.charAt(j) != '=') {
                    return null;
                }
                j++;
                while (j < n && isWhitespace(xml.charAt(j))) {
                    j++;
                }
                if (j >= n || (xml.charAt(j) != '"' && xml.charAt(j) != '\'')) {
                    return null;
                }
                int close = xml.indexOf(xml.charAt(j), j + 1);
                if (close < 0) {
                    return null;
                }
                int lt = xml.indexOf('<', j + 1);
                if (lt >= 0 && lt < close) {
                    return null; // '<' is not allowed in an attribute value
                }
                int valueStart = j + 1;
                j = close + 1;
                // Namespace declarations are never removed: without them the document breaks.
                if (!attributeName.equals("xmlns") && !attributeName.startsWith("xmlns:")) {
                    Node attribute = new Node(path == null ? null : path + "/@" + attributeName, true, element,
                            depth + 1, spaceStart, valueStart, close);
                    attribute.end = j;
                    nodes.add(attribute);
                }
            }

            rootSeen = true;
            if (selfClosing) {
                if (element >= 0) {
                    nodes.get(element).end = j;
                }
                rootClosed = depth == 0;
            } else {
                stack.push(new Open(name, element, path, j));
            }
            i = j;
        }
        if (!rootSeen || !stack.isEmpty()) {
            return null;
        }
        return new Tree(xml, List.copyOf(nodes));
    }

    /** An element's text starts at its {@code <}, plus indentation if it follows another tag. */
    private static int elementCutStart(String xml, int tagStart) {
        int k = tagStart;
        while (k > 0 && isWhitespace(xml.charAt(k - 1))) {
            k--;
        }
        return k > 0 && xml.charAt(k - 1) == '>' ? k : tagStart;
    }

    /** Returns the index just after a {@code <!...>} declaration, or -1 if it never ends. */
    private static int skipDeclaration(String xml, int from) {
        int depth = 0;
        char quote = 0;
        for (int k = from; k < xml.length(); k++) {
            char c = xml.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return k + 1;
            }
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return !isWhitespace(c) && c != '/' && c != '>' && c != '<' && c != '=' && c != '"' && c != '\'';
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isBlank(String s, int from, int to) {
        for (int k = from; k < to; k++) {
            if (!isWhitespace(s.charAt(k))) {
                return false;
            }
        }
        return true;
    }
}
