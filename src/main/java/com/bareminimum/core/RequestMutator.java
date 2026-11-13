package com.bareminimum.core;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.bareminimum.core.JsonBodyHelper.JsonField;
import com.bareminimum.core.RequestParts.Multipart;
import com.bareminimum.core.RequestParts.Segment;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import com.bareminimum.model.RemovableItem.Category;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Extracts candidate {@link RemovableItem}s from an {@link HttpRequest} and builds mutated
 * requests, both while searching for the minimal set and when applying a saved
 * {@link MinimizationProfile}.
 *
 * <p>Extraction and mutation share one parse of the request, and items are addressed by their
 * position within their category, so a request with repeated cookie, header or parameter names is
 * handled exactly. Everything that is kept is copied verbatim from the original request.
 */
public final class RequestMutator {

    /** Longest value kept on a {@link RemovableItem}; longer values are clipped for display. */
    static final int MAX_DISPLAY_VALUE_LENGTH = 4096;

    private static final String COOKIE = "Cookie";
    private static final String CONTENT_TYPE = "Content-Type";

    /** Decides whether a single item of the request is kept. */
    @FunctionalInterface
    public interface ItemFilter {
        boolean keep(Category category, int index, String name);
    }

    private record ParsedBody(Category category, List<Segment> segments, Function<List<Segment>, String> rebuilder) {
        static final ParsedBody NONE = new ParsedBody(Category.BODY_PARAM, List.of(), kept -> "");
    }

    private RequestMutator() {
    }

    /**
     * Extracts all removable items from {@code request} matching the given {@code target}.
     */
    public static List<RemovableItem> extractCandidates(
            HttpRequest request,
            MinimizationTarget target,
            MinimizationConfig config) {
        List<RemovableItem> items = new ArrayList<>();
        if (request == null) {
            return items;
        }

        if (target.includesCookies()) {
            addItems(items, Category.COOKIE, cookieSegments(request));
        }

        if (target.includesHeaders()) {
            int idx = 0;
            for (HttpHeader header : request.headers()) {
                if (isCandidateHeader(header, config)) {
                    items.add(new RemovableItem(Category.HEADER, header.name(), clip(header.value()), idx++));
                }
            }
        }

        if (target.includesParameters()) {
            addItems(items, Category.URL_PARAM, querySegments(request));
            ParsedBody body = parseBody(request);
            addItems(items, body.category(), body.segments());
        }

        return items;
    }

    /**
     * Builds a modified {@link HttpRequest} keeping ONLY the items in {@code keptItems}
     * among the categories targeted by {@code target}. {@code keptItems} must come from
     * {@link #extractCandidates} on the same request with the same target and config.
     */
    public static HttpRequest buildRequestWithSubset(
            HttpRequest original,
            MinimizationTarget target,
            Set<RemovableItem> keptItems,
            MinimizationConfig config) {
        Map<Category, Set<Integer>> keptIndexes = new LinkedHashMap<>();
        for (RemovableItem item : keptItems) {
            keptIndexes.computeIfAbsent(item.category(), c -> new HashSet<>()).add(item.occurrenceIndex());
        }
        return applyFilter(original, target, config, (category, index, name) -> {
            Set<Integer> kept = keptIndexes.get(category);
            return kept != null && kept.contains(index);
        });
    }

    /**
     * Applies a saved {@link MinimizationProfile} (e.g., "GET - ALL 1" or "POST - COOKIES 3")
     * to any {@link HttpRequest}, keeping only the item names that profile found to be required
     * while preserving the target request's own values for them.
     */
    public static HttpRequest applyProfile(
            HttpRequest request,
            MinimizationProfile profile,
            MinimizationConfig config) {
        return applyFilter(request, profile.getTarget(), config, (category, index, name) -> switch (category) {
            case COOKIE -> profile.getRequiredCookies().contains(name);
            case HEADER -> profile.isHeaderRequired(name);
            case URL_PARAM -> profile.getRequiredUrlParams().contains(name);
            case BODY_PARAM, JSON_PARAM -> profile.getRequiredBodyParams().contains(name);
        });
    }

    /**
     * Returns the names of the cookies sent in the request's {@code Cookie} header(s), in order.
     */
    public static List<String> cookieNames(HttpRequest request) {
        return cookieSegments(request).stream().map(Segment::name).toList();
    }

