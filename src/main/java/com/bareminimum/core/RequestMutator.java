package com.bareminimum.core;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.bareminimum.core.RequestParts.Multipart;
import com.bareminimum.core.RequestParts.Segment;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import com.bareminimum.model.RemovableItem.Category;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Extracts candidate {@link RemovableItem}s from an {@link HttpRequest} and builds mutated
 * requests, both while searching for the minimal set and when applying a saved
 * {@link MinimizationProfile}.
 *
 * <p>A request is parsed once into a {@link Prepared} form, which a minimization run then reuses
 * for every test request it builds. Items are addressed by their position within their category,
 * so a request with repeated cookie, header or parameter names is handled exactly, and everything
 * that is kept is copied verbatim from the original request.
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

    /**
     * Most JSON/XML members a single request offers for removal, and how deep they may be nested.
     * Anything beyond is simply kept as it is, so a huge document cannot turn into thousands of
     * test requests.
     */
    static final int MAX_STRUCTURED_ITEMS = 1000;
    static final int MAX_STRUCTURED_DEPTH = 32;

    /**
     * The removable items of a body. {@code parents[i]} is the index of the item that contains
     * item {@code i} (a JSON container or XML element), or -1; removing a parent also removes
     * everything inside it. {@code rebuilder} writes the body keeping exactly the set items.
     */
    private record ParsedBody(Category category, List<Segment> segments, int[] parents,
                              Function<BitSet, String> rebuilder) {
        static final ParsedBody NONE = new ParsedBody(Category.BODY_PARAM, List.of(), new int[0], kept -> "");

        static ParsedBody flat(Category category, List<Segment> segments, Function<List<Segment>, String> rebuilder) {
            int[] parents = new int[segments.size()];
            Arrays.fill(parents, -1);
            return new ParsedBody(category, List.copyOf(segments), parents, kept -> {
                List<Segment> keptSegments = new ArrayList<>(kept.cardinality());
                for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
                    keptSegments.add(segments.get(i));
                }
                return rebuilder.apply(keptSegments);
            });
        }
    }

    private RequestMutator() {
    }

    /**
     * Parses {@code request} once for the given target and settings. The result is immutable and
     * safe to use from several threads at the same time.
     */
    public static Prepared prepare(HttpRequest request, MinimizationTarget target, MinimizationConfig config) {
        return new Prepared(request, target, config);
    }

    /**
     * Extracts all removable items from {@code request} matching the given {@code target}.
     */
    public static List<RemovableItem> extractCandidates(
            HttpRequest request,
            MinimizationTarget target,
            MinimizationConfig config) {
        if (request == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(prepare(request, target, config).candidates());
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
        if (original == null) {
            return null;
        }
        return prepare(original, target, config).build(keptItems);
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
        if (request == null) {
            return null;
        }
        return prepare(request, profile.getTarget(), config).apply((category, index, name) -> switch (category) {
            case COOKIE -> profile.getRequiredCookies().contains(name);
            case HEADER -> profile.isHeaderRequired(name);
            case URL_PARAM -> profile.getRequiredUrlParams().contains(name);
            case BODY_PARAM, JSON_PARAM, XML_PARAM -> profile.getRequiredBodyParams().contains(name);
        });
    }

    /**
     * Returns the names of the cookies sent in the request's {@code Cookie} header(s), in order.
     */
    public static List<String> cookieNames(HttpRequest request) {
        return cookieSegments(request.headers()).stream().map(Segment::name).toList();
    }

    /**
     * A request parsed for one target and one set of settings.
     */
    public static final class Prepared {
        private final HttpRequest original;
        private final MinimizationTarget target;
        private final List<HttpHeader> headers;
        private final List<HttpHeader> candidateHeaders;
        private final List<HttpHeader> cookieHeaders;
        private final List<Segment> cookies;
        private final String path;
        private final List<Segment> query;
        private final ParsedBody body;
        private final List<RemovableItem> candidates;
        private final Map<RemovableItem, RemovableItem> parents;

        private Prepared(HttpRequest original, MinimizationTarget target, MinimizationConfig config) {
            this.original = original;
            this.target = target;
            this.headers = List.copyOf(original.headers());

            List<HttpHeader> candidateList = new ArrayList<>();
            List<HttpHeader> cookieList = new ArrayList<>();
            for (HttpHeader header : headers) {
                String name = header.name();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (COOKIE.equalsIgnoreCase(name)) {
                    cookieList.add(header);
                } else if (!MinimizationConfig.isAlwaysPinned(name) && !config.isHeaderPinned(name)) {
                    candidateList.add(header);
                }
            }
            this.candidateHeaders = target.includesHeaders() ? List.copyOf(candidateList) : List.of();
            this.cookieHeaders = List.copyOf(cookieList);
            this.cookies = target.includesCookies() ? cookieSegments(cookieList) : List.of();

            String requestPath = target.includesParameters() ? original.path() : null;
            this.path = requestPath;
            this.query = requestPath == null ? List.of() : querySegments(requestPath);
            this.body = target.includesParameters() ? parseBody(original, headers) : ParsedBody.NONE;

            List<RemovableItem> items = new ArrayList<>();
            addItems(items, Category.COOKIE, cookies);
            for (int i = 0; i < candidateHeaders.size(); i++) {
                HttpHeader header = candidateHeaders.get(i);
                items.add(new RemovableItem(Category.HEADER, header.name(), clip(header.value()), i));
            }
            addItems(items, Category.URL_PARAM, query);
            int bodyOffset = items.size();
            addItems(items, body.category(), body.segments());
            this.candidates = Collections.unmodifiableList(items);

            Map<RemovableItem, RemovableItem> parentMap = new HashMap<>();
            for (int i = 0; i < body.parents().length; i++) {
                int parent = body.parents()[i];
                if (parent >= 0) {
                    parentMap.put(items.get(bodyOffset + i), items.get(bodyOffset + parent));
                }
            }
            this.parents = Collections.unmodifiableMap(parentMap);
        }

        /**
         * The item that contains {@code item} (a JSON container or XML element), or null. When
         * the parent is removed, {@code item} is gone from the request whether it is kept or not.
         */
        public RemovableItem parentOf(RemovableItem item) {
            return parents.get(item);
        }

        /** True if some items are nested inside others (a nested JSON or XML body). */
        public boolean hasNestedItems() {
            return !parents.isEmpty();
        }

        /**
         * The items of {@code kept} that actually appear in the request built from it: those
         * whose enclosing items are all kept too. Two sets with the same effective items build
         * the same request.
         */
        public Set<RemovableItem> effective(Set<RemovableItem> kept) {
            if (parents.isEmpty()) {
                return kept;
            }
            Set<RemovableItem> result = new HashSet<>();
            for (RemovableItem item : kept) {
                if (allAncestorsIn(item, kept)) {
                    result.add(item);
                }
            }
            return result;
        }

        /** True if every item enclosing {@code item} is in {@code set}. */
        public boolean allAncestorsIn(RemovableItem item, Set<RemovableItem> set) {
            for (RemovableItem p = parents.get(item); p != null; p = parents.get(p)) {
                if (!set.contains(p)) {
                    return false;
                }
            }
            return true;
        }

        /** True if some item enclosing {@code item} is in {@code set}. */
        public boolean anyAncestorIn(RemovableItem item, Set<RemovableItem> set) {
            for (RemovableItem p = parents.get(item); p != null; p = parents.get(p)) {
                if (set.contains(p)) {
                    return true;
                }
            }
            return false;
        }

        public HttpRequest original() {
            return original;
        }

        public MinimizationTarget target() {
            return target;
        }

        /** The removable items of the request, in a stable order. */
        public List<RemovableItem> candidates() {
            return candidates;
        }

        /** Builds the request keeping exactly {@code keptItems} (and everything not removable). */
        public HttpRequest build(Set<RemovableItem> keptItems) {
            Map<Category, Set<Integer>> keptIndexes = new LinkedHashMap<>();
            for (RemovableItem item : keptItems) {
                keptIndexes.computeIfAbsent(item.category(), c -> new HashSet<>()).add(item.occurrenceIndex());
            }
            return apply((category, index, name) -> {
                Set<Integer> kept = keptIndexes.get(category);
                return kept != null && kept.contains(index);
            });
        }

        /** Builds the request keeping exactly the items {@code filter} accepts. */
        public HttpRequest apply(ItemFilter filter) {
            HttpRequest mutated = original;
            if (target.includesCookies()) {
                mutated = filterCookies(mutated, filter);
            }
            if (target.includesHeaders()) {
                mutated = filterHeaders(mutated, filter);
            }
            if (target.includesParameters()) {
                mutated = filterQuery(mutated, filter);
                mutated = filterBody(mutated, filter);
            }
            return mutated;
        }

        private HttpRequest filterCookies(HttpRequest mutated, ItemFilter filter) {
            if (cookieHeaders.isEmpty()) {
                return mutated;
            }
            List<Segment> kept = keep(cookies, Category.COOKIE, filter);
            if (kept.size() == cookies.size()) {
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

        private HttpRequest filterHeaders(HttpRequest mutated, ItemFilter filter) {
            // Group candidate headers by name so repeated header names are handled deterministically.
            Map<String, List<HttpHeader>> allByName = new LinkedHashMap<>();
            Map<String, List<HttpHeader>> keptByName = new LinkedHashMap<>();
            for (int i = 0; i < candidateHeaders.size(); i++) {
                HttpHeader header = candidateHeaders.get(i);
                String key = header.name().toLowerCase(Locale.ROOT);
                allByName.computeIfAbsent(key, k -> new ArrayList<>()).add(header);
                List<HttpHeader> keptForName = keptByName.computeIfAbsent(key, k -> new ArrayList<>());
                if (filter.keep(Category.HEADER, i, header.name())) {
                    keptForName.add(header);
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

        private HttpRequest filterQuery(HttpRequest mutated, ItemFilter filter) {
            if (query.isEmpty()) {
                return mutated;
            }
            List<Segment> kept = keep(query, Category.URL_PARAM, filter);
            if (kept.size() == query.size()) {
                return mutated;
            }
            String base = path.substring(0, path.indexOf('?'));
            return mutated.withPath(kept.isEmpty() ? base : base + "?" + RequestParts.joinPairs(kept));
        }

        private HttpRequest filterBody(HttpRequest mutated, ItemFilter filter) {
            List<Segment> segments = body.segments();
            if (segments.isEmpty()) {
                return mutated;
            }
            BitSet kept = new BitSet(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                if (filter.keep(body.category(), i, segments.get(i).name())) {
                    kept.set(i);
                }
            }
            if (kept.cardinality() == segments.size()) {
                return mutated;
            }
            // withBody() also updates Content-Length to match the new body.
            return mutated.withBody(body.rebuilder().apply(kept));
        }
    }

    // --- Parsing ---------------------------------------------------------------------------

    private static List<Segment> cookieSegments(List<HttpHeader> headers) {
        List<Segment> segments = new ArrayList<>();
        for (HttpHeader header : headers) {
            if (COOKIE.equalsIgnoreCase(header.name())) {
                segments.addAll(RequestParts.splitCookies(header.value()));
            }
        }
        return List.copyOf(segments);
    }

    private static List<Segment> querySegments(String path) {
        int q = path.indexOf('?');
        return q < 0 ? List.of() : List.copyOf(RequestParts.splitPairs(path.substring(q + 1)));
    }

    private static ParsedBody parseBody(HttpRequest request, List<HttpHeader> headers) {
        String body = request.bodyToString();
        if (body == null || body.isEmpty()) {
            return ParsedBody.NONE;
        }

        // JSON is recognised by content rather than by Content-Type, which is often wrong.
        JsonBodyHelper.Tree json = JsonBodyHelper.parseTree(body);
        if (json != null) {
            List<JsonBodyHelper.Node> nodes = json.nodes();
            return structured(Category.JSON_PARAM, nodes.size(),
                    k -> nodes.get(k).path, k -> clip(body, nodes.get(k).valueStart, nodes.get(k).valueEnd()),
                    k -> nodes.get(k).parent, k -> nodes.get(k).depth, json::render);
        }

        String contentType = headerValue(headers, CONTENT_TYPE);
        String lowerContentType = contentType == null ? null : contentType.toLowerCase(Locale.ROOT);

        if (lowerContentType != null && lowerContentType.contains("multipart/form-data")) {
            Multipart multipart = RequestParts.parseMultipart(body, RequestParts.multipartBoundary(contentType));
            return multipart == null
                    ? ParsedBody.NONE
                    : ParsedBody.flat(Category.BODY_PARAM, multipart.parts(), multipart::rebuild);
        }

        boolean declaredForm = lowerContentType != null && lowerContentType.contains("application/x-www-form-urlencoded");
        if (!declaredForm && (lowerContentType != null && lowerContentType.contains("xml") || looksLikeXml(body))) {
            XmlBodyHelper.Tree xml = XmlBodyHelper.parse(body);
            if (xml != null) {
                List<XmlBodyHelper.Node> nodes = xml.nodes();
                return structured(Category.XML_PARAM, nodes.size(),
                        k -> nodes.get(k).path, k -> clip(body, nodes.get(k).valueStart(), nodes.get(k).valueEnd()),
                        k -> nodes.get(k).parent, k -> nodes.get(k).depth, xml::render);
            }
        }

        boolean urlEncoded = lowerContentType != null ? declaredForm : RequestParts.looksUrlEncoded(body);
        if (urlEncoded) {
            return ParsedBody.flat(Category.BODY_PARAM, RequestParts.splitPairs(body), RequestParts::joinPairs);
        }
        return ParsedBody.NONE;
    }

    private static boolean looksLikeXml(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '﻿') {
                return c == '<';
            }
        }
        return false;
    }

    /**
     * Turns the nodes of a JSON or XML tree into removable items. Nodes deeper than
     * {@link #MAX_STRUCTURED_DEPTH}, or beyond the first {@link #MAX_STRUCTURED_ITEMS} in document
     * order, are not offered for removal and always stay. An offered node's enclosing nodes are
     * always offered too (they are shallower and come earlier), so parents map onto items.
     */
    private static ParsedBody structured(Category category, int nodeCount,
                                         IntFunction<String> name, IntFunction<String> value,
                                         IntUnaryOperator parent, IntUnaryOperator depth,
                                         Function<boolean[], String> renderer) {
        int[] itemOfNode = new int[nodeCount];
        int[] nodeOfItem = new int[Math.min(nodeCount, MAX_STRUCTURED_ITEMS)];
        List<Segment> segments = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        for (int k = 0; k < nodeCount; k++) {
            itemOfNode[k] = -1;
            if (segments.size() >= MAX_STRUCTURED_ITEMS || depth.applyAsInt(k) > MAX_STRUCTURED_DEPTH) {
                continue;
            }
            int parentNode = parent.applyAsInt(k);
            nodeOfItem[segments.size()] = k;
            itemOfNode[k] = segments.size();
            parents.add(parentNode < 0 ? -1 : itemOfNode[parentNode]);
            segments.add(new Segment(name.apply(k), value.apply(k), null));
        }
        int itemCount = segments.size();
        int[] parentArray = parents.stream().mapToInt(Integer::intValue).toArray();
        return new ParsedBody(category, List.copyOf(segments), parentArray, kept -> {
            boolean[] removed = new boolean[nodeCount];
            for (int i = 0; i < itemCount; i++) {
                removed[nodeOfItem[i]] = !kept.get(i);
            }
            return renderer.apply(removed);
        });
    }

    private static String headerValue(List<HttpHeader> headers, String name) {
        for (HttpHeader header : headers) {
            if (name.equalsIgnoreCase(header.name())) {
                return header.value();
            }
        }
        return null;
    }

    // --- Shared helpers --------------------------------------------------------------------

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

    /** Display value for {@code text[start, end)}, clipped without copying the whole range. */
    private static String clip(String text, int start, int end) {
        int length = end - start;
        return length <= MAX_DISPLAY_VALUE_LENGTH
                ? text.substring(start, end)
                : text.substring(start, start + MAX_DISPLAY_VALUE_LENGTH) + "… (" + length + " chars)";
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
