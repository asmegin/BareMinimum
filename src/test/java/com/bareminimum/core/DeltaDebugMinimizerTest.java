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
}