    private static HttpRequest applyFilter(
            HttpRequest request,
            MinimizationTarget target,
            MinimizationConfig config,
            ItemFilter filter) {
        if (request == null) {
            return null;
        }
        // Every decision is made against the unmodified request, so the positions seen here are
        // the same ones extractCandidates() reported.
        HttpRequest mutated = request;
        if (target.includesCookies()) {
            mutated = filterCookies(request, mutated, filter);
        }
        if (target.includesHeaders()) {
            mutated = filterHeaders(request, mutated, config, filter);
        }
        if (target.includesParameters()) {
            mutated = filterQuery(request, mutated, filter);
            mutated = filterBody(request, mutated, filter);
        }
        return mutated;
    }

    // --- Cookies ---------------------------------------------------------------------------

    private static List<Segment> cookieSegments(HttpRequest request) {
        List<Segment> segments = new ArrayList<>();
        for (HttpHeader header : request.headers()) {
            if (COOKIE.equalsIgnoreCase(header.name())) {
                segments.addAll(RequestParts.splitCookies(header.value()));
            }
        }
        return segments;
    }

    private static HttpRequest filterCookies(HttpRequest original, HttpRequest mutated, ItemFilter filter) {
        List<HttpHeader> cookieHeaders = original.headers().stream()
                .filter(h -> COOKIE.equalsIgnoreCase(h.name()))
                .toList();
        if (cookieHeaders.isEmpty()) {
            return mutated;
        }
        List<Segment> all = cookieSegments(original);
        List<Segment> kept = keep(all, Category.COOKIE, filter);
        if (kept.size() == all.size()) {
            return mutated;
        }

        String headerName = cookieHeaders.get(0).name();
        if (kept.isEmpty()) {
            return removeAllNamed(mutated, headerName);
        }
        StringBuilder value = new StringBuilder();
        for (Segment cookie : kept) {
            if (value.length() > 0) {
                value.append("; ");
            }
            value.append(cookie.raw());
        }
        if (cookieHeaders.size() == 1) {
            // Keeps the Cookie header where it was in the request.
            return mutated.withUpdatedHeader(headerName, value.toString());
        }
        return removeAllNamed(mutated, headerName).withAddedHeader(headerName, value.toString());
    }

    // --- Headers ---------------------------------------------------------------------------

    private static boolean isCandidateHeader(HttpHeader header, MinimizationConfig config) {
        String name = header.name();
        return name != null && !COOKIE.equalsIgnoreCase(name) && !config.isHeaderPinned(name);
    }

    private static HttpRequest filterHeaders(
            HttpRequest original,
            HttpRequest mutated,
            MinimizationConfig config,
            ItemFilter filter) {
        // Group candidate headers by name so repeated header names are handled deterministically.
        Map<String, List<HttpHeader>> allByName = new LinkedHashMap<>();
        Map<String, List<HttpHeader>> keptByName = new LinkedHashMap<>();
        int idx = 0;
        for (HttpHeader header : original.headers()) {
            if (!isCandidateHeader(header, config)) {
                continue;
            }
            String key = header.name().toLowerCase(Locale.ROOT);
            allByName.computeIfAbsent(key, k -> new ArrayList<>()).add(header);
            keptByName.computeIfAbsent(key, k -> new ArrayList<>());
            if (filter.keep(Category.HEADER, idx++, header.name())) {
                keptByName.get(key).add(header);
            }
        }

        HttpRequest result = mutated;
        for (Map.Entry<String, List<HttpHeader>> entry : allByName.entrySet()) {
            List<HttpHeader> all = entry.getValue();
            List<HttpHeader> kept = keptByName.get(entry.getKey());
            if (kept.size() == all.size()) {
                continue;
            }
            result = removeAllNamed(result, all.get(0).name());
            for (HttpHeader header : kept) {
                result = result.withAddedHeader(header.name(), header.value());
            }
        }
        return result;
    }

    /**
     * Removes every header with the given name (case-insensitively), whether the underlying
     * {@code withRemovedHeader} removes one occurrence per call or all of them.
     */
    private static HttpRequest removeAllNamed(HttpRequest request, String name) {
        HttpRequest result = request;
        int guard = request.headers().size();
        while (guard-- > 0) {
            String actualName = null;
            for (HttpHeader header : result.headers()) {
                if (name.equalsIgnoreCase(header.name())) {
                    actualName = header.name();
                    break;
                }
            }
            if (actualName == null) {
                break;
            }
            result = result.withRemovedHeader(actualName);
        }
        return result;
    }

