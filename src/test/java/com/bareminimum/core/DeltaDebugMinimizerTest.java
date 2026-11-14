package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.FakeHttp;
import com.bareminimum.core.DeltaDebugMinimizer.MinimizationResult;
import com.bareminimum.core.DeltaDebugMinimizer.ProgressListener;
import com.bareminimum.core.DeltaDebugMinimizer.StepLog;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaDebugMinimizerTest {
    private static final HttpResponse OK = FakeHttp.response(200, "{\"status\": \"ok\", \"orderId\": 999}");
    private static final HttpResponse DENIED = FakeHttp.response(401, "{\"error\": \"unauthorized\"}");

    private MinimizationConfig config;
    private ProfileStore store;
    private DeltaDebugMinimizer minimizer;

    /** Records every callback so tests can assert on the run's observable behaviour. */
    private static final class Recorder implements ProgressListener {
        final List<StepLog> steps = new ArrayList<>();
        final List<MinimizationResult> completions = new ArrayList<>();
        int started;
        int baselines;

        @Override
        public void onStarted(HttpRequest request, MinimizationTarget target, List<RemovableItem> candidates) {
            started++;
        }

        @Override
        public void onBaselineEstablished(short statusCode, int bodyLength) {
            baselines++;
        }

        @Override
        public void onStep(StepLog stepLog) {
            steps.add(stepLog);
        }

        @Override
        public void onCompleted(MinimizationResult result) {
            completions.add(result);
        }
    }

    @BeforeEach
    void setUp() {
        config = new MinimizationConfig();
        store = new ProfileStore();
        minimizer = new DeltaDebugMinimizer(config, store);
    }

    /** A target that answers OK exactly when the request satisfies {@code accepts}. */
    private static Function<HttpRequest, HttpResponse> server(Predicate<HttpRequest> accepts) {
        return request -> accepts.test(request) ? OK : DENIED;
    }

    private static boolean hasCookies(HttpRequest request, String... names) {
        return RequestMutator.cookieNames(request).containsAll(List.of(names));
    }

    private static boolean hasHeader(HttpRequest request, String name) {
        return request.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase(name));
    }

    private static HttpRequest requestWithCookies(int count, int sessionAt, int csrfAt) {
        List<String> cookies = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            if (i == sessionAt) {
                cookies.add("session_id=sec_abc123");
            } else if (i == csrfAt) {
                cookies.add("csrf_token=tok_xyz789");
            } else {
                cookies.add("junk_cookie_" + i + "=val_" + i);
            }
        }
        return FakeHttp.request("POST", "/api/checkout", "{\"item\": 1}",
                "Host: example.com", "Cookie: " + String.join("; ", cookies), "Sec-Ch-Ua: \"Chromium\";v=\"128\"");
    }

    private MinimizationResult run(HttpRequest request, MinimizationTarget target,
                                   Function<HttpRequest, HttpResponse> server, ProgressListener listener) {
        return minimizer.minimize(request, null, target, server, new AtomicBoolean(false), listener);
    }

    @Test
    void findsTwoRequiredCookiesOutOfThirtyTwoInFarFewerRequestsThanOneByOne() {
        HttpRequest request = requestWithCookies(32, 7, 23);
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.COOKIES,
                server(r -> hasCookies(r, "session_id", "csrf_token")), recorder);

        assertNull(result.errorMessage());
        assertFalse(result.cancelled());
        assertNotNull(result.profile());
        assertEquals("POST - COOKIES 1", result.profile().getName());
        assertEquals(Set.of("session_id", "csrf_token"), result.profile().getRequiredCookies());
        assertEquals(List.of("session_id", "csrf_token"), RequestMutator.cookieNames(result.minimizedRequest()));
        assertEquals(32, result.allCandidates().size());
        // One-by-one elimination needs 32 test requests on top of the baseline.
        assertTrue(result.requestsSent() <= 22, "requests sent: " + result.requestsSent());

        assertEquals(1, recorder.started);
        assertEquals(1, recorder.baselines);
        assertEquals(1, recorder.completions.size());
        assertSame(result, recorder.completions.get(0));
        assertSame(store.getActiveProfile(), result.profile());
    }

    @Test
    void nothingRequiredNeedsOnlyBaselineStabilityCheckAndOneTest() {
        HttpRequest request = FakeHttp.request("GET", "/public/info", "",
                "Host: example.com", "Cookie: ga=1; gid=2; fbp=3; utm=4; pref=5; theme=dark");

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, server(r -> true), null);

        assertEquals(0, result.requiredItems().size());
        assertEquals(3, result.requestsSent());
        assertEquals("GET - COOKIES 1", result.profile().getName());
        assertTrue(RequestMutator.cookieNames(result.minimizedRequest()).isEmpty());
    }

    @Test
    void suppliedBaselineAndDisabledStabilityCheckNeedASingleRequest() {
        config.setVerifyBaselineStability(false);
        HttpRequest request = FakeHttp.request("GET", "/public/info", "", "Host: example.com", "Cookie: a=1; b=2");

        MinimizationResult result = minimizer.minimize(
                request, OK, MinimizationTarget.COOKIES, server(r -> true), new AtomicBoolean(false), null);

        assertEquals(1, result.requestsSent());
        assertNotNull(result.profile());
    }

    @Test
    void findRequiredAllHandlesItemsThatAreOnlyRequiredTogether() {
        HttpRequest request = FakeHttp.request("GET", "/v1/account?accountId=1001&utm_source=newsletter&cacheBust=987654", "",
                "Host: api.example.com",
                "User-Agent: Mozilla/5.0",
                "Sec-Fetch-Site: same-origin",
                "Sec-Fetch-Mode: cors",
                "X-Api-Key: secret-key-1",
                "Accept-Language: en-US",
                "Cookie: _ga=111; auth_sid=valid_sid; _gid=222");

        MinimizationResult result = run(request, MinimizationTarget.ALL,
                server(r -> hasCookies(r, "auth_sid") && hasHeader(r, "X-Api-Key") && r.path().contains("accountId=1001")),
                null);

        MinimizationProfile profile = result.profile();
        assertNotNull(profile);
        assertEquals("GET - ALL 1", profile.getName());
        assertEquals(Set.of("auth_sid"), profile.getRequiredCookies());
        assertEquals(Set.of("X-Api-Key"), profile.getRequiredHeaders());
        assertEquals(Set.of("accountId"), profile.getRequiredUrlParams());
        assertEquals(List.of("Host: api.example.com", "X-Api-Key: secret-key-1", "Cookie: auth_sid=valid_sid"),
                FakeHttp.headerLines(result.minimizedRequest()));
        assertEquals("/v1/account?accountId=1001", result.minimizedRequest().path());
    }

    @Test
    void wholeCategoriesAreDroppedInOneStepInAllMode() {
        HttpRequest request = FakeHttp.request("GET", "/x?a=1&b=2&c=3", "",
                "Host: h", "A: 1", "B: 2", "C: 3", "D: 4", "Cookie: sid=1; j1=1; j2=2; j3=3");
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.ALL, server(r -> hasCookies(r, "sid")), recorder);

        assertEquals(Set.of("sid"), result.profile().getRequiredCookies());
        assertTrue(recorder.steps.stream().anyMatch(s -> s.description().equals("Without any headers") && s.matchedBaseline()));
        assertTrue(recorder.steps.stream().anyMatch(s -> s.description().equals("Without any parameters") && s.matchedBaseline()));
        // baseline + stability + empty + 2 category probes + binary search over 4 cookies
        assertTrue(result.requestsSent() <= 10, "requests sent: " + result.requestsSent());
    }

    @Test
    void everyItemRequiredIsReportedAsSuch() {
        HttpRequest request = FakeHttp.request("GET", "/", "", "Host: h", "Cookie: a=1; b=2; c=3");

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, server(r -> hasCookies(r, "a", "b", "c")), null);

        assertEquals(Set.of("a", "b", "c"), result.profile().getRequiredCookies());
        assertSame(request, result.minimizedRequest());
    }

    @Test
    void requestWithNothingToRemoveFailsWithoutSendingAnything() {
        HttpRequest request = FakeHttp.request("GET", "/", "", "Host: h");
        AtomicInteger sent = new AtomicInteger();
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, r -> {
            sent.incrementAndGet();
            return OK;
        }, recorder);

        assertNull(result.profile());
        assertNotNull(result.errorMessage());
        assertEquals(0, sent.get());
        assertEquals(1, recorder.completions.size());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void unstableBaselineIsReportedInsteadOfProducingGarbage() {
        HttpRequest request = requestWithCookies(8, 2, 5);
        AtomicInteger calls = new AtomicInteger();
        // Every response differs wildly in length, so identical requests never match each other.
        Function<HttpRequest, HttpResponse> flappingServer =
                r -> FakeHttp.response(200, "x".repeat(100 * calls.incrementAndGet()));

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, flappingServer, null);

        assertNull(result.profile());
        assertTrue(result.errorMessage().contains("stable"), result.errorMessage());
        assertEquals(2, result.requestsSent());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void sessionExpiringMidRunFailsInsteadOfSavingAWrongProfile() {
        HttpRequest request = requestWithCookies(16, 3, 12);
        AtomicInteger calls = new AtomicInteger();
        Function<HttpRequest, HttpResponse> expiringServer = r ->
                calls.incrementAndGet() <= 6 && hasCookies(r, "session_id", "csrf_token") ? OK : DENIED;

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, expiringServer, null);

        assertNull(result.profile());
        assertNotNull(result.errorMessage());
        assertFalse(result.cancelled());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void reportedResultIsAlwaysOneThatWasObservedToWork() {
        // The server rejects the true minimal request the first time it sees it (a one-off glitch).
        HttpRequest request = requestWithCookies(8, 2, 5);
        AtomicBoolean glitched = new AtomicBoolean();
        Function<HttpRequest, HttpResponse> glitchyServer = r -> {
            List<String> names = RequestMutator.cookieNames(r);
            if (names.equals(List.of("session_id", "csrf_token")) && glitched.compareAndSet(false, true)) {
                return DENIED;
            }
            return hasCookies(r, "session_id", "csrf_token") ? OK : DENIED;
        };
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, glitchyServer, recorder);

        assertNotNull(result.profile());
        assertTrue(result.profile().getRequiredCookies().containsAll(Set.of("session_id", "csrf_token")));
        Set<String> keptNames = Set.copyOf(RequestMutator.cookieNames(result.minimizedRequest()));
        assertTrue(recorder.steps.stream().anyMatch(s -> s.matchedBaseline() && s.keptCount() == keptNames.size()));
    }

    @Test
    void senderExceptionsBecomeAnErrorResultAndCompletionIsStillDelivered() {
        HttpRequest request = requestWithCookies(4, 1, 2);
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, r -> {
            throw new IllegalStateException("connection refused");
        }, recorder);

        assertNull(result.profile());
        assertTrue(result.errorMessage().contains("connection refused"), result.errorMessage());
        assertEquals(1, recorder.completions.size());
    }

    @Test
    void targetGoingSilentAbortsTheRunAfterAFewAttempts() {
        HttpRequest request = requestWithCookies(64, 9, 40);
        AtomicInteger calls = new AtomicInteger();
        // Answers the baseline and the stability check, then stops responding entirely.
        Function<HttpRequest, HttpResponse> dyingServer = r -> calls.incrementAndGet() <= 2 ? OK : null;

        MinimizationResult result = run(request, MinimizationTarget.COOKIES, dyingServer, null);

        assertNull(result.profile());
        assertTrue(result.errorMessage().contains("did not respond"), result.errorMessage());
        // 2 answered + (3 failed tests x 2 attempts each)
        assertEquals(2 + DeltaDebugMinimizer.MAX_CONSECUTIVE_FAILURES * 2, result.requestsSent());
    }

    @Test
    void cancellationStopsTheRunWithoutSavingAProfile() {
        HttpRequest request = requestWithCookies(32, 7, 23);
        AtomicBoolean cancel = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();
        Recorder recorder = new Recorder();

        MinimizationResult result = minimizer.minimize(request, null, MinimizationTarget.COOKIES, r -> {
            if (calls.incrementAndGet() == 5) {
                cancel.set(true);
            }
            return hasCookies(r, "session_id", "csrf_token") ? OK : DENIED;
        }, cancel, recorder);

        assertTrue(result.cancelled());
        assertNull(result.profile());
        assertNull(result.errorMessage());
        assertEquals(5, calls.get());
        assertEquals(1, recorder.completions.size());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void cancelledBeforeStartSendsNothing() {
        AtomicInteger calls = new AtomicInteger();
        MinimizationResult result = minimizer.minimize(requestWithCookies(4, 1, 2), null, MinimizationTarget.COOKIES, r -> {
            calls.incrementAndGet();
            return OK;
        }, new AtomicBoolean(true), null);

        assertTrue(result.cancelled());
        assertEquals(0, calls.get());
    }

    @Test
    void settingsChangedDuringARunDoNotAffectThatRun() {
        HttpRequest request = FakeHttp.request("GET", "/", "", "Host: h", "X-Needed: 1", "X-Junk: 2", "X-Junk2: 3");

        MinimizationResult result = run(request, MinimizationTarget.HEADERS, r -> {
            // Pinning a header mid-run would shift item positions if the run read live settings.
            config.setPinnedHeadersFromCsv("X-Junk");
            return hasHeader(r, "X-Needed") ? OK : DENIED;
        }, null);

        assertEquals(Set.of("X-Needed"), result.profile().getRequiredHeaders());
        assertEquals(List.of("Host: h", "X-Needed: 1"), FakeHttp.headerLines(result.minimizedRequest()));
    }

    @Test
    void stepLogDescribesEachRequestInOrder() {
        HttpRequest request = requestWithCookies(4, 1, 3);
        Recorder recorder = new Recorder();

        MinimizationResult result = run(request, MinimizationTarget.COOKIES,
                server(r -> hasCookies(r, "session_id", "csrf_token")), recorder);

        assertTrue(recorder.steps.get(0).description().startsWith("Stability check"));
        assertTrue(recorder.steps.get(0).matchedBaseline());
        assertEquals("Without any candidate items", recorder.steps.get(1).description());
        assertFalse(recorder.steps.get(1).matchedBaseline());
        assertEquals(401, recorder.steps.get(1).statusCode());
        for (int i = 1; i < recorder.steps.size(); i++) {
            assertTrue(recorder.steps.get(i).stepNumber() > recorder.steps.get(i - 1).stepNumber());
        }
        assertEquals(recorder.steps.get(recorder.steps.size() - 1).stepNumber(), result.requestsSent());
    }

    // --- Nested JSON and XML bodies ---------------------------------------------------------

    /** Records every distinct body sent, to show that no request is wasted on an identical body. */
    private static final class BodyCounter {
        final List<String> bodies = new ArrayList<>();
    }

    @Test
    void findsTheOneNestedJsonMemberThatMatters() {
        HttpRequest request = FakeHttp.request("POST", "/api/order",
                "{\"auth\": {\"token\": \"t0k\", \"client\": \"web\", \"ver\": 3},"
                        + " \"data\": {\"a\": 1, \"b\": [1, 2, 3]}, \"trace\": {\"id\": \"x\", \"ts\": 5}, \"debug\": true}",
                "Host: h", "Content-Type: application/json");
        BodyCounter counter = new BodyCounter();

        MinimizationResult result = run(request, MinimizationTarget.PARAMETERS, r -> {
            counter.bodies.add(r.bodyToString());
            return r.bodyToString().contains("\"token\": \"t0k\"") ? OK : DENIED;
        }, null);

        assertNull(result.errorMessage());
        assertEquals(List.of("auth", "auth.token"), result.requiredItems().stream().map(RemovableItem::name).toList());
        assertEquals("{\"auth\": {\"token\": \"t0k\"}}", result.minimizedRequest().bodyToString());
        assertEquals(Set.of("auth", "auth.token"), result.profile().getRequiredBodyParams());

        // Apart from the deliberate re-sends of the original and the final confirmation, the
        // same body is never sent twice, even though removing a container removes its members.
        long distinct = counter.bodies.stream().distinct().count();
        assertTrue(counter.bodies.size() - distinct <= 2, counter.bodies.toString());
        assertTrue(result.requestsSent() < 15, "requests: " + result.requestsSent());
    }

    @Test
    void findsTheRequiredSoapElements() {
        String body = "<?xml version=\"1.0\"?>\n"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "  <soap:Header><Trace id=\"7\">abc</Trace></soap:Header>\n"
                + "  <soap:Body>\n"
                + "    <m:GetUser xmlns:m=\"urn:x\" locale=\"en\">\n"
                + "      <m:Id>5</m:Id>\n"
                + "      <m:Verbose>true</m:Verbose>\n"
                + "    </m:GetUser>\n"
                + "  </soap:Body>\n"
                + "</soap:Envelope>";
        HttpRequest request = FakeHttp.request("POST", "/ws", body, "Host: h", "Content-Type: text/xml");

        MinimizationResult result = run(request, MinimizationTarget.PARAMETERS, r -> {
            String b = r.bodyToString();
            return b.contains("<soap:Body>") && b.contains("<m:GetUser") && b.contains("<m:Id>5</m:Id>") ? OK : DENIED;
        }, null);

        assertNull(result.errorMessage());
        assertEquals(List.of("soap:Envelope/soap:Body", "soap:Envelope/soap:Body/m:GetUser",
                        "soap:Envelope/soap:Body/m:GetUser/m:Id"),
                result.requiredItems().stream().map(RemovableItem::name).toList());
        assertEquals("<?xml version=\"1.0\"?>\n"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "  <soap:Body>\n"
                + "    <m:GetUser xmlns:m=\"urn:x\">\n"
                + "      <m:Id>5</m:Id>\n"
                + "    </m:GetUser>\n"
                + "  </soap:Body>\n"
                + "</soap:Envelope>", result.minimizedRequest().bodyToString());
    }

    @Test
    void nestedSearchGivesTheSameAnswerAtEveryParallelism() {
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < 12; i++) {
            body.append(i == 0 ? "" : ", ").append("\"g").append(i).append("\": {\"x\": ").append(i * 100)
                    .append(", \"y\": [").append(i * 1000 + 1).append(", ").append(i * 1000 + 2).append("]}");
        }
        HttpRequest request = FakeHttp.request("POST", "/", body.append('}').toString(), "Host: h");
        // Needs g3.x and the first element of g9.y; everything else is junk.
        Predicate<HttpRequest> accepts = r -> r.bodyToString().contains("\"x\": 300")
                && r.bodyToString().contains("[9001");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for (int parallelism : new int[]{1, 2, 4, 8}) {
                config.setParallelism(parallelism);
                MinimizationResult result = minimizer.minimize(request, null, MinimizationTarget.PARAMETERS,
                        r -> accepts.test(r) ? OK : DENIED, new AtomicBoolean(false), null, pool);
                assertNull(result.errorMessage(), "parallelism " + parallelism);
                assertEquals(List.of("g3", "g3.x", "g9", "g9.y", "g9.y[0]"),
                        result.requiredItems().stream().map(RemovableItem::name).toList(), "parallelism " + parallelism);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Burp response analysis -------------------------------------------------------------

    /** A page whose length changes on every request, as with a timestamp or a nonce. */
    private static Function<HttpRequest, HttpResponse> noisyServer(Predicate<HttpRequest> loggedIn) {
        AtomicInteger counter = new AtomicInteger();
        return request -> {
            String nonce = "n".repeat(1 + counter.incrementAndGet() % 7 * 40);
            return loggedIn.test(request)
                    ? FakeHttp.response(200, "<title>Account</title> Welcome back bob nonce=" + nonce)
                    : FakeHttp.response(200, "<title>Sign in</title> Please sign in nonce=" + nonce);
        };
    }

    @Test
    void burpAnalysisHandlesPagesThatChangeOnEveryRequest() {
        HttpRequest request = requestWithCookies(16, 5, 11);
        Predicate<HttpRequest> loggedIn = r -> hasCookies(r, "session_id");

        // The length rule cannot tell this page apart from itself.
        MinimizationResult rules = run(request, MinimizationTarget.COOKIES, noisyServer(loggedIn), null);
        assertNull(rules.profile());
        assertTrue(rules.errorMessage().contains("not give a stable response"), rules.errorMessage());

        config.setResponseMatching(MinimizationConfig.ResponseMatching.BURP_ANALYSIS);
        Recorder recorder = new Recorder();
        MinimizationResult analysis = run(request, MinimizationTarget.COOKIES, noisyServer(loggedIn), recorder);

        assertNull(analysis.errorMessage());
        assertEquals(Set.of("session_id"), analysis.profile().getRequiredCookies());
        assertTrue(recorder.steps.get(0).description().contains("response sample 2 of 3"));
        assertTrue(recorder.steps.get(1).description().contains("response attributes stay the same"),
                recorder.steps.get(1).description());
    }

    @Test
    void burpAnalysisSendsItsSamplesInTheFirstRound() {
        config.setResponseMatching(MinimizationConfig.ResponseMatching.BURP_ANALYSIS);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            MinimizationResult result = minimizer.minimize(requestWithCookies(4, 0, 0), null, MinimizationTarget.COOKIES,
                    r -> {
                        calls.incrementAndGet();
                        return FakeHttp.response(200, "same");
                    }, new AtomicBoolean(false), null, pool);
            // 3 samples + "without any cookies", all in one round; nothing is required.
            assertNull(result.errorMessage());
            assertEquals(4, calls.get());
            assertEquals(1, result.rounds());
            assertTrue(result.requiredItems().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void burpAnalysisStopsWhenTheOriginalRequestChangesStatus() {
        config.setResponseMatching(MinimizationConfig.ResponseMatching.BURP_ANALYSIS);
        AtomicInteger calls = new AtomicInteger();
        MinimizationResult result = run(requestWithCookies(4, 1, 2), MinimizationTarget.COOKIES,
                r -> FakeHttp.response(calls.incrementAndGet() == 2 ? 500 : 200, "x"), null);

        assertNull(result.profile());
        assertTrue(result.errorMessage().contains("different status code"), result.errorMessage());
    }

    @Test
    void burpAnalysisFailureTellsTheUserWhatToChange() {
        config.setResponseMatching(MinimizationConfig.ResponseMatching.BURP_ANALYSIS);
        MinimizationResult result = run(requestWithCookies(4, 1, 2), MinimizationTarget.COOKIES,
                r -> FakeHttp.responseWithoutAnalysis(200, "x"), null);

        assertNull(result.profile());
        assertTrue(result.errorMessage().contains("'Rules'"), result.errorMessage());
    }
}
