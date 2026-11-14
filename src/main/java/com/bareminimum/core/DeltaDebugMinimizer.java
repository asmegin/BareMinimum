package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.core.RequestMutator.Prepared;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationConfig.ResponseMatching;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;
import com.bareminimum.model.RemovableItem.Category;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Finds the bare minimum set of cookies, headers and/or parameters a request needs.
 *
 * <h2>Search</h2>
 * Every test request removes one group of items from the request and asks a single question: is
 * anything in this group required? If the response still matches the baseline, the whole group is
 * dropped for good; if not, the group is known to hold at least one required item and is split in
 * half. This is adaptive group testing (Hwang's generalised binary splitting): the size of the
 * groups it tries is chosen from how dense the required items have turned out to be so far, so it
 * needs very few requests when most items are junk and falls back to roughly one request per item
 * when most items are required, which is the cost of removing items one at a time.
 *
 * <h2>Parallelism</h2>
 * Under that model, tests of different groups do not depend on each other, so each round sends
 * several of them at once (up to the configured parallelism P). A group known to hold a required
 * item is split into P + 1 parts, P of which are tested together while the last is inferred, so a
 * round narrows it down by a factor of P + 1 for about the same number of requests as halving.
 * Spare capacity goes to untested groups. The first round also sends the baseline, the stability
 * check and the "remove everything" test together.
 *
 * <h2>Verification</h2>
 * The search is only meaningful while the target keeps answering the same request the same way,
 * so the minimized request is confirmed with a request sent at the very end of the run. If that
 * check fails, the original request is sent again: if it no longer reproduces the baseline (an
 * expired session, a rate limit ...) the run fails without saving anything, otherwise the run
 * falls back to one-by-one elimination.
 */
public final class DeltaDebugMinimizer {

    /** Consecutive unanswered requests after which a run gives up instead of hammering a dead target. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Responses to the unmodified request that Burp's response analysis learns from. */
    public static final int ANALYSIS_SAMPLES = 3;

    public record StepLog(
            int stepNumber,
            int round,
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
            int rounds,
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
     * Runs a minimization sending one request at a time. See
     * {@link #minimize(HttpRequest, HttpResponse, MinimizationTarget, Function, AtomicBoolean, ProgressListener, ExecutorService)}.
     */
    public MinimizationResult minimize(
            HttpRequest originalRequest,
            HttpResponse existingBaseline,
            MinimizationTarget target,
            Function<HttpRequest, HttpResponse> requestSender,
            AtomicBoolean cancelFlag,
            ProgressListener listener) {
        return minimize(originalRequest, existingBaseline, target, requestSender, cancelFlag, listener, null);
    }

    /**
     * Executes the minimization synchronously on the caller's thread (call from a background
     * worker, never from the Swing thread). Never throws: failures are reported through
     * {@link MinimizationResult#errorMessage()}.
     *
     * @param originalRequest  The request to minimize
     * @param existingBaseline Optional existing baseline response; if null, sends a baseline request first
     * @param target           Which categories to minimize (COOKIES, HEADERS, PARAMETERS, or ALL)
     * @param requestSender    Sends a request and returns its response (null if none); must be thread-safe
     *                         when {@code requestExecutor} is given
     * @param cancelFlag       Allows cleanly aborting an in-progress run
     * @param listener         Receives live progress updates
     * @param requestExecutor  Threads used to send several test requests at once, or null to send
     *                         them one at a time
     */
    public MinimizationResult minimize(
            HttpRequest originalRequest,
            HttpResponse existingBaseline,
            MinimizationTarget target,
            Function<HttpRequest, HttpResponse> requestSender,
            AtomicBoolean cancelFlag,
            ProgressListener listener,
            ExecutorService requestExecutor) {

        ProgressListener safeListener = listener != null ? listener : new ProgressListener() {
        };
        Run run = new Run(originalRequest, target, config.snapshot(), requestSender, cancelFlag,
                safeListener, requestExecutor);

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

    /** Outcome of sending one test request (with its retry). {@code attempts == 0} means not sent. */
    private record Sent(HttpResponse response, int attempts, String error) {
        static final Sent NOT_SENT = new Sent(null, 0, null);
    }

    /** One test: send the request that keeps exactly {@code kept}. */
    private static final class Check {
        final Set<RemovableItem> kept;
        final String description;
        final boolean bypassMemo;
        boolean passed;

        Check(Set<RemovableItem> kept, String description, boolean bypassMemo) {
            this.kept = kept;
            this.description = description;
            this.bypassMemo = bypassMemo;
        }
    }

    /** One part of a group known to contain at least one required item. */
    private static final class Child {
        final List<RemovableItem> items;
        final String label;
        Boolean clean;     // null while untested
        Check check;       // set while scheduled in the current round
        boolean applied;   // removal already applied
        boolean free;      // known clean without a request: everything in it was already gone

        Child(List<RemovableItem> items, String label) {
            this.items = items;
            this.label = label;
        }
    }

    /** The parts of a split group; at least one of them contains a required item. */
    private static final class SiblingSet {
        final List<Child> children;

        SiblingSet(List<Child> children) {
            this.children = children;
        }

        int unscheduledUntested() {
            int count = 0;
            for (Child child : children) {
                if (child.clean == null && child.check == null) {
                    count++;
                }
            }
            return count;
        }

        Child firstUntested() {
            for (Child child : children) {
                if (child.clean == null && child.check == null) {
                    return child;
                }
            }
            return null;
        }
    }

    /** State of a single minimization run. */
    private final class Run {
        private final HttpRequest original;
        private final MinimizationTarget target;
        private final MinimizationConfig cfg;
        private final Function<HttpRequest, HttpResponse> sender;
        private final AtomicBoolean cancelFlag;
        private final ProgressListener listener;
        private final ExecutorService executor;
        private final int parallelism;

        private final Map<Set<RemovableItem>, Boolean> memo = new HashMap<>();
        private final Set<Set<RemovableItem>> passedInLastRound = new HashSet<>();
        private Prepared prepared;
        private List<RemovableItem> all = List.of();
        private ResponseComparator comparator;
        private int requestCount;
        private int rounds;
        private int consecutiveFailures;
        private boolean anySent;
        private String lastSendError;
        private String abortReason;

        // Search state
        private final Set<RemovableItem> removed = new HashSet<>();
        private final Deque<List<RemovableItem>> dirty = new ArrayDeque<>();
        private final List<SiblingSet> siblingSets = new ArrayList<>();
        private final Deque<RemovableItem> pool = new ArrayDeque<>();
        private int requiredFound;

        Run(HttpRequest original,
            MinimizationTarget target,
            MinimizationConfig cfg,
            Function<HttpRequest, HttpResponse> sender,
            AtomicBoolean cancelFlag,
            ProgressListener listener,
            ExecutorService executor) {
            this.original = original;
            this.target = target;
            this.cfg = cfg;
            this.sender = sender;
            this.cancelFlag = cancelFlag;
            this.listener = listener;
            this.executor = executor;
            this.parallelism = executor == null ? 1 : cfg.getEffectiveParallelism();
        }

        MinimizationResult execute(HttpResponse existingBaseline) {
            prepared = RequestMutator.prepare(original, target, cfg);
            all = prepared.candidates();
            listener.onStarted(original, target, all);

            if (all.isEmpty()) {
                return failure("Nothing to minimize: this request has no removable " + target.itemNoun() + ".");
            }

            // 1. Baseline, stability check / response samples and the "nothing required" fast
            //    path, together if possible.
            Set<RemovableItem> everything = new LinkedHashSet<>(all);
            Check empty = new Check(new LinkedHashSet<>(), "Without any candidate items", false);
            MinimizationResult setupFailure = establishBaseline(existingBaseline, empty);
            if (setupFailure != null) {
                return setupFailure;
            }
            memo.put(new HashSet<>(everything), Boolean.TRUE);
            if (shouldStop()) {
                return stopped();
            }

            List<RemovableItem> required;
            if (empty.passed) {
                required = List.of();
            } else {
                // 2. Adaptive group testing.
                search();
                if (shouldStop()) {
                    return stopped();
                }
                required = all.stream().filter(item -> !removed.contains(item) && !absent(item)).toList();

                // 3. Confirm the result with a request sent now, at the end of the run. Earlier
                //    answers are not enough: if the session expired part-way through, every later
                //    test fails and the search would wrongly conclude that everything is required.
                if (!confirmStillWorks(new LinkedHashSet<>(required), "Verifying minimized request")) {
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
                    required = eliminateOneByOne();
                }
            }

            if (shouldStop()) {
                return stopped();
            }

            HttpRequest minimizedRequest = prepared.build(new LinkedHashSet<>(required));
            MinimizationProfile profile = profileStore.createAndSaveProfile(
                    original, minimizedRequest, target, all.size(), required, requestCount);

            return new MinimizationResult(
                    profile, original, minimizedRequest, all, required, requestCount, rounds,
                    comparator.getBaselineStatusCode(), comparator.getBaselineBodyLength(), false, null);
        }

        /** A response to the unmodified request, with where it appears in the step log. */
        private record Copy(Sent sent, int stepNumber, int round) {
        }

        /**
         * Gets the responses to the unmodified request that the matching rules need (the baseline,
         * plus a stability copy or the samples Burp's response analysis learns from) and runs the
         * {@code empty} check. Everything goes out in one round when the parallelism allows,
         * otherwise one request at a time. Returns a result only if the run must end here.
         */
        private MinimizationResult establishBaseline(HttpResponse existingBaseline, Check empty) {
            boolean analysis = cfg.getResponseMatching() == ResponseMatching.BURP_ANALYSIS;
            int copiesWanted = analysis ? ANALYSIS_SAMPLES : (cfg.isVerifyBaselineStability() ? 2 : 1);
            int toSend = copiesWanted - (existingBaseline != null ? 1 : 0);

            List<Copy> copies = new ArrayList<>();
            Sent emptySent = null;
            if (toSend >= 1 && parallelism >= toSend + 1) {
                List<HttpRequest> requests = new ArrayList<>();
                for (int k = 0; k < toSend; k++) {
                    requests.add(original);
                }
                requests.add(prepared.build(empty.kept));
                List<Sent> sent = sendBatch(requests);
                beginRound();
                for (int k = 0; k < toSend; k++) {
                    account(sent.get(k));
                    copies.add(new Copy(sent.get(k), requestCount, rounds));
                }
                emptySent = sent.get(toSend);
            } else {
                for (int k = 0; k < toSend; k++) {
                    if (cancelled()) {
                        return stopped();
                    }
                    Sent sent = sendBatch(List.of(original)).get(0);
                    beginRound();
                    account(sent);
                    copies.add(new Copy(sent, requestCount, rounds));
                    if (sent.response() == null) {
                        break; // no point asking again
                    }
                }
            }

            // The baseline: Burp's existing response, or the first copy sent.
            HttpResponse baseline = existingBaseline;
            List<Copy> extraCopies = copies;
            if (baseline == null) {
                baseline = copies.isEmpty() ? null : copies.get(0).sent().response();
                extraCopies = copies.isEmpty() ? copies : copies.subList(1, copies.size());
            }
            if (baseline == null) {
                if (emptySent != null) {
                    account(emptySent);
                }
                if (cancelled()) {
                    return stopped();
                }
                return failure("No response received for the original request"
                        + (lastSendError == null ? "." : " (" + lastSendError + ")."));
            }

            List<HttpResponse> samples = new ArrayList<>();
            samples.add(baseline);
            for (Copy copy : extraCopies) {
                if (copy.sent().response() != null) {
                    samples.add(copy.sent().response());
                }
            }
            try {
                comparator = new ResponseComparator(samples, cfg);
            } catch (RuntimeException e) {
                if (emptySent != null) {
                    account(emptySent);
                }
                return failure("Burp could not analyse the responses (" + describe(e) + "). Set 'Response"
                        + " matching' to 'Rules' in the BareMinimum settings and try again.");
            }
            listener.onBaselineEstablished(comparator.getBaselineStatusCode(), comparator.getBaselineBodyLength());

            // Log the extra copies and check they agree with the baseline.
            boolean noResponse = extraCopies.size() < copiesWanted - 1;
            boolean unstable = false;
            for (int k = 0; k < extraCopies.size(); k++) {
                Copy copy = extraCopies.get(k);
                HttpResponse response = copy.sent().response();
                boolean ok = response != null && comparator.acceptsSample(response);
                noResponse |= response == null;
                unstable |= !ok;
                String description = analysis
                        ? "Original request again (response sample " + (k + 2) + " of " + copiesWanted + ")"
                        : "Stability check: original request again";
                if (analysis && k == extraCopies.size() - 1) {
                    description += ": " + comparator.stableAttributeCount() + " response attributes stay the same";
                }
                listener.onStep(new StepLog(copy.stepNumber(), copy.round(),
                        response == null ? description + " (no response)" : description,
                        all.size(), all.size(),
                        response == null ? 0 : response.statusCode(),
                        ResponseComparator.bodyLength(response), ok));
            }

            if (emptySent != null) {
                process(empty, emptySent);
            }
            if (shouldStop()) {
                return stopped();
            }
            if (noResponse) {
                return failure("No response received when re-sending the original request"
                        + (lastSendError == null ? "." : " (" + lastSendError + ")."));
            }
            if (unstable) {
                return failure(analysis
                        ? "The original request does not give a stable response: sending it again produced a"
                        + " different status code, or broke the required/forbidden substring rules."
                        : "The original request does not give a stable response: sending it twice"
                        + " produced responses that differ under the current matching rules."
                        + " Adjust the baseline rules in the BareMinimum settings, or switch 'Response"
                        + " matching' to Burp's response analysis, and try again.");
            }
            if (emptySent == null) {
                runChecks(List.of(empty));
            }
            return null;
        }

        // --- Search --------------------------------------------------------------------------

        private void search() {
            List<List<RemovableItem>> categories = target == MinimizationTarget.ALL ? categoryGroups() : List.of();
            if (categories.size() >= 2) {
                // Whole categories first: headers are most often junk (Sec-*, Accept-*, User-Agent ...).
                List<Child> children = new ArrayList<>();
                for (List<RemovableItem> group : categories) {
                    children.add(new Child(group, categoryLabel(group.get(0).category())));
                }
                siblingSets.add(new SiblingSet(children));
            } else {
                // "Remove everything" already failed, so the whole set holds a required item.
                dirty.add(all);
            }

            // Every round classifies or splits at least one group, so this always terminates; the
            // cap only guards against a defect turning into an endless stream of requests.
            int maxRounds = 4 * all.size() + 32;
            int searchRounds = 0;
            while (!shouldStop()) {
                pruneAbsent();
                while (!dirty.isEmpty()) {
                    List<RemovableItem> group = dirty.poll();
                    if (group.size() == 1) {
                        requiredFound++;
                    } else {
                        siblingSets.add(split(group));
                    }
                }
                if (siblingSets.isEmpty() && pool.isEmpty()) {
                    return;
                }
                if (++searchRounds > maxRounds) {
                    abortReason = "Stopped: the search did not converge. Please report this as a bug.";
                    return;
                }
                runSearchRound();
            }
        }

        private void runSearchRound() {
            Set<RemovableItem> base = new LinkedHashSet<>();
            for (RemovableItem item : all) {
                if (!removed.contains(item)) {
                    base.add(item);
                }
            }

            int slots = parallelism;
            List<Check> checks = new ArrayList<>();

            // a) One part of every split group: these tests always make progress.
            for (SiblingSet set : siblingSets) {
                if (slots == 0) {
                    break;
                }
                Child child = set.firstUntested();
                if (child != null) {
                    checks.add(schedule(child, base));
                    slots--;
                }
            }
            // b) With spare capacity, further parts of each split group, except the last untested
            //    one: if every other part is clean, that one is known to hold the required item.
            for (SiblingSet set : siblingSets) {
                for (Child child : set.children) {
                    if (slots > 0 && child.clean == null && child.check == null && set.unscheduledUntested() > 1) {
                        checks.add(schedule(child, base));
                        slots--;
                    }
                }
            }
            // c) Untested items, in groups sized for the density of required items seen so far.
            List<List<RemovableItem>> probes = new ArrayList<>();
            List<Check> probeChecks = new ArrayList<>();
            if (slots > 0 && !pool.isEmpty()) {
                int groupSize = poolGroupSize();
                while (slots > 0 && !pool.isEmpty()) {
                    List<RemovableItem> group = new ArrayList<>();
                    while (group.size() < groupSize && !pool.isEmpty()) {
                        group.add(pool.poll());
                    }
                    Check check = new Check(without(base, group), "Without " + group.size()
                            + (group.size() == 1 ? " untested item" : " untested items"), false);
                    probes.add(group);
                    probeChecks.add(check);
                    checks.add(check);
                    slots--;
                }
            }

            runChecks(checks);
            if (shouldStop()) {
                return;
            }

            for (SiblingSet set : new ArrayList<>(siblingSets)) {
                for (Child child : set.children) {
                    if (child.check != null) {
                        child.clean = child.check.passed;
                        child.check = null;
                    }
                }
                if (resolve(set)) {
                    siblingSets.remove(set);
                }
            }
            for (int i = 0; i < probes.size(); i++) {
                if (probeChecks.get(i).passed) {
                    removed.addAll(probes.get(i));
                } else {
                    dirty.add(probes.get(i));
                }
            }
        }

        private Check schedule(Child child, Set<RemovableItem> base) {
            String description = child.label != null
                    ? "Without any " + child.label
                    : "Without " + child.items.size() + (child.items.size() == 1 ? " item" : " items") + " (narrowing down)";
            child.check = new Check(without(base, child.items), description, false);
            return child.check;
        }

        /** Applies what is now known about a split group. Returns true once it is fully resolved. */
        private boolean resolve(SiblingSet set) {
            boolean anyRequired = false;
            for (Child child : set.children) {
                if (Boolean.TRUE.equals(child.clean) && !child.applied) {
                    removed.addAll(child.items);
                    child.applied = true;
                }
                if (Boolean.FALSE.equals(child.clean)) {
                    anyRequired = true;
                }
            }
            if (anyRequired) {
                List<RemovableItem> untested = new ArrayList<>();
                for (Child child : set.children) {
                    if (Boolean.FALSE.equals(child.clean)) {
                        dirty.add(child.items);
                    } else if (child.clean == null) {
                        untested.addAll(child.items);
                    }
                }
                returnToPool(untested);
                return true;
            }

            List<Child> untested = set.children.stream().filter(c -> c.clean == null).toList();
            if (untested.size() == 1) {
                // Everything else in the group is removable, so this part must hold the required item.
                dirty.add(untested.get(0).items);
                return true;
            }
            if (untested.isEmpty()) {
                // Every part tested clean. If removing one of them already took the others with it
                // (they sit inside it in a JSON/XML body), that request covered them all.
                for (Child child : set.children) {
                    if (!child.free && coversSiblings(child, set)) {
                        return true;
                    }
                }
                // Otherwise each part can go on its own, but not all of them at once (e.g. either
                // of two cookies works). Keep the last part and narrow it down without the others.
                Child last = null;
                for (Child child : set.children) {
                    if (!child.free) {
                        last = child;
                    }
                }
                if (last != null) {
                    removed.removeAll(last.items);
                    dirty.add(last.items);
                }
                return true;
            }
            return false;
        }

        /** True if every item of the other parts of {@code set} sits inside {@code child}. */
        private boolean coversSiblings(Child child, SiblingSet set) {
            if (!prepared.hasNestedItems()) {
                return false;
            }
            Set<RemovableItem> mine = new HashSet<>(child.items);
            for (Child other : set.children) {
                if (other == child) {
                    continue;
                }
                for (RemovableItem item : other.items) {
                    if (!mine.contains(item) && !prepared.anyAncestorIn(item, mine)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** True if {@code item} sits inside a removed JSON container or XML element. */
        private boolean absent(RemovableItem item) {
            return prepared.hasNestedItems() && prepared.anyAncestorIn(item, removed);
        }

        /**
         * Items inside a removed JSON container or XML element are already missing from every
         * request, so they are dropped without spending a request on them.
         */
        private void pruneAbsent() {
            if (!prepared.hasNestedItems()) {
                return;
            }
            pool.removeIf(item -> {
                if (absent(item)) {
                    removed.add(item);
                    return true;
                }
                return false;
            });
            for (SiblingSet set : new ArrayList<>(siblingSets)) {
                boolean changed = false;
                for (Child child : set.children) {
                    if (child.clean == null && child.check == null && child.items.stream().allMatch(this::absent)) {
                        child.clean = true;
                        child.free = true;
                        changed = true;
                    }
                }
                if (changed && resolve(set)) {
                    siblingSets.remove(set);
                }
            }
            int groups = dirty.size();
            for (int g = 0; g < groups; g++) {
                List<RemovableItem> group = dirty.poll();
                List<RemovableItem> present = group.stream().filter(item -> !absent(item)).toList();
                if (present.size() < group.size()) {
                    group.stream().filter(this::absent).forEach(removed::add);
                }
                if (!present.isEmpty()) {
                    dirty.add(present);
                }
            }
        }

        /**
         * Splits a group known to hold a required item. Sending one request at a time, into halves
         * (one test per level, the other half is inferred). With parallelism P, into P + 1 parts
         * of which P are tested in the same round and the last is inferred, so each round narrows
         * the group down by a factor of P + 1 instead of 2.
         */
        private SiblingSet split(List<RemovableItem> group) {
            int size = group.size();
            int parts = Math.min(size, parallelism > 1 ? parallelism + 1 : 2);
            List<Child> children = new ArrayList<>(parts);
            for (int i = 0; i < parts; i++) {
                int from = (int) ((long) size * i / parts);
                int to = (int) ((long) size * (i + 1) / parts);
                children.add(new Child(List.copyOf(group.subList(from, to)), null));
            }
            return new SiblingSet(children);
        }

        /** Fraction of the items looked at so far that turned out to be required (0 if unknown). */
        private double estimatedDensity() {
            int knownRequired = requiredFound + dirty.size() + siblingSets.size();
            int accounted = all.size() - pool.size();
            return accounted <= 0 ? 0 : (double) knownRequired / accounted;
        }

        /** Hwang's group size for {@code n} items expected to hold {@code expected} required ones. */
        private int hwangGroupSize(int n, double expected) {
            double ratio = (n - expected + 1) / expected;
            int size = ratio < 2 ? 1 : Integer.highestOneBit((int) ratio);
            return Math.max(1, Math.min(size, n));
        }

        private void returnToPool(List<RemovableItem> items) {
            for (int i = items.size() - 1; i >= 0; i--) {
                pool.addFirst(items.get(i));
            }
        }

        /**
         * Group size for untested items, following Hwang's generalised binary splitting with the
         * number of required items estimated from the density observed so far.
         */
        private int poolGroupSize() {
            int poolSize = pool.size();
            double expectedInPool = estimatedDensity() * poolSize;
            if (expectedInPool <= 1.0) {
                return poolSize; // probably nothing left to find: test the rest in one go
            }
            return hwangGroupSize(poolSize, expectedInPool);
        }

        private List<List<RemovableItem>> categoryGroups() {
            List<List<RemovableItem>> groups = new ArrayList<>();
            addCategory(groups, item -> item.category() == Category.HEADER);
            addCategory(groups, item -> item.category().isParameter());
            addCategory(groups, item -> item.category() == Category.COOKIE);
            return groups;
        }

        private void addCategory(List<List<RemovableItem>> groups, Predicate<RemovableItem> inCategory) {
            List<RemovableItem> group = all.stream().filter(inCategory).toList();
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }

        private String categoryLabel(Category category) {
            return switch (category) {
                case HEADER -> "headers";
                case COOKIE -> "cookies";
                default -> "parameters";
            };
        }

        private Set<RemovableItem> without(Set<RemovableItem> base, List<RemovableItem> group) {
            Set<RemovableItem> kept = new LinkedHashSet<>(base);
            group.forEach(kept::remove);
            return kept;
        }

        /**
         * Fallback: starting from the original request (just confirmed to work), drop items one at
         * a time and keep each removal that still reproduces the baseline. Every state it settles
         * on has been observed to work, so the result is verified by construction.
         */
        private List<RemovableItem> eliminateOneByOne() {
            List<RemovableItem> current = new ArrayList<>(all);
            for (RemovableItem item : all) {
                if (shouldStop()) {
                    break;
                }
                List<RemovableItem> trial = new ArrayList<>(current);
                trial.remove(item);
                if (prepared.hasNestedItems() && !prepared.allAncestorsIn(item, new HashSet<>(current))) {
                    current = trial; // already gone with the container it sits in
                    continue;
                }
                Check check = new Check(new LinkedHashSet<>(trial), "Fallback: without one more item", true);
                runChecks(List.of(check));
                if (check.passed) {
                    current = trial;
                }
            }
            return current;
        }

        // --- Sending and testing -------------------------------------------------------------

        /**
         * Like a normal check but never answers from memory, unless {@code kept} is exactly what a
         * request in the most recent round already confirmed.
         */
        private boolean confirmStillWorks(Set<RemovableItem> kept, String description) {
            if (shouldStop()) {
                return false;
            }
            if (passedInLastRound.contains(prepared.effective(kept))) {
                return true;
            }
            Check check = new Check(kept, description, true);
            runChecks(List.of(check));
            return check.passed;
        }

        /** Sends every check that is not already answered from memory, all in one round. */
        private void runChecks(List<Check> checks) {
            if (shouldStop()) {
                return;
            }
            List<Check> toSend = new ArrayList<>();
            for (Check check : checks) {
                Boolean cached = check.bypassMemo ? null : memo.get(prepared.effective(check.kept));
                if (cached != null) {
                    check.passed = cached;
                } else {
                    toSend.add(check);
                }
            }
            if (toSend.isEmpty()) {
                return;
            }
            List<HttpRequest> requests = new ArrayList<>(toSend.size());
            for (Check check : toSend) {
                requests.add(prepared.build(check.kept));
            }
            List<Sent> sent = sendBatch(requests);
            beginRound();
            for (int i = 0; i < toSend.size(); i++) {
                process(toSend.get(i), sent.get(i));
            }
        }

        private void beginRound() {
            rounds++;
            passedInLastRound.clear();
        }

        /** Records the outcome of one test request (on the run's own thread, in a fixed order). */
        private void process(Check check, Sent sent) {
            HttpResponse response = account(sent);
            if (sent.attempts() == 0) {
                check.passed = false; // never sent because the run is stopping
                return;
            }
            if (response == null) {
                if (!cancelled() && ++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && abortReason == null) {
                    abortReason = "Stopped: the target did not respond to " + MAX_CONSECUTIVE_FAILURES
                            + " test requests in a row" + (lastSendError == null ? "." : " (" + lastSendError + ").");
                }
            } else {
                consecutiveFailures = 0;
            }

            boolean passed = response != null && comparator.isEquivalent(response);
            check.passed = passed;
            Set<RemovableItem> key = new HashSet<>(prepared.effective(check.kept));
            memo.put(key, passed);
            if (passed) {
                passedInLastRound.add(key);
            }

            listener.onStep(new StepLog(
                    requestCount,
                    rounds,
                    response == null ? check.description + " (no response)" : check.description,
                    check.kept.size(),
                    all.size(),
                    response == null ? 0 : response.statusCode(),
                    ResponseComparator.bodyLength(response),
                    passed));
        }

        /** Adds a sent request to the run's totals and returns its response. */
        private HttpResponse account(Sent sent) {
            requestCount += sent.attempts();
            if (sent.error() != null) {
                lastSendError = sent.error();
            }
            return sent.response();
        }

        /**
         * Sends the requests, in parallel when allowed. Results come back in the same order. Runs
         * no state-changing code on the worker threads.
         */
        private List<Sent> sendBatch(List<HttpRequest> requests) {
            List<Sent> results = new ArrayList<>(requests.size());
            if (executor == null || parallelism <= 1 || requests.size() == 1) {
                for (HttpRequest request : requests) {
                    results.add(pauseBeforeSending() ? sendWithRetry(request) : Sent.NOT_SENT);
                }
                return results;
            }

            List<Future<Sent>> futures = new ArrayList<>(requests.size());
            try {
                for (HttpRequest request : requests) {
                    futures.add(executor.submit(() -> cancelled() ? Sent.NOT_SENT : sendWithRetry(request)));
                }
            } catch (RejectedExecutionException e) {
                // The extension is unloading.
                futures.forEach(f -> f.cancel(true));
                requests.forEach(r -> results.add(Sent.NOT_SENT));
                return results;
            }
            anySent = true;

            boolean interrupted = false;
            for (Future<Sent> future : futures) {
                if (interrupted) {
                    future.cancel(true);
                    results.add(Sent.NOT_SENT);
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    future.cancel(true);
                    results.add(Sent.NOT_SENT);
                } catch (ExecutionException e) {
                    results.add(new Sent(null, 1, describe(e.getCause() != null ? e.getCause() : e)));
                } catch (CancellationException e) {
                    results.add(Sent.NOT_SENT);
                }
            }
            return results;
        }

        /** Applies the configured delay between requests. Returns false if the run should stop. */
        private boolean pauseBeforeSending() {
            if (cancelled()) {
                return false;
            }
            int delay = cfg.getRequestDelayMs();
            if (delay > 0 && anySent) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            anySent = true;
            return !cancelled();
        }

        /** Sends a request, retrying once so a single network blip cannot skew the result. */
        private Sent sendWithRetry(HttpRequest request) {
            Sent first = sendOnce(request);
            if (first.response() != null || cancelled()) {
                return first;
            }
            Sent second = sendOnce(request);
            return new Sent(second.response(), 2, second.error() != null ? second.error() : first.error());
        }

        private Sent sendOnce(HttpRequest request) {
            try {
                HttpResponse response = sender.apply(request);
                return new Sent(response, 1, response == null ? "no response" : null);
            } catch (RuntimeException e) {
                return new Sent(null, 1, describe(e));
            }
        }

        // --- Results -------------------------------------------------------------------------

        MinimizationResult failure(String message) {
            return new MinimizationResult(
                    null, original, original, all, List.of(), requestCount, rounds,
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
                    null, original, original, all, List.of(), requestCount, rounds,
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
    }
}
