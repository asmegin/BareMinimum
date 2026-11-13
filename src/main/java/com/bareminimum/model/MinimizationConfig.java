package com.bareminimum.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thread-safe configuration for determining whether a minimized request response
 * is equivalent to the baseline response, as well as which headers are always pinned.
 *
 * <p>The live instance is edited from the Swing thread. Each minimization run works on an
 * immutable-by-convention {@link #snapshot()} so that changing a setting mid-run cannot make
 * the run inconsistent.
 */
public final class MinimizationConfig {
    public static final int MAX_REQUEST_DELAY_MS = 60_000;

    private volatile boolean matchStatusCode = true;
    private volatile boolean matchBodyLength = true;
    private volatile double bodyLengthTolerancePercent = 5.0;
    private volatile boolean checkJsonStructure = true;
    private volatile boolean verifyBaselineStability = true;
    private volatile String requiredSubstring = "";
    private volatile String forbiddenSubstring = "";
    private volatile Set<String> pinnedHeaders = defaultPinnedHeaders();
    private volatile int requestDelayMs = 0;
    private volatile Runnable changeListener;

    public static Set<String> defaultPinnedHeaders() {
        Set<String> set = new LinkedHashSet<>();
        set.add("host");
        set.add("content-length");
        return Collections.unmodifiableSet(set);
    }

    /**
     * Returns an independent copy of the current settings (without the change listener).
     */
    public MinimizationConfig snapshot() {
        MinimizationConfig copy = new MinimizationConfig();
        copy.matchStatusCode = matchStatusCode;
        copy.matchBodyLength = matchBodyLength;
        copy.bodyLengthTolerancePercent = bodyLengthTolerancePercent;
        copy.checkJsonStructure = checkJsonStructure;
        copy.verifyBaselineStability = verifyBaselineStability;
        copy.requiredSubstring = requiredSubstring;
        copy.forbiddenSubstring = forbiddenSubstring;
        copy.pinnedHeaders = pinnedHeaders;
        copy.requestDelayMs = requestDelayMs;
        return copy;
    }

    /**
     * Registers a callback invoked after any setting changes (used to persist settings).
     */
    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void changed() {
        Runnable listener = changeListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // Persisting settings is best-effort and must never break the UI.
            }
        }
    }

    public boolean isMatchStatusCode() {
        return matchStatusCode;
    }

    public void setMatchStatusCode(boolean matchStatusCode) {
        this.matchStatusCode = matchStatusCode;
        changed();
    }

    public boolean isMatchBodyLength() {
        return matchBodyLength;
    }

    public void setMatchBodyLength(boolean matchBodyLength) {
        this.matchBodyLength = matchBodyLength;
        changed();
    }

    public double getBodyLengthTolerancePercent() {
        return bodyLengthTolerancePercent;
    }

    public void setBodyLengthTolerancePercent(double bodyLengthTolerancePercent) {
        double value = Double.isNaN(bodyLengthTolerancePercent) ? 0.0 : bodyLengthTolerancePercent;
        this.bodyLengthTolerancePercent = Math.max(0.0, Math.min(100.0, value));
        changed();
    }

    public boolean isCheckJsonStructure() {
        return checkJsonStructure;
    }

    public void setCheckJsonStructure(boolean checkJsonStructure) {
        this.checkJsonStructure = checkJsonStructure;
        changed();
    }

    public boolean isVerifyBaselineStability() {
        return verifyBaselineStability;
    }

    public void setVerifyBaselineStability(boolean verifyBaselineStability) {
        this.verifyBaselineStability = verifyBaselineStability;
        changed();
    }

    public String getRequiredSubstring() {
        return requiredSubstring;
    }

    public void setRequiredSubstring(String requiredSubstring) {
        this.requiredSubstring = requiredSubstring == null ? "" : requiredSubstring.trim();
        changed();
    }

    public String getForbiddenSubstring() {
        return forbiddenSubstring;
    }

    public void setForbiddenSubstring(String forbiddenSubstring) {
        this.forbiddenSubstring = forbiddenSubstring == null ? "" : forbiddenSubstring.trim();
        changed();
    }

    public Set<String> getPinnedHeaders() {
        return pinnedHeaders;
    }

    public void setPinnedHeadersFromCsv(String csv) {
        Set<String> parsed = new LinkedHashSet<>();
        // Host and Content-Length always stay pinned so the request keeps valid HTTP framing.
        parsed.add("host");
        parsed.add("content-length");
        if (csv != null) {
            parsed.addAll(Arrays.stream(csv.split("[,;\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        this.pinnedHeaders = Collections.unmodifiableSet(parsed);
        changed();
    }

    public String getPinnedHeadersCsv() {
        return String.join(", ", pinnedHeaders);
    }

    public boolean isHeaderPinned(String headerName) {
        if (headerName == null) {
            return false;
        }
        return pinnedHeaders.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    public int getRequestDelayMs() {
        return requestDelayMs;
    }

    public void setRequestDelayMs(int requestDelayMs) {
        this.requestDelayMs = Math.max(0, Math.min(MAX_REQUEST_DELAY_MS, requestDelayMs));
        changed();
    }
}
