package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import com.bareminimum.FakeHttp;
import com.bareminimum.FakeHttp.RemoveMode;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import com.bareminimum.model.RemovableItem.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMutatorTest {
    private final MinimizationConfig config = new MinimizationConfig();

    private static Set<RemovableItem> keep(List<RemovableItem> items, Predicate<RemovableItem> predicate) {
        Set<RemovableItem> kept = new LinkedHashSet<>();
        for (RemovableItem item : items) {
            if (predicate.test(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    private HttpRequest subset(HttpRequest request, MinimizationTarget target, Predicate<RemovableItem> predicate) {
        List<RemovableItem> items = RequestMutator.extractCandidates(request, target, config);
        return RequestMutator.buildRequestWithSubset(request, target, keep(items, predicate), config);
    }

    @Test
    void extractsEveryCategoryWithPositions() {
        HttpRequest request = FakeHttp.request("POST", "/api/items?id=7&utm=x", "name=a&name=b",
                "Host: example.com",
                "User-Agent: test",
                "Cookie: sid=abc; theme=dark",
                "Content-Type: application/x-www-form-urlencoded",
                "Content-Length: 13");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.ALL, config);

        assertEquals(List.of(
                new RemovableItem(Category.COOKIE, "sid", "abc", 0),
                new RemovableItem(Category.COOKIE, "theme", "dark", 1),
                // Host and Content-Length are pinned; Cookie is handled as cookies, not as a header.
                new RemovableItem(Category.HEADER, "User-Agent", "test", 0),
                new RemovableItem(Category.HEADER, "Content-Type", "application/x-www-form-urlencoded", 1),
                new RemovableItem(Category.URL_PARAM, "id", "7", 0),
                new RemovableItem(Category.URL_PARAM, "utm", "x", 1),
                new RemovableItem(Category.BODY_PARAM, "name", "a", 0),
                new RemovableItem(Category.BODY_PARAM, "name", "b", 1)
        ), items);

        assertEquals(2, RequestMutator.extractCandidates(request, MinimizationTarget.COOKIES, config).size());
        assertEquals(2, RequestMutator.extractCandidates(request, MinimizationTarget.HEADERS, config).size());
        assertEquals(4, RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config).size());
        assertTrue(RequestMutator.extractCandidates(null, MinimizationTarget.ALL, config).isEmpty());
    }

    @Test
    void keepingEverythingReturnsTheSameRequestUntouched() {
        HttpRequest request = FakeHttp.request("POST", "/p?a=1", "{\"k\": 1}",
                "Host: h", "X-A: 1", "Cookie: c=1", "Content-Type: application/json");
        assertSame(request, subset(request, MinimizationTarget.ALL, item -> true));
    }

    @ParameterizedTest
    @EnumSource(RemoveMode.class)
    void cookieHeaderStaysInPlaceAndKeptCookiesAreVerbatim(RemoveMode mode) {
        HttpRequest request = FakeHttp.request(mode, "GET", "/", "",
                "Host: h", "Cookie: a=1; empty=; flag; b=x=y", "Accept: */*");

        HttpRequest mutated = subset(request, MinimizationTarget.COOKIES,
                item -> !item.name().equals("a"));
        assertEquals(List.of("Host: h", "Cookie: empty=; flag; b=x=y", "Accept: */*"), FakeHttp.headerLines(mutated));

        HttpRequest none = subset(request, MinimizationTarget.COOKIES, item -> false);
        assertEquals(List.of("Host: h", "Accept: */*"), FakeHttp.headerLines(none));
    }

    @ParameterizedTest
    @EnumSource(RemoveMode.class)
    void multipleCookieHeadersAreMergedWhenFiltered(RemoveMode mode) {
        HttpRequest request = FakeHttp.request(mode, "GET", "/", "",
                "Host: h", "Cookie: a=1; b=2", "cookie: c=3", "Accept: */*");

        assertEquals(List.of("a", "b", "c"), RequestMutator.cookieNames(request));

        HttpRequest mutated = subset(request, MinimizationTarget.COOKIES, item -> !item.name().equals("b"));
        assertEquals(List.of("Host: h", "Accept: */*", "Cookie: a=1; c=3"), FakeHttp.headerLines(mutated));

        HttpRequest none = subset(request, MinimizationTarget.COOKIES, item -> false);
        assertEquals(List.of("Host: h", "Accept: */*"), FakeHttp.headerLines(none));
    }

    @ParameterizedTest
    @EnumSource(RemoveMode.class)
    void repeatedHeaderNamesAreRemovedByPosition(RemoveMode mode) {
        HttpRequest request = FakeHttp.request(mode, "GET", "/", "",
                "Host: h", "X-Fwd: first", "Accept: */*", "x-fwd: second", "X-Fwd: third", "Cookie: c=1");

        // Drop only the middle occurrence.
        HttpRequest mutated = subset(request, MinimizationTarget.HEADERS,
                item -> !(item.name().equalsIgnoreCase("X-Fwd") && item.value().equals("second")));
        assertEquals(List.of("Host: h", "Accept: */*", "Cookie: c=1", "X-Fwd: first", "X-Fwd: third"),
                FakeHttp.headerLines(mutated));

        // Drop every occurrence, and everything else that is removable.
        HttpRequest bare = subset(request, MinimizationTarget.HEADERS, item -> false);
        assertEquals(List.of("Host: h", "Cookie: c=1"), FakeHttp.headerLines(bare));
    }

    @Test
    void pinnedHeadersAreNeverCandidatesAndNeverRemoved() {
        MinimizationConfig custom = new MinimizationConfig();
        custom.setPinnedHeadersFromCsv("Authorization, X-Keep");
        HttpRequest request = FakeHttp.request("GET", "/", "",
                "Host: h", "authorization: Bearer t", "X-Keep: 1", "X-Drop: 2", "Content-Length: 0");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.HEADERS, custom);
        assertEquals(List.of(new RemovableItem(Category.HEADER, "X-Drop", "2", 0)), items);

        HttpRequest mutated = RequestMutator.buildRequestWithSubset(request, MinimizationTarget.HEADERS, Set.of(), custom);
        assertEquals(List.of("Host: h", "authorization: Bearer t", "X-Keep: 1", "Content-Length: 0"),
                FakeHttp.headerLines(mutated));
    }

    @Test
    void queryParametersAreRemovedByPositionWithoutReEncoding() {
        HttpRequest request = FakeHttp.request("GET", "/search?q=a%20b&id=1&id=2&flag&x=%26", "", "Host: h");

        HttpRequest mutated = subset(request, MinimizationTarget.PARAMETERS,
                item -> !(item.name().equals("id") && item.occurrenceIndex() == 1) && !item.name().equals("flag"));
        assertEquals("/search?q=a%20b&id=2&x=%26", mutated.path());

        assertEquals("/search", subset(request, MinimizationTarget.PARAMETERS, item -> false).path());
    }

    @Test
    void formBodyParametersAreRemovedAndContentLengthFollows() {
        HttpRequest request = FakeHttp.request("POST", "/login", "user=bob&csrf=t0k%3D&remember=1",
                "Host: h", "Content-Type: application/x-www-form-urlencoded; charset=UTF-8", "Content-Length: 32");

        HttpRequest mutated = subset(request, MinimizationTarget.PARAMETERS, item -> !item.name().equals("remember"));
        assertEquals("user=bob&csrf=t0k%3D", mutated.bodyToString());
        assertTrue(FakeHttp.headerLines(mutated).contains("Content-Length: 20"));
    }

    @Test
    void bodyWithoutContentTypeIsOnlyTreatedAsFormDataWhenItLooksLikeIt() {
        HttpRequest form = FakeHttp.request("POST", "/", "a=1&b=2", "Host: h");
        assertEquals(2, RequestMutator.extractCandidates(form, MinimizationTarget.PARAMETERS, config).size());

        HttpRequest text = FakeHttp.request("POST", "/", "some text = not a form", "Host: h");
        assertTrue(RequestMutator.extractCandidates(text, MinimizationTarget.PARAMETERS, config).isEmpty());

        HttpRequest brokenXml = FakeHttp.request("POST", "/", "<a><b></a>", "Host: h", "Content-Type: text/xml");
        assertTrue(RequestMutator.extractCandidates(brokenXml, MinimizationTarget.PARAMETERS, config).isEmpty());
    }

    @Test
    void jsonFieldsAreRemovedEvenWhenContentTypeIsWrong() {
        HttpRequest request = FakeHttp.request("POST", "/api", "{\"id\": 5, \"meta\": {\"a\": [1, 2]}, \"debug\": false}",
                "Host: h", "Content-Type: application/x-www-form-urlencoded");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config);
        assertEquals(List.of(
                new RemovableItem(Category.JSON_PARAM, "id", "5", 0),
                new RemovableItem(Category.JSON_PARAM, "meta", "{\"a\": [1, 2]}", 1),
                new RemovableItem(Category.JSON_PARAM, "meta.a", "[1, 2]", 2),
                new RemovableItem(Category.JSON_PARAM, "meta.a[0]", "1", 3),
                new RemovableItem(Category.JSON_PARAM, "meta.a[1]", "2", 4),
                new RemovableItem(Category.JSON_PARAM, "debug", "false", 5)
        ), items);

        HttpRequest mutated = subset(request, MinimizationTarget.PARAMETERS, item -> !item.name().equals("debug"));
        assertEquals("{\"id\": 5, \"meta\": {\"a\": [1, 2]}}", mutated.bodyToString());
        assertEquals("{}", subset(request, MinimizationTarget.PARAMETERS, item -> false).bodyToString());
    }

    @Test
    void nestedJsonMembersAreRemovedAndUntouchedContainersStayVerbatim() {
        String body = "{\n  \"user\": {\"name\": \"bob\", \"role\": \"admin\"},\n"
                + "  \"items\": [{\"id\": 1, \"qty\": 2}, {\"id\": 2}],\n"
                + "  \"meta\": {\n    \"trace\": \"x\"\n  }\n}";
        HttpRequest request = FakeHttp.request("POST", "/api", body, "Host: h", "Content-Type: application/json");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config);
        assertEquals(List.of("user", "user.name", "user.role", "items", "items[0]", "items[0].id", "items[0].qty",
                "items[1]", "items[1].id", "meta", "meta.trace"), items.stream().map(RemovableItem::name).toList());

        HttpRequest noRole = subset(request, MinimizationTarget.PARAMETERS, item -> !item.name().equals("user.role"));
        assertEquals("{\"user\": {\"name\": \"bob\"}, \"items\": [{\"id\": 1, \"qty\": 2}, {\"id\": 2}],"
                + " \"meta\": {\n    \"trace\": \"x\"\n  }}", noRole.bodyToString());

        HttpRequest secondItemGone = subset(request, MinimizationTarget.PARAMETERS,
                item -> !item.name().startsWith("items[1]") && !item.name().startsWith("meta"));
        assertEquals("{\"user\": {\"name\": \"bob\", \"role\": \"admin\"}, \"items\": [{\"id\": 1, \"qty\": 2}]}",
                secondItemGone.bodyToString());

        // Removing a container removes what is inside it, whether those items are kept or not.
        HttpRequest noUser = subset(request, MinimizationTarget.PARAMETERS, item -> !item.name().equals("user"));
        assertTrue(noUser.bodyToString().startsWith("{\"items\": "));
        assertFalse(noUser.bodyToString().contains("bob"));
    }

    @Test
    void nestedItemsKnowTheirParentsAndWhatActuallyRemains() {
        HttpRequest request = FakeHttp.request("POST", "/api", "[{\"a\": 1}, {\"b\": {\"c\": 2}}]", "Host: h");
        RequestMutator.Prepared prepared = RequestMutator.prepare(request, MinimizationTarget.PARAMETERS, config);
        List<RemovableItem> items = prepared.candidates();
        assertEquals(List.of("[0]", "[0].a", "[1]", "[1].b", "[1].b.c"), items.stream().map(RemovableItem::name).toList());
        assertTrue(prepared.hasNestedItems());
        assertEquals(items.get(3), prepared.parentOf(items.get(4)));
        assertNull(prepared.parentOf(items.get(0)));

        // Without [1], [1].b and [1].b.c are not in the request even though they are "kept".
        Set<RemovableItem> kept = new LinkedHashSet<>(items);
        kept.remove(items.get(2));
        assertEquals(Set.of(items.get(0), items.get(1)), prepared.effective(kept));
        assertEquals("[{\"a\": 1}]", prepared.build(kept).bodyToString());
    }

    @Test
    void hugeJsonDocumentsOnlyOfferTheFirstItems() {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 1500; i++) {
            body.append(i == 0 ? "" : ",").append(i);
        }
        HttpRequest request = FakeHttp.request("POST", "/", body.append(']').toString(), "Host: h");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config);
        assertEquals(RequestMutator.MAX_STRUCTURED_ITEMS, items.size());
        assertTrue(subset(request, MinimizationTarget.PARAMETERS, item -> false).bodyToString().startsWith("[1000, 1001, "));

        String deep = "{\"k\": ".repeat(40) + "1" + "}".repeat(40);
        HttpRequest deepRequest = FakeHttp.request("POST", "/", deep, "Host: h");
        assertEquals(RequestMutator.MAX_STRUCTURED_DEPTH,
                RequestMutator.extractCandidates(deepRequest, MinimizationTarget.PARAMETERS, config).size());
    }

    @Test
    void xmlElementsAndAttributesAreRemovedFromTheOriginalText() {
        String body = "<?xml version=\"1.0\"?>\n"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "  <soap:Header>\n"
                + "    <Trace id=\"7\">abc</Trace>\n"
                + "  </soap:Header>\n"
                + "  <soap:Body>\n"
                + "    <m:GetUser xmlns:m=\"urn:x\">\n"
                + "      <m:Id>5</m:Id>\n"
                + "      <m:Verbose>true</m:Verbose>\n"
                + "    </m:GetUser>\n"
                + "  </soap:Body>\n"
                + "</soap:Envelope>\n";
        HttpRequest request = FakeHttp.request("POST", "/ws", body, "Host: h", "Content-Type: text/xml; charset=utf-8");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config);
        assertEquals(List.of(
                "soap:Envelope/soap:Header",
                "soap:Envelope/soap:Header/Trace",
                "soap:Envelope/soap:Header/Trace/@id",
                "soap:Envelope/soap:Body",
                "soap:Envelope/soap:Body/m:GetUser",
                "soap:Envelope/soap:Body/m:GetUser/m:Id",
                "soap:Envelope/soap:Body/m:GetUser/m:Verbose"), items.stream().map(RemovableItem::name).toList());
        assertTrue(items.stream().allMatch(item -> item.category() == Category.XML_PARAM));
        assertEquals("5", items.get(5).value());
        assertEquals("7", items.get(2).value());

        HttpRequest mutated = subset(request, MinimizationTarget.PARAMETERS,
                item -> !item.name().endsWith("soap:Header") && !item.name().endsWith("m:Verbose"));
        assertEquals("<?xml version=\"1.0\"?>\n"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "  <soap:Body>\n"
                + "    <m:GetUser xmlns:m=\"urn:x\">\n"
                + "      <m:Id>5</m:Id>\n"
                + "    </m:GetUser>\n"
                + "  </soap:Body>\n"
                + "</soap:Envelope>\n", mutated.bodyToString());
    }

    @Test
    void xmlIsRecognisedWithoutContentTypeButNotInAFormBody() {
        HttpRequest noType = FakeHttp.request("POST", "/", "<order id=\"1\" currency='EUR'><item sku=\"a\"/></order>",
                "Host: h");
        assertEquals(List.of("order/@id", "order/@currency", "order/item", "order/item/@sku"),
                RequestMutator.extractCandidates(noType, MinimizationTarget.PARAMETERS, config)
                        .stream().map(RemovableItem::name).toList());
        assertEquals("<order id=\"1\"><item sku=\"a\"/></order>",
                subset(noType, MinimizationTarget.PARAMETERS, item -> !item.name().endsWith("@currency")).bodyToString());

        HttpRequest form = FakeHttp.request("POST", "/", "<a/>", "Host: h",
                "Content-Type: application/x-www-form-urlencoded");
        assertTrue(RequestMutator.extractCandidates(form, MinimizationTarget.PARAMETERS, config)
                .stream().noneMatch(item -> item.category() == Category.XML_PARAM));
    }

    @Test
    void applyProfileKeepsNestedItemsByPath() {
        MinimizationProfile profile = new MinimizationProfile(
                "POST - PARAMETERS 1", "POST", "/api", MinimizationTarget.PARAMETERS,
                Set.of(), Set.of(), Set.of(), Set.of("auth", "auth.token"), 5, 2, 9, null, null);
        HttpRequest request = FakeHttp.request("POST", "/api",
                "{\"auth\": {\"token\": \"NEW\", \"client\": \"web\"}, \"debug\": true}", "Host: h");

        assertEquals("{\"auth\": {\"token\": \"NEW\"}}", RequestMutator.applyProfile(request, profile, config).bodyToString());
    }

    @Test
    void multipartPartsAreRemovedWhole() {
        String body = "--XX\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n"
                + "--XX\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n\r\ndata\r\n"
                + "--XX--\r\n";
        HttpRequest request = FakeHttp.request("POST", "/upload", body,
                "Host: h", "Content-Type: multipart/form-data; boundary=XX");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.PARAMETERS, config);
        assertEquals(List.of("a", "f"), items.stream().map(RemovableItem::name).toList());

        HttpRequest mutated = subset(request, MinimizationTarget.PARAMETERS, item -> item.name().equals("f"));
        assertEquals("--XX\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n\r\ndata\r\n--XX--\r\n",
                mutated.bodyToString());
    }

    @Test
    void targetLimitsWhatIsTouched() {
        HttpRequest request = FakeHttp.request("GET", "/p?a=1", "", "Host: h", "X-A: 1", "Cookie: c=1");

        HttpRequest cookiesOnly = RequestMutator.buildRequestWithSubset(request, MinimizationTarget.COOKIES, Set.of(), config);
        assertEquals("/p?a=1", cookiesOnly.path());
        assertEquals(List.of("Host: h", "X-A: 1"), FakeHttp.headerLines(cookiesOnly));

        HttpRequest everything = RequestMutator.buildRequestWithSubset(request, MinimizationTarget.ALL, Set.of(), config);
        assertEquals("/p", everything.path());
        assertEquals(List.of("Host: h"), FakeHttp.headerLines(everything));
    }

    @ParameterizedTest
    @EnumSource(RemoveMode.class)
    void applyProfileKeepsRequiredNamesWithTheNewRequestsValues(RemoveMode mode) {
        MinimizationProfile profile = new MinimizationProfile(
                "GET - ALL 1", "GET", "/v1/account", MinimizationTarget.ALL,
                Set.of("auth_sid"), Set.of("X-Api-Key"), Set.of("accountId"), Set.of("amount"),
                10, 4, 12, null, null);

        HttpRequest request = FakeHttp.request(mode, "POST", "/v1/account?accountId=2002&fbclid=zzz",
                "{\"amount\": 10, \"note\": \"hi\"}",
                "Host: api.example.com",
                "Cookie: junk1=abc; auth_sid=NEW_SESSION; junk2=xyz",
                "Sec-Ch-Ua-Platform: \"Windows\"",
                "x-api-key: NEW_KEY",
                "Content-Length: 29");

        HttpRequest minimized = RequestMutator.applyProfile(request, profile, config);

        assertEquals(List.of("Host: api.example.com", "Cookie: auth_sid=NEW_SESSION", "x-api-key: NEW_KEY", "Content-Length: 14"),
                FakeHttp.headerLines(minimized));
        assertEquals("/v1/account?accountId=2002", minimized.path());
        assertEquals("{\"amount\": 10}", minimized.bodyToString());
    }

    @Test
    void applyProfileOnlyTouchesTheCategoriesItWasBuiltFor() {
        MinimizationProfile cookiesOnly = new MinimizationProfile(
                "GET - COOKIES 1", "GET", "/", MinimizationTarget.COOKIES,
                Set.of("sid"), Set.of(), Set.of(), Set.of(), 3, 1, 4, null, null);
        HttpRequest request = FakeHttp.request("GET", "/x?keep=1", "", "Host: h", "X-Other: 1", "Cookie: a=1; sid=2");

        HttpRequest minimized = RequestMutator.applyProfile(request, cookiesOnly, config);

        assertEquals(List.of("Host: h", "X-Other: 1", "Cookie: sid=2"), FakeHttp.headerLines(minimized));
        assertEquals("/x?keep=1", minimized.path());
    }

    @Test
    void veryLongValuesAreClippedForDisplayOnly() {
        String huge = "v".repeat(RequestMutator.MAX_DISPLAY_VALUE_LENGTH + 500);
        HttpRequest request = FakeHttp.request("GET", "/", "", "Host: h", "Cookie: big=" + huge + "; small=1");

        List<RemovableItem> items = RequestMutator.extractCandidates(request, MinimizationTarget.COOKIES, config);
        assertTrue(items.get(0).value().length() < huge.length());

        // The request itself still carries the full value.
        HttpRequest mutated = subset(request, MinimizationTarget.COOKIES, item -> item.name().equals("big"));
        assertEquals(List.of("Host: h", "Cookie: big=" + huge), FakeHttp.headerLines(mutated));
    }
}
