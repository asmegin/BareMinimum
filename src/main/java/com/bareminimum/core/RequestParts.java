package com.bareminimum.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the removable parts of a request (cookie pairs, query / form pairs, multipart parts)
 * into segments that keep their exact source text.
 *
 * <p>BareMinimum removes items by dropping whole segments and re-joining the rest, so anything it
 * keeps is byte-for-byte what was in the original request: values are never decoded, re-encoded
 * or normalized. Segments are identified by position, which makes duplicate names unambiguous.
 */
public final class RequestParts {

    private static final Pattern BOUNDARY =
            Pattern.compile("(?i)boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;,\\s]+))");
    private static final Pattern DISPOSITION_NAME =
            Pattern.compile("(?i)(?:^|[;\\s])name\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))");
    private static final Pattern DISPOSITION_FILENAME =
            Pattern.compile("(?i)(?:^|[;\\s])filename\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))");
    private static final Pattern LOOKS_URL_ENCODED =
            Pattern.compile("[^\\s&=<>{}\\[\\]\"]+=[^\\s&]*(?:&[^\\s&=]+=[^\\s&]*)*");

    /**
     * A removable piece of a request.
     *
     * @param name  the item name shown to the user and used when matching by name
     * @param value the item value shown to the user
     * @param raw   the exact source text that is kept or dropped
     */
    public record Segment(String name, String value, String raw) {
    }

    /** A parsed {@code multipart/form-data} body. */
    public record Multipart(String boundary, String lineEnding, List<Segment> parts) {

        /** Rebuilds the body from the given parts (in order), closing it with the final boundary. */
        public String rebuild(List<Segment> keptParts) {
            StringBuilder sb = new StringBuilder();
            for (Segment part : keptParts) {
                sb.append("--").append(boundary).append(lineEnding).append(part.raw()).append(lineEnding);
            }
            return sb.append("--").append(boundary).append("--").append(lineEnding).toString();
        }
    }

    private RequestParts() {
    }

    /**
     * Splits the value of a {@code Cookie} header into its {@code name=value} pairs.
     */
    public static List<Segment> splitCookies(String headerValue) {
        List<Segment> out = new ArrayList<>();
        if (headerValue == null) {
            return out;
        }
        for (String part : headerValue.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.add(new Segment(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim(), trimmed));
            } else {
                out.add(new Segment(trimmed, "", trimmed));
            }
        }
        return out;
    }

    /**
     * Splits a query string or {@code application/x-www-form-urlencoded} body on {@code &}.
     */
    public static List<Segment> splitPairs(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("&", -1)) {
            if (raw.isEmpty()) {
                continue;
            }
            int eq = raw.indexOf('=');
            if (eq < 0) {
                out.add(new Segment(raw, "", raw));
            } else {
                out.add(new Segment(raw.substring(0, eq), raw.substring(eq + 1), raw));
            }
        }
        return out;
    }

    public static String joinPairs(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(segment.raw());
        }
        return sb.toString();
    }

    /**
     * Heuristic used only when a request has no Content-Type header at all.
     */
    public static boolean looksUrlEncoded(String body) {
        return body != null && !body.isEmpty() && LOOKS_URL_ENCODED.matcher(body).matches();
    }

    /**
     * Extracts the boundary parameter from a {@code multipart/form-data} Content-Type value.
     */
    public static String multipartBoundary(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            return null;
        }
        Matcher m = BOUNDARY.matcher(contentType);
        if (!m.find()) {
            return null;
        }
        String boundary = m.group(1) != null ? m.group(1) : m.group(2);
        return boundary == null || boundary.isEmpty() ? null : boundary;
    }

    /**
     * Parses a {@code multipart/form-data} body. Returns {@code null} unless the body is a
     * well-formed sequence of parts terminated by the closing boundary.
     */
    public static Multipart parseMultipart(String body, String boundary) {
        if (body == null || boundary == null || boundary.isEmpty()) {
            return null;
        }
        final String delimiter = "--" + boundary;
        final int n = body.length();

        List<Segment> parts = new ArrayList<>();
        String lineEnding = null;
        int contentStart = -1; // start of the part currently being read, or -1 before the first delimiter
        int from = 0;

        while (true) {
            int at = body.indexOf(delimiter, from);
            if (at < 0) {
                return null; // ran out of input before the closing boundary
            }
            from = at + delimiter.length();
            if (at != 0 && body.charAt(at - 1) != '\n') {
                continue; // boundary text inside a line is not a delimiter
            }
            int after = at + delimiter.length();
            boolean closing = body.startsWith("--", after);
            int lineBreakAt = closing ? -1 : skipBlanks(body, after, n);
            String thisLineEnding = null;
            if (!closing) {
                if (body.startsWith("\r\n", lineBreakAt)) {
                    thisLineEnding = "\r\n";
                } else if (lineBreakAt < n && body.charAt(lineBreakAt) == '\n') {
                    thisLineEnding = "\n";
                } else {
                    continue; // e.g. a longer boundary that merely starts with ours
                }
            }

            if (contentStart >= 0) {
                int contentEnd = at;
                if (contentEnd - 2 >= contentStart && body.startsWith("\r\n", contentEnd - 2)) {
                    contentEnd -= 2;
                } else if (contentEnd - 1 >= contentStart && body.charAt(contentEnd - 1) == '\n') {
                    contentEnd -= 1;
                }
                parts.add(toPartSegment(body.substring(Math.min(contentStart, contentEnd), contentEnd)));
            }

            if (closing) {
                return new Multipart(boundary, lineEnding == null ? "\r\n" : lineEnding, parts);
            }
            if (lineEnding == null) {
                lineEnding = thisLineEnding;
            }
            contentStart = lineBreakAt + thisLineEnding.length();
            from = contentStart;
        }
    }

    private static int skipBlanks(String s, int idx, int limit) {
        while (idx < limit && (s.charAt(idx) == ' ' || s.charAt(idx) == '\t')) {
            idx++;
        }
        return idx;
    }

    private static Segment toPartSegment(String rawPart) {
        int headerEnd = rawPart.indexOf("\r\n\r\n");
        int separatorLength = 4;
        int bareLf = rawPart.indexOf("\n\n");
        if (headerEnd < 0 || (bareLf >= 0 && bareLf < headerEnd)) {
            headerEnd = bareLf;
            separatorLength = 2;
        }
        String headers = headerEnd < 0 ? rawPart : rawPart.substring(0, headerEnd);
        String content = headerEnd < 0 ? "" : rawPart.substring(headerEnd + separatorLength);

        String name = "";
        String filename = null;
        for (String line : headers.split("\r?\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) {
                String params = line.substring("content-disposition:".length());
                name = firstGroup(DISPOSITION_NAME.matcher(params), name);
                filename = firstGroup(DISPOSITION_FILENAME.matcher(params), null);
                break;
            }
        }
        String value = filename != null
                ? "[file \"" + filename + "\", " + content.length() + " bytes]"
                : content;
        return new Segment(name.isEmpty() ? "(unnamed part)" : name, value, rawPart);
    }

    private static String firstGroup(Matcher m, String fallback) {
        if (!m.find()) {
            return fallback;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
