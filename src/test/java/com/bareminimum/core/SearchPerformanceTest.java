package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.FakeHttp;
import com.bareminimum.core.DeltaDebugMinimizer.MinimizationResult;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down how many requests and rounds the search needs, compared with removing items one at
 * a time (what Request Minimizer does: two baseline requests plus one per item), and checks the
 * parallel machinery. Also writes the comparison table to build/reports/search-benchmark.txt.
 */
class SearchPerformanceTest {
    private static final HttpResponse OK = FakeHttp.response(200, "ok-body-ok-body-ok-body-ok-body-ok-body-ok-body");
    private static final HttpResponse DENIED = FakeHttp.response(401, "no");
    private static final int TRIALS = 20;

    private static ExecutorService pool;

    @BeforeAll
    static void startPool() {
        pool = Executors.newFixedThreadPool(MinimizationConfig.MAX_PARALLELISM);
    }

    @AfterAll
    static void stopPool() {
        pool.shutdownNow();
    }

    /** A request with {@code n} cookies, {@code d} of them (chosen at random) required. */
    private record Scenario(HttpRequest request, Set<String> required) {
        static Scenario random(int n, int d, long seed) {
            Random random = new Random(seed);
            List<String> cookies = new ArrayList<>();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                cookies.add("c" + i + "=v" + i);
                order.add(i);
            }
            Collections.shuffle(order, random);
            Set<String> required = new HashSet<>();
            for (int i = 0; i < d; i++) {
                required.add("c" + order.get(i));
            }
            return new Scenario(
                    FakeHttp.request("GET", "/", "", "Host: h", "Cookie: " + String.join("; ", cookies)),
                    required);
        }
    }

    private static MinimizationResult run(HttpRequest request, Predicate<HttpRequest> accepts, int parallelism) {
        MinimizationConfig config = new MinimizationConfig();
        config.setParallelism(parallelism);
        return new DeltaDebugMinimizer(config, new ProfileStore()).minimize(
                request, null, MinimizationTarget.COOKIES, r -> accepts.test(r) ? OK : DENIED,
                new AtomicBoolean(), null, parallelism == 1 ? null : pool);
    }

    private static MinimizationResult run(Scenario s, int parallelism) {
        MinimizationResult result = run(s.request(), r -> RequestMutator.cookieNames(r).containsAll(s.required()), parallelism);
        assertNull(result.errorMessage());
        assertEquals(s.required(), new HashSet<>(result.requiredItems().stream().map(i -> i.name()).toList()),
                "wrong result for " + s.required().size() + " of " + s.request());
        return result;
    }

    private static int log2Ceil(int n) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1));
    }

    @Test
    void neverMuchWorseThanOneByOneAndFindsExactlyTheRequiredItems() {
        for (int n : new int[]{1, 2, 3, 5, 10, 20, 40, 80, 150}) {
            for (int d = 0; d <= n; d += Math.max(1, n / 8)) {
                for (int t = 0; t < 5; t++) {
                    Scenario s = Scenario.random(n, d, 7919L * n + 31L * d + t);
                    for (int parallelism : new int[]{1, 4}) {
                        MinimizationResult result = run(s, parallelism);
                        int oneByOne = n + 2;
                        int bound = oneByOne + 2 * log2Ceil(n) + 4;
                        assertTrue(result.requestsSent() <= bound, String.format(
                                "n=%d d=%d P=%d: %d requests, bound %d", n, d, parallelism, result.requestsSent(), bound));
                    }
                }
            }
        }
    }

    @Test
    void fewRequiredItemsAmongManyNeedAFractionOfTheRequests() {
        double requests = 0;
        double rounds = 0;
        for (int t = 0; t < TRIALS; t++) {
            Scenario s = Scenario.random(150, 2, 104729L + t);
            requests += run(s, 1).requestsSent();
            rounds += run(s, 4).rounds();
        }
        // One-by-one needs 152 requests and 152 sequential round trips.
        assertTrue(requests / TRIALS < 25, "average requests " + requests / TRIALS);
        assertTrue(rounds / TRIALS < 10, "average rounds " + rounds / TRIALS);
    }

    @Test
    void parallelRoundsCutWaitingTimeWithoutMuchExtraTraffic() {
        double sequentialRequests = 0;
        double sequentialRounds = 0;
        double parallelRequests = 0;
        double parallelRounds = 0;
        for (int d : new int[]{1, 4, 10, 20, 40}) {
            for (int t = 0; t < TRIALS; t++) {
                Scenario s = Scenario.random(40, d, 15485863L + 97L * d + t);
                MinimizationResult one = run(s, 1);
                MinimizationResult four = run(s, 4);
                sequentialRequests += one.requestsSent();
                sequentialRounds += one.rounds();
                parallelRequests += four.requestsSent();
                parallelRounds += four.rounds();
            }
        }
        assertTrue(parallelRounds < 0.45 * sequentialRounds,
                "rounds: parallel " + parallelRounds + " vs sequential " + sequentialRounds);
        assertTrue(parallelRequests < 1.15 * sequentialRequests,
                "requests: parallel " + parallelRequests + " vs sequential " + sequentialRequests);
    }

    @Test
    void nothingRequiredFinishesInOneRoundWhenParallel() {
        Scenario s = Scenario.random(30, 0, 1);
        MinimizationResult result = run(s, 4);
        assertEquals(3, result.requestsSent());
        assertEquals(1, result.rounds());
    }

    @Test
    void eitherOfTwoAlternativesIsHandledAndTheResultIsMinimal() {
        // The server needs "auth" and at least one of "sid_a" / "sid_b".
        List<String> cookies = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            cookies.add(i == 5 ? "sid_a=1" : i == 17 ? "sid_b=2" : i == 11 ? "auth=x" : "junk" + i + "=j");
        }
        HttpRequest request = FakeHttp.request("GET", "/", "", "Host: h", "Cookie: " + String.join("; ", cookies));
        Predicate<HttpRequest> accepts = r -> {
            List<String> names = RequestMutator.cookieNames(r);
            return names.contains("auth") && (names.contains("sid_a") || names.contains("sid_b"));
        };

        for (int parallelism : new int[]{1, 2, 4, 8}) {
            MinimizationResult result = run(request, accepts, parallelism);
            assertNull(result.errorMessage(), "P=" + parallelism);
            List<String> kept = RequestMutator.cookieNames(result.minimizedRequest());
            assertEquals(2, kept.size(), "P=" + parallelism + " kept " + kept);
            assertTrue(accepts.test(result.minimizedRequest()), "P=" + parallelism + " kept " + kept);
        }
    }

    @Test
    void parallelRunsNeverExceedTheConfiguredConcurrency() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Scenario s = Scenario.random(60, 6, 99);
        Function<HttpRequest, HttpResponse> slowServer = r -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return RequestMutator.cookieNames(r).containsAll(s.required()) ? OK : DENIED;
        };

        MinimizationConfig config = new MinimizationConfig();
        config.setParallelism(4);
        MinimizationResult result = new DeltaDebugMinimizer(config, new ProfileStore()).minimize(
                s.request(), null, MinimizationTarget.COOKIES, slowServer, new AtomicBoolean(), null, pool);

        assertNull(result.errorMessage());
        assertEquals(s.required().size(), result.requiredItems().size());
        assertTrue(maxInFlight.get() > 1, "requests were never sent in parallel");
        assertTrue(maxInFlight.get() <= 4, "max in flight " + maxInFlight.get());
    }

    @Test
    void aDelayBetweenRequestsForcesOneAtATime() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Scenario s = Scenario.random(12, 2, 5);
        MinimizationConfig config = new MinimizationConfig();
        config.setParallelism(8);
        config.setRequestDelayMs(1);

        MinimizationResult result = new DeltaDebugMinimizer(config, new ProfileStore()).minimize(
                s.request(), null, MinimizationTarget.COOKIES, r -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    inFlight.decrementAndGet();
                    return RequestMutator.cookieNames(r).containsAll(s.required()) ? OK : DENIED;
                }, new AtomicBoolean(), null, pool);

        assertNull(result.errorMessage());
        assertEquals(1, maxInFlight.get());
        assertEquals(result.requestsSent(), result.rounds());
    }

    @Test
    void cancellingAParallelRunStopsPromptly() {
        Scenario s = Scenario.random(120, 10, 3);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger sentAfterCancel = new AtomicInteger();
        MinimizationConfig config = new MinimizationConfig();
        config.setParallelism(4);

        MinimizationResult result = new DeltaDebugMinimizer(config, new ProfileStore()).minimize(
                s.request(), null, MinimizationTarget.COOKIES, r -> {
                    if (cancel.get()) {
                        sentAfterCancel.incrementAndGet();
                    }
                    if (sent.incrementAndGet() == 10) {
                        cancel.set(true);
                    }
                    return RequestMutator.cookieNames(r).containsAll(s.required()) ? OK : DENIED;
                }, cancel, null, pool);

        assertTrue(result.cancelled());
        assertNull(result.profile());
        // Only requests already handed to the pool in the same round may still go out.
        assertTrue(sentAfterCancel.get() < 4, "sent after cancel: " + sentAfterCancel.get());
    }

    @Test
    void findRequiredAllGivesTheSameAnswerAtEveryParallelism() {
        HttpRequest request = FakeHttp.request("POST", "/v1/orders?accountId=1001&utm_source=mail&cb=987",
                "{\"item\": 7, \"qty\": 1, \"tracking\": \"x\", \"debug\": false}",
                "Host: api.example.com",
                "User-Agent: Mozilla/5.0",
                "Sec-Fetch-Site: same-origin",
                "Sec-Fetch-Mode: cors",
                "X-Api-Key: secret",
                "X-Csrf: t1",
                "Accept-Language: en-US",
                "Content-Type: application/json",
                "Cookie: _ga=1; auth_sid=valid; _gid=2; csrf=t1; pref=dark");
        Predicate<HttpRequest> accepts = r -> {
            List<String> cookies = RequestMutator.cookieNames(r);
            boolean headers = r.headers().stream().anyMatch(h -> h.name().equals("X-Api-Key"))
                    && r.headers().stream().anyMatch(h -> h.name().equals("X-Csrf"));
            return cookies.contains("auth_sid") && cookies.contains("csrf") && headers
                    && r.path().contains("accountId=1001") && r.bodyToString().contains("\"item\"");
        };

        for (int parallelism : new int[]{1, 2, 4, 8, 16}) {
            MinimizationConfig config = new MinimizationConfig();
            config.setParallelism(parallelism);
            MinimizationResult result = new DeltaDebugMinimizer(config, new ProfileStore()).minimize(
                    request, null, MinimizationTarget.ALL, r -> accepts.test(r) ? OK : DENIED,
                    new AtomicBoolean(), null, parallelism == 1 ? null : pool);

            assertNull(result.errorMessage(), "P=" + parallelism);
            assertEquals(List.of("auth_sid", "csrf", "X-Api-Key", "X-Csrf", "accountId", "item"),
                    result.requiredItems().stream().map(i -> i.name()).toList(), "P=" + parallelism);
            assertEquals("/v1/orders?accountId=1001", result.minimizedRequest().path());
            assertEquals("{\"item\": 7}", result.minimizedRequest().bodyToString());
        }
    }

    @Test
    void writesComparisonTable() throws IOException {
        StringBuilder table = new StringBuilder();
        table.append("Simulated: n cookies, d of them required, averaged over ").append(TRIALS).append(" random layouts.\n");
        table.append("One-by-one = 2 baseline requests + 1 per item, one after another (Request Minimizer's approach).\n\n");
        table.append(String.format("%5s %5s | %18s | %18s | %10s%n", "n", "d", "1 at a time", "4 in parallel", "one-by-one"));
        table.append(String.format("%5s %5s | %8s %9s | %8s %9s | %10s%n", "", "", "requests", "rounds", "requests", "rounds", "req=rounds"));
        for (int n : new int[]{10, 20, 40, 80, 150}) {
            for (int d : new java.util.TreeSet<>(List.of(0, 1, 2, 4, 8, n / 4, n / 2, n))) {
                if (d > n) {
                    continue;
                }
                double[] requests = new double[2];
                double[] rounds = new double[2];
                for (int t = 0; t < TRIALS; t++) {
                    Scenario s = Scenario.random(n, d, 1000L * n + 31L * d + t);
                    MinimizationResult one = run(s, 1);
                    MinimizationResult four = run(s, 4);
                    requests[0] += one.requestsSent();
                    rounds[0] += one.rounds();
                    requests[1] += four.requestsSent();
                    rounds[1] += four.rounds();
                }
                table.append(String.format("%5d %5d | %8.1f %9.1f | %8.1f %9.1f | %10d%n", n, d,
                        requests[0] / TRIALS, rounds[0] / TRIALS, requests[1] / TRIALS, rounds[1] / TRIALS, n + 2));
            }
        }
        Path out = Path.of("build", "reports", "search-benchmark.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.toString());
    }
}