    private static String headerValue(HttpRequest request, String name) {
        for (HttpHeader header : request.headers()) {
            if (name.equalsIgnoreCase(header.name())) {
                return header.value();
            }
        }
        return null;
    }

    // --- URL query parameters --------------------------------------------------------------

    private static List<Segment> querySegments(HttpRequest request) {
        String path = request.path();
        int q = path == null ? -1 : path.indexOf('?');
        return q < 0 ? List.of() : RequestParts.splitPairs(path.substring(q + 1));
    }

    private static HttpRequest filterQuery(HttpRequest original, HttpRequest mutated, ItemFilter filter) {
        List<Segment> all = querySegments(original);
        if (all.isEmpty()) {
            return mutated;
        }
        List<Segment> kept = keep(all, Category.URL_PARAM, filter);
        if (kept.size() == all.size()) {
            return mutated;
        }
        String path = original.path();
        String base = path.substring(0, path.indexOf('?'));
        return mutated.withPath(kept.isEmpty() ? base : base + "?" + RequestParts.joinPairs(kept));
    }

    // --- Body parameters (form-urlencoded, multipart, top-level JSON) ----------------------

    private static ParsedBody parseBody(HttpRequest request) {
        String body = request.bodyToString();
        if (body == null || body.isEmpty()) {
            return ParsedBody.NONE;
        }

        // JSON is recognised by content rather than by Content-Type, which is often wrong.
        List<JsonField> jsonFields = JsonBodyHelper.parseTopLevelObject(body);
        if (!jsonFields.isEmpty()) {
            List<Segment> segments = new ArrayList<>(jsonFields.size());
            for (JsonField field : jsonFields) {
                // The segment's raw text is the member exactly as it is written back out.
                segments.add(new Segment(field.name(), field.rawValue(), JsonBodyHelper.renderField(field)));
            }
            return new ParsedBody(Category.JSON_PARAM, segments, kept -> {
                StringBuilder sb = new StringBuilder("{");
                for (int i = 0; i < kept.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(kept.get(i).raw());
                }
                return sb.append('}').toString();
            });
        }

        String contentType = headerValue(request, CONTENT_TYPE);
        String lowerContentType = contentType == null ? null : contentType.toLowerCase(Locale.ROOT);

        if (lowerContentType != null && lowerContentType.contains("multipart/form-data")) {
            Multipart multipart = RequestParts.parseMultipart(body, RequestParts.multipartBoundary(contentType));
            return multipart == null
                    ? ParsedBody.NONE
                    : new ParsedBody(Category.BODY_PARAM, multipart.parts(), multipart::rebuild);
        }

        boolean urlEncoded = lowerContentType != null
                ? lowerContentType.contains("application/x-www-form-urlencoded")
                : RequestParts.looksUrlEncoded(body);
        if (urlEncoded) {
            return new ParsedBody(Category.BODY_PARAM, RequestParts.splitPairs(body), RequestParts::joinPairs);
        }
        return ParsedBody.NONE;
    }

    private static HttpRequest filterBody(HttpRequest original, HttpRequest mutated, ItemFilter filter) {
        ParsedBody body = parseBody(original);
        if (body.segments().isEmpty()) {
            return mutated;
        }
        List<Segment> kept = keep(body.segments(), body.category(), filter);
        if (kept.size() == body.segments().size()) {
            return mutated;
        }
        // withBody() also updates Content-Length to match the new body.
        return mutated.withBody(body.rebuilder().apply(kept));
    }

    // --- Shared helpers --------------------------------------------------------------------

    private static List<Segment> keep(List<Segment> segments, Category category, ItemFilter filter) {
        List<Segment> kept = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (filter.keep(category, i, segment.name())) {
                kept.add(segment);
            }
        }
        return kept;
    }

    private static void addItems(List<RemovableItem> items, Category category, List<Segment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            items.add(new RemovableItem(category, segment.name(), clip(segment.value()), i));
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_DISPLAY_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_DISPLAY_VALUE_LENGTH) + "… (" + value.length() + " chars)";
    }
}
