package com.bareminimum.core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.core.DeltaDebugMinimizer.MinimizationResult;
import com.bareminimum.core.DeltaDebugMinimizer.ProgressListener;
import com.bareminimum.core.DeltaDebugMinimizer.StepLog;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs {@link DeltaDebugMinimizer} jobs one at a time on a background thread (never on the Swing
 * Event Dispatch Thread) and delivers progress updates back on the Swing thread.
 *
 * <p>All test requests go through Burp's own HTTP stack, so upstream proxy, TLS and session
 * handling settings apply to them.
 */
public final class MinimizationJobManager {
    private final MontoyaApi api;
    private final DeltaDebugMinimizer minimizer;
    private final ExecutorService executor;
    private final ThreadPoolExecutor requestExecutor;
    private final List<ProgressListener> uiListeners = new CopyOnWriteArrayList<>();
    private final Set<AtomicBoolean> outstandingCancelFlags = ConcurrentHashMap.newKeySet();
    private volatile AtomicBoolean runningCancelFlag;
    private volatile boolean shutDown;

    public MinimizationJobManager(MontoyaApi api, MinimizationConfig config, ProfileStore profileStore) {
        this.api = api;
        this.minimizer = new DeltaDebugMinimizer(config, profileStore);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BareMinimum-Worker");
            t.setDaemon(true);
            return t;
        });
        // Sends the test requests of a round in parallel. Only one job runs at a time, so the pool
        // never needs more threads than the highest parallelism setting; idle threads exit.
        AtomicInteger threadNumber = new AtomicInteger();
        this.requestExecutor = new ThreadPoolExecutor(
                MinimizationConfig.MAX_PARALLELISM, MinimizationConfig.MAX_PARALLELISM,
                30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "BareMinimum-Request-" + threadNumber.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.requestExecutor.allowCoreThreadTimeOut(true);
    }

    public void addProgressListener(ProgressListener listener) {
        uiListeners.add(listener);
    }

    /** Cancels the job that is running right now (queued jobs are left to run). */
    public void cancelCurrentJob() {
        AtomicBoolean flag = runningCancelFlag;
        if (flag != null) {
            flag.set(true);
        }
    }

    /** Cancels every running and queued job and stops the worker thread. Called on unload. */
    public void shutdown() {
        shutDown = true;
        for (AtomicBoolean flag : outstandingCancelFlags) {
            flag.set(true);
        }
        executor.shutdownNow();
        requestExecutor.shutdownNow();
        uiListeners.clear();
    }

    /**
     * Queues a minimization of {@code request}. {@code onCompleteUiCallback} is invoked on the
     * Swing thread once the job has finished, failed or been cancelled.
     */
    public void startJob(
            HttpRequest request,
            HttpResponse existingBaseline,
            MinimizationTarget target,
            Consumer<MinimizationResult> onCompleteUiCallback) {
        if (request == null || shutDown) {
            return;
        }
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        outstandingCancelFlags.add(cancelFlag);
        try {
            executor.execute(() -> runJob(request, existingBaseline, target, cancelFlag, onCompleteUiCallback));
        } catch (RejectedExecutionException e) {
            outstandingCancelFlags.remove(cancelFlag); // unloaded between the check and the submit
        }
    }

    private void runJob(
            HttpRequest request,
            HttpResponse existingBaseline,
            MinimizationTarget target,
            AtomicBoolean cancelFlag,
            Consumer<MinimizationResult> onCompleteUiCallback) {
        runningCancelFlag = cancelFlag;
        try {
            logOutput("Starting '" + target.menuLabel() + "' on " + describe(request));

            MinimizationResult result = minimizer.minimize(
                    request,
                    existingBaseline,
                    target,
                    this::send,
                    cancelFlag,
                    new ProgressListener() {
                        @Override
                        public void onStarted(HttpRequest req, MinimizationTarget tgt, List<RemovableItem> candidates) {
                            dispatch(l -> l.onStarted(req, tgt, candidates));
                        }

                        @Override
                        public void onBaselineEstablished(short statusCode, int bodyLength) {
                            dispatch(l -> l.onBaselineEstablished(statusCode, bodyLength));
                        }

                        @Override
                        public void onStep(StepLog stepLog) {
                            dispatch(l -> l.onStep(stepLog));
                        }

                        @Override
                        public void onCompleted(MinimizationResult res) {
                            onSwingThread(() -> {
                                for (ProgressListener l : uiListeners) {
                                    l.onCompleted(res);
                                }
                                if (onCompleteUiCallback != null) {
                                    onCompleteUiCallback.accept(res);
                                }
                            });
                        }
                    },
                    requestExecutor);

            MinimizationProfile profile = result.profile();
            if (profile != null) {
                logOutput(String.format(
                        "Completed -> saved as '%s' (%s): kept %d / %d items in %d HTTP requests (%d rounds).",
                        clean(profile.getName()),
                        profile.getShortSummary(),
                        result.requiredItems().size(),
                        result.allCandidates().size(),
                        result.requestsSent(),
                        result.rounds()));
            } else if (result.errorMessage() != null) {
                logError(result.errorMessage());
            } else if (result.cancelled()) {
                logOutput("Cancelled after " + result.requestsSent() + " HTTP requests.");
            }
        } catch (RuntimeException e) {
            logError("Unexpected error during minimization: " + e);
        } finally {
            outstandingCancelFlags.remove(cancelFlag);
            runningCancelFlag = null;
        }
    }

    private HttpResponse send(HttpRequest request) {
        HttpRequestResponse requestResponse = api.http().sendRequest(request);
        return requestResponse != null ? requestResponse.response() : null;
    }

    private void dispatch(Consumer<ProgressListener> event) {
        onSwingThread(() -> {
            for (ProgressListener l : uiListeners) {
                event.accept(l);
            }
        });
    }

    private void onSwingThread(Runnable task) {
        SwingUtilities.invokeLater(() -> {
            if (!shutDown) {
                task.run();
            }
        });
    }

    private void logOutput(String message) {
        try {
            api.logging().logToOutput("[BareMinimum] " + message);
        } catch (RuntimeException ignored) {
            // Logging must never break a job (e.g. while the extension is unloading).
        }
    }

    private void logError(String message) {
        try {
            api.logging().logToError("[BareMinimum] " + clean(message));
        } catch (RuntimeException ignored) {
            // See logOutput.
        }
    }

    private static String describe(HttpRequest request) {
        try {
            return clean(request.method() + " " + request.path());
        } catch (RuntimeException e) {
            return "request";
        }
    }

    /** Keeps untrusted request text from forging extra lines in Burp's extension log. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\p{Cntrl}", " ");
        return singleLine.length() > 300 ? singleLine.substring(0, 300) + "..." : singleLine;
    }
}
