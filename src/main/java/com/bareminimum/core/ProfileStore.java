package com.bareminimum.core;

import burp.api.montoya.http.message.requests.HttpRequest;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;

import javax.swing.SwingUtilities;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central store for saved {@link MinimizationProfile}s (such as "GET - ALL 1" or "POST - COOKIES 3")
 * and the currently selected active profile used by the Repeater "Minimize Request" button.
 */
public final class ProfileStore {
    private final List<MinimizationProfile> profiles = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> countersByPrefix = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<WeakReference<Runnable>> weakListeners = new CopyOnWriteArrayList<>();
    private volatile MinimizationProfile activeProfile;

    /**
     * Generates the next profile name in the format "{METHOD} - {TARGET} {N}",
     * e.g., "GET - ALL 1", "POST - COOKIES 3", "GET - HEADERS 2".
     */
    public String nextProfileName(String httpMethod, MinimizationTarget target) {
        String method = (httpMethod == null || httpMethod.isBlank())
                ? "GET"
                : httpMethod.trim().toUpperCase(Locale.ROOT);
        String prefix = method + " - " + target.shortCode();
        int nextNum = countersByPrefix
                .computeIfAbsent(prefix, k -> new AtomicInteger(0))
                .incrementAndGet();
        return prefix + " " + nextNum;
    }

    /**
     * Creates, registers, and activates a new {@link MinimizationProfile} from a completed minimization run.
     */
    public MinimizationProfile createAndSaveProfile(
            HttpRequest originalRequest,
            HttpRequest minimizedRequest,
            MinimizationTarget target,
            int originalCandidateCount,
            List<RemovableItem> requiredItems,
            int requestsSent) {
        String method = safeMethod(originalRequest);
        String path = safePath(originalRequest);
        String name = nextProfileName(method, target);

        Set<String> reqCookies = new LinkedHashSet<>();
        Set<String> reqHeaders = new LinkedHashSet<>();
        Set<String> reqUrlParams = new LinkedHashSet<>();
        Set<String> reqBodyParams = new LinkedHashSet<>();

        for (RemovableItem item : requiredItems) {
            switch (item.category()) {
                case COOKIE -> reqCookies.add(item.name());
                case HEADER -> reqHeaders.add(item.name());
                case URL_PARAM -> reqUrlParams.add(item.name());
                case BODY_PARAM, JSON_PARAM -> reqBodyParams.add(item.name());
            }
        }

        MinimizationProfile profile = new MinimizationProfile(
                name,
                method,
                path,
                target,
                reqCookies,
                reqHeaders,
                reqUrlParams,
                reqBodyParams,
                originalCandidateCount,
                requiredItems.size(),
                requestsSent,
                storeOutOfMemory(minimizedRequest),
                Instant.now());

        addProfile(profile);
        return profile;
    }

    public void addProfile(MinimizationProfile profile) {
        profiles.add(profile);
        activeProfile = profile;
        notifyListeners();
    }

    public void removeProfile(MinimizationProfile profile) {
        profiles.remove(profile);
        if (activeProfile == profile) {
            activeProfile = profiles.isEmpty() ? null : profiles.get(profiles.size() - 1);
        }
        notifyListeners();
    }

    public void clearAll() {
        profiles.clear();
        activeProfile = null;
        notifyListeners();
    }

    public List<MinimizationProfile> getProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    public MinimizationProfile getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(MinimizationProfile profile) {
        this.activeProfile = profile;
        notifyListeners();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void addWeakListener(Runnable listener) {
        weakListeners.add(new WeakReference<>(listener));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
        weakListeners.removeIf(ref -> {
            Runnable target = ref.get();
            return target == null || target == listener;
        });
    }

    public void shutdown() {
        listeners.clear();
        weakListeners.clear();
        profiles.clear();
        activeProfile = null;
    }

    public void notifyListeners() {
        Runnable dispatch = () -> {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {
                }
            }
            weakListeners.removeIf(ref -> ref.get() == null);
            for (WeakReference<Runnable> ref : weakListeners) {
                Runnable listener = ref.get();
                if (listener != null) {
                    try {
                        listener.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            dispatch.run();
        } else {
            SwingUtilities.invokeLater(dispatch);
        }
    }

    /**
     * Profiles live for the whole Burp session, so the request they keep for preview is moved to
     * Burp's temporary file storage rather than held in memory.
     */
    private static HttpRequest storeOutOfMemory(HttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            HttpRequest stored = request.copyToTempFile();
            return stored != null ? stored : request;
        } catch (RuntimeException e) {
            return request;
        }
    }

    private static String safeMethod(HttpRequest request) {
        try {
            String m = request.method();
            return (m == null || m.isBlank()) ? "GET" : m;
        } catch (Exception e) {
            return "GET";
        }
    }

    private static String safePath(HttpRequest request) {
        try {
            String p = request.pathWithoutQuery();
            return (p == null || p.isBlank()) ? "/" : p;
        } catch (Exception e) {
            try {
                String p = request.path();
                return (p == null || p.isBlank()) ? "/" : p;
            } catch (Exception ex) {
                return "/";
            }
        }
    }
}
