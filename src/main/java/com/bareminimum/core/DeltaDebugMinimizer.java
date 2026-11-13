package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import com.bareminimum.model.RemovableItem.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Performs binary-search delta debugging (QuickXplain + category pruning) to find the bare minimum
 * required set of cookies, headers, and/or parameters in O(k log N) HTTP requests.
 *
 * <p>The search is only meaningful while the target keeps answering the same request the same
 * way, so the minimized request is confirmed with a request sent at the very end of the run. If
 * that check fails, the original request is sent again: if it no longer reproduces the baseline
 * (an expired session, a rate limit ...) the run fails without saving anything, otherwise the run
 * falls back to one-by-one elimination.
 */
public final class DeltaDebugMinimizer {

    /** Consecutive unanswered requests after which a run gives up instead of hammering a dead target. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    public record StepLog(
            int stepNumber,
            String description,
            int keptCount,
            int totalCandidates,
            short statusCode,
            int bodyLength,
            boolean matchedBaseline
    ) {
    }

    public record MinimizationResult(
            MinimizationProfile profile,
            HttpRequest originalRequest,
            HttpRequest minimizedRequest,
            List<RemovableItem> allCandidates,
            List<RemovableItem> requiredItems,
            int requestsSent,
            short baselineStatusCode,
            int baselineBodyLength,
            boolean cancelled,
            String errorMessage
    ) {
    }

    public interface ProgressListener {
        default void onStarted(HttpRequest request, MinimizationTarget target, List<RemovableItem> candidates) {
        }

        default void onBaselineEstablished(short statusCode, int bodyLength) {
        }

        default void onStep(StepLog stepLog) {
        }

        /** Called exactly once per run, whether it succeeded, failed or was cancelled. */
        default void onCompleted(MinimizationResult result) {
        }
    }

    private final MinimizationConfig config;
    private final ProfileStore profileStore;

    public DeltaDebugMinimizer(MinimizationConfig config, ProfileStore profileStore) {
        this.config = config;
        this.profileStore = profileStore;
    }

    /**
     * Executes the minimization algorithm synchronously on the caller's thread (call from a
     * background worker, never from the Swing thread). Never throws: failures are reported through
     * {@link MinimizationResult#errorMessage()}.
     *
     * @param originalRequest  The request to minimize
     * @param existingBaseline Optional existing baseline response; if null, sends a baseline request first
     * @param target           Which categories to minimize (COOKIES, HEADERS, PARAMETERS, or ALL)
     * @param requestSender    Function that sends an HttpRequest and returns its HttpResponse (null if none)
     * @param cancelFlag       Allows cleanly aborting an in-progress run
     * @param listener         Receives live progress updates
     */
    public MinimizationResult minimize(
            HttpRequest originalRequest,
            HttpResponse existingBaseline,
            MinimizationTarget target,
            Function<HttpRequest, HttpResponse> requestSender,
            AtomicBoolean cancelFlag,
            ProgressListener listener) {

        ProgressListener safeListener = listener != null ? listener : new ProgressListener() {
        };
        Run run = new Run(originalRequest, target, config.snapshot(), requestSender, cancelFlag, safeListener);

        MinimizationResult result;
        try {
            result = run.execute(existingBaseline);
        } catch (RuntimeException e) {
            result = run.failure("Unexpected error: " + describe(e));
        }
        safeListener.onCompleted(result);
        return result;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** State of a single minimization run. */
    private final class Run {
        private final HttpRequest original;
        private final MinimizationTarget target;
        private final MinimizationConfig cfg;
        private final Function<HttpRequest, HttpResponse> sender;
        private final AtomicBoolean cancelFlag;
        private final ProgressListener listener;

        private final Map<Set<RemovableItem>, Boolean> memo = new HashMap<>();
        private List<RemovableItem> allCandidates = List.of();
        private ResponseComparator comparator;
        private int requestCount;
        private int consecutiveFailures;
        private String lastSendError;
        private boolean lastTestUnanswered;
        private Set<RemovableItem> lastTestedSet;
        private boolean lastTestPassed;
        private String abortReason;

        Run(HttpRequest original,
            MinimizationTarget target,
            MinimizationConfig cfg,
            Function<HttpRequest, HttpResponse> sender,
            AtomicBoolean cancelFlag,
            ProgressListener listener) {
            this.original = original;
            this.target = target;
            this.cfg = cfg;
            this.sender = sender;
            this.cancelFlag = cancelFlag;
            this.listener = listener;
        }

        MinimizationResult execute(HttpResponse existingBaseline) {
            allCandidates = List.copyOf(RequestMutator.extractCandidates(original, target, cfg));
            listener.onStarted(original, target, allCandidates);

            if (allCandidates.isEmpty()) {
                return failure("Nothing to minimize: this request has no removable " + target.itemNoun() + ".");
            }

            // 1. Establish the baseline response.
            HttpResponse baseline = existingBaseline;
            if (baseline == null) {
                if (cancelled()) {
                    return stopped();
                }
                baseline = send(original);
            }
            if (baseline == null) {
                if (cancelled()) {
                    return stopped();
                }
                return failure("No response received for the original request"
                        + (lastSendError == null ? "." : " (" + lastSendError + ")."));
            }
            comparator = new ResponseComparator(baseline, cfg);
            listener.onBaselineEstablished(comparator.getBaselineStatusCode(), comparator.getBaselineBodyLength());

            // 2. Confirm the unmodified request reproduces the baseline. Without this, a response
            //    that varies between identical requests would make every later comparison meaningless.
            Set<RemovableItem> everything = new LinkedHashSet<>(allCandidates);
            if (cfg.isVerifyBaselineStability()) {
                if (!test(everything, "Stability check: original request again")) {
                    if (shouldStop()) {
                        return stopped();
                    }
                    if (lastTestUnanswered) {
                        return failure("No response received when re-sending the original request"
                                + (lastSendError == null ? "." : " (" + lastSendError + ")."));
                    }
                    return failure("The original request does not give a stable response: sending it twice"
                            + " produced responses that differ under the current matching rules."
                            + " Adjust the baseline rules in the BareMinimum settings and try again.");
                }
            } else {
                memo.put(everything, Boolean.TRUE);
            }

            // 3. Fast path: nothing is required at all.
            List<RemovableItem> required;
            if (test(Collections.emptySet(), "Without any candidate items")) {
                required = List.of();
            } else {
                // 4. Whole-category pruning when minimizing everything at once.
                List<RemovableItem> active = target == MinimizationTarget.ALL
                        ? pruneWholeCategories(allCandidates)
                        : allCandidates;

                // 5. QuickXplain binary search (the empty background is already known to fail).
                required = quickXplain(Collections.emptySet(), active, true);

                // 6. Confirm the result with a request sent now, at the end of the run. Earlier
                //    answers are not enough: if the session expired part-way through, every later
                //    test fails and the search would wrongly conclude that everything is required.
                if (!shouldStop() && !confirmStillWorks(new LinkedHashSet<>(required), "Verifying minimized request")) {
                    if (shouldStop()) {
                        return stopped();
                    }
                    if (!confirmStillWorks(everything, "Re-checking original request")) {
                        if (shouldStop()) {
                            return stopped();
                        }
                        return failure("The target stopped answering the original request the way it did at"
                                + " the start of the run (for example the session expired or a rate limit"
                                + " was hit), so the result cannot be trusted. Nothing was saved.");
                    }
                    required = eliminateOneByOne(active);
                }
            }

            if (shouldStop()) {
                return stopped();
            }

            Set<RemovableItem> requiredSet = new HashSet<>(required);
            List<RemovableItem> orderedRequired = allCandidates.stream().filter(requiredSet::contains).toList();
            HttpRequest minimizedRequest = RequestMutator.buildRequestWithSubset(
                    original, target, new LinkedHashSet<>(orderedRequired), cfg);

            MinimizationProfile profile = profileStore.createAndSaveProfile(
                    original, minimizedRequest, target, allCandidates.size(), orderedRequired, requestCount);

            return new MinimizationResult(
                    profile, original, minimizedRequest, allCandidates, orderedRequired, requestCount,
                    comparator.getBaselineStatusCode(), comparator.getBaselineBodyLength(), false, null);
        }

        // --- Result helpers ------------------------------------------------------------------

        MinimizationResult failure(String message) {
            return new MinimizationResult(
                    null, original, original, allCandidates, List.of(), requestCount,
                    comparator == null ? (short) 0 : comparator.getBaselineStatusCode(),
                    comparator == null ? 0 : comparator.getBaselineBodyLength(),
                    false, message);
        }

        /** Result for a run that was cancelled or aborted part-way through. */
        private MinimizationResult stopped() {
            if (abortReason != null && !cancelled()) {
                return failure(abortReason);
            }
            return new MinimizationResult(
                    null, original, original, allCandidates, List.of(), requestCount,
                    comparator == null ? (short) 0 : comparator.getBaselineStatusCode(),
                    comparator == null ? 0 : comparator.getBaselineBodyLength(),
                    true, null);
        }

        private boolean cancelled() {
            return (cancelFlag != null && cancelFlag.get()) || Thread.currentThread().isInterrupted();
        }

        private boolean shouldStop() {
            return abortReason != null || cancelled();
        }

        // --- Sending and testing -------------------------------------------------------------

        private HttpResponse send(HttpRequest request) {
            requestCount++;
            try {
                HttpResponse response = sender.apply(request);
                if (response == null) {
                    lastSendError = "no response";
                }
                return response;
            } catch (RuntimeException e) {
                lastSendError = describe(e);
                return null;
            }
        }

        /**
         * Sends the request that keeps exactly {@code kept} and reports whether its response is
         * equivalent to the baseline. Results are memoized; a stopped run always answers false.
         */
        private boolean test(Set<RemovableItem> kept, String description) {
            if (shouldStop()) {
                return false;
            }
            Boolean cached = memo.get(kept);
            if (cached != null) {
                return cached;
            }
            return sendAndCompare(kept, description);
        }

        /**
         * Like {@link #test} but never answers from memory, unless {@code kept} is exactly what the
         * most recent request already confirmed.
         */
        private boolean confirmStillWorks(Set<RemovableItem> kept, String description) {
            if (shouldStop()) {
                return false;
            }
            if (lastTestPassed && kept.equals(lastTestedSet)) {
                return true;
            }
            return sendAndCompare(kept, description);
        }

        private boolean sendAndCompare(Set<RemovableItem> kept, String description) {
            int delay = cfg.getRequestDelayMs();
            if (delay > 0 && requestCount > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            HttpRequest testRequest = RequestMutator.buildRequestWithSubset(original, target, kept, cfg);
            HttpResponse response = send(testRequest);
            if (response == null && !cancelled()) {
                response = send(testRequest); // one retry so a single network blip cannot skew the result
            }
            int step = requestCount;
            lastTestUnanswered = response == null;

            if (response == null) {
                if (cancelled()) {
                    return false;
                }
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    abortReason = "Stopped: the target did not respond to " + MAX_CONSECUTIVE_FAILURES
                            + " test requests in a row" + (lastSendError == null ? "." : " (" + lastSendError + ").");
                }
            } else {
                consecutiveFailures = 0;
            }

            boolean passed = response != null && comparator.isEquivalent(response);
            Set<RemovableItem> key = new HashSet<>(kept);
            memo.put(key, passed);
            lastTestedSet = key;
            lastTestPassed = passed;

            listener.onStep(new StepLog(
                    step,
                    response == null ? description + " (no response)" : description,
                    kept.size(),
                    allCandidates.size(),
                    response == null ? 0 : response.statusCode(),
                    ResponseComparator.bodyLength(response),
                    passed));
            return passed;
        }

        // --- Search --------------------------------------------------------------------------

        /**
         * In ALL mode, checks whether entire categories (all headers, all parameters, or all
         * cookies) can be dropped in a single step before binary-searching individual items.
         */
        private List<RemovableItem> pruneWholeCategories(List<RemovableItem> candidates) {
            List<RemovableItem> current = candidates;
            // Headers first: they are most often junk (Sec-*, Accept-*, User-Agent ...).
            current = pruneCategory(current, i -> i.category() == Category.HEADER, "Without any headers");
            current = pruneCategory(current, i -> i.category().isParameter(), "Without any parameters");
            current = pruneCategory(current, i -> i.category() == Category.COOKIE, "Without any cookies");
            return current;
        }

        private List<RemovableItem> pruneCategory(
                List<RemovableItem> current,
                Predicate<RemovableItem> inCategory,
                String description) {
            List<RemovableItem> without = current.stream().filter(inCategory.negate()).toList();
            // Dropping everything is the fast path already known to fail, so skip an empty result.
            if (without.size() == current.size() || without.isEmpty()) {
                return current;
            }
            return test(new LinkedHashSet<>(without), description) ? without : current;
        }

        /**
         * QuickXplain: finds a minimal R within {@code candidates} such that
         * test(background + R) passes, given that test(background + candidates) passes.
         *
         * @param background          Items currently held fixed in the request
         * @param candidates          Candidate items being partitioned
         * @param backgroundKnownFail True if test(background) is already known to fail
         */
        private List<RemovableItem> quickXplain(
                Set<RemovableItem> background,
                List<RemovableItem> candidates,
                boolean backgroundKnownFail) {

            if (candidates.isEmpty() || shouldStop()) {
                return Collections.emptyList();
            }
            if (!backgroundKnownFail && test(background, describeProbe(background))) {
                // The background alone succeeds, so nothing in this branch is needed.
                return Collections.emptyList();
            }
            if (candidates.size() == 1) {
                return List.of(candidates.get(0));
            }

            int mid = candidates.size() / 2;
            List<RemovableItem> c1 = candidates.subList(0, mid);
            List<RemovableItem> c2 = candidates.subList(mid, candidates.size());

            // Hold C1 in the background and find what is required from C2.
            Set<RemovableItem> backgroundWithC1 = new LinkedHashSet<>(background);
            backgroundWithC1.addAll(c1);
            List<RemovableItem> r2 = quickXplain(backgroundWithC1, c2, false);

            // Hold R2 in the background and find what is required from C1. If R2 is empty this
            // background equals the one we were called with, which is already known to fail.
            Set<RemovableItem> backgroundWithR2 = new LinkedHashSet<>(background);
            backgroundWithR2.addAll(r2);
            List<RemovableItem> r1 = quickXplain(backgroundWithR2, c1, r2.isEmpty());

            List<RemovableItem> combined = new ArrayList<>(r1.size() + r2.size());
            combined.addAll(r1);
            combined.addAll(r2);
            return combined;
        }

        private String describeProbe(Set<RemovableItem> kept) {
            return "Testing " + kept.size() + " of " + allCandidates.size() + " items";
        }

        /**
         * Fallback: starting from a set known to work, drop items one at a time and keep each
         * removal that still reproduces the baseline. Every state it settles on has been observed
         * to work, so the result is verified by construction.
         */
        private List<RemovableItem> eliminateOneByOne(List<RemovableItem> knownGood) {
            List<RemovableItem> current = new ArrayList<>(knownGood);
            for (RemovableItem item : knownGood) {
                if (shouldStop()) {
                    break;
                }
                List<RemovableItem> trial = new ArrayList<>(current);
                trial.remove(item);
                if (test(new LinkedHashSet<>(trial), "Fallback: without one more item")) {
                    current = trial;
                }
            }
            return current;
        }
    }
}
