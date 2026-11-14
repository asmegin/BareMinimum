package com.bareminimum.core;

import burp.api.montoya.persistence.Preferences;
import com.bareminimum.model.MinimizationConfig;

/**
 * Loads and saves {@link MinimizationConfig} in Burp's user-level extension preferences so the
 * baseline rules survive restarts. Only the user's own settings are stored, never HTTP data.
 */
public final class SettingsPersistence {
    private static final String PREFIX = "bareminimum.";
    private static final String MATCH_STATUS = PREFIX + "matchStatusCode";
    private static final String MATCH_LENGTH = PREFIX + "matchBodyLength";
    private static final String LENGTH_TOLERANCE = PREFIX + "bodyLengthTolerancePercent";
    private static final String CHECK_JSON = PREFIX + "checkJsonStructure";
    private static final String VERIFY_BASELINE = PREFIX + "verifyBaselineStability";
    private static final String REQUIRED_SUBSTRING = PREFIX + "requiredSubstring";
    private static final String FORBIDDEN_SUBSTRING = PREFIX + "forbiddenSubstring";
    private static final String PINNED_HEADERS = PREFIX + "pinnedHeaders";
    private static final String REQUEST_DELAY = PREFIX + "requestDelayMs";
    private static final String PARALLELISM = PREFIX + "parallelism";
    private static final String RESPONSE_MATCHING = PREFIX + "responseMatching";

    private SettingsPersistence() {
    }

    /** Applies any previously saved settings to {@code config}; missing or bad values are skipped. */
    public static void load(Preferences prefs, MinimizationConfig config) {
        if (prefs == null) {
            return;
        }
        try {
            Boolean matchStatus = prefs.getBoolean(MATCH_STATUS);
            if (matchStatus != null) {
                config.setMatchStatusCode(matchStatus);
            }
            Boolean matchLength = prefs.getBoolean(MATCH_LENGTH);
            if (matchLength != null) {
                config.setMatchBodyLength(matchLength);
            }
            String tolerance = prefs.getString(LENGTH_TOLERANCE);
            if (tolerance != null) {
                try {
                    config.setBodyLengthTolerancePercent(Double.parseDouble(tolerance));
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
            Boolean checkJson = prefs.getBoolean(CHECK_JSON);
            if (checkJson != null) {
                config.setCheckJsonStructure(checkJson);
            }
            Boolean verifyBaseline = prefs.getBoolean(VERIFY_BASELINE);
            if (verifyBaseline != null) {
                config.setVerifyBaselineStability(verifyBaseline);
            }
            String required = prefs.getString(REQUIRED_SUBSTRING);
            if (required != null) {
                config.setRequiredSubstring(required);
            }
            String forbidden = prefs.getString(FORBIDDEN_SUBSTRING);
            if (forbidden != null) {
                config.setForbiddenSubstring(forbidden);
            }
            String pinned = prefs.getString(PINNED_HEADERS);
            if (pinned != null) {
                config.setPinnedHeadersFromCsv(pinned);
            }
            Integer delay = prefs.getInteger(REQUEST_DELAY);
            if (delay != null) {
                config.setRequestDelayMs(delay);
            }
            Integer parallelism = prefs.getInteger(PARALLELISM);
            if (parallelism != null) {
                config.setParallelism(parallelism);
            }
            String matching = prefs.getString(RESPONSE_MATCHING);
            if (matching != null) {
                try {
                    config.setResponseMatching(MinimizationConfig.ResponseMatching.valueOf(matching));
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
            }
        } catch (RuntimeException ignored) {
            // Preferences are a convenience; fall back to defaults if they cannot be read.
        }
    }

    public static void save(Preferences prefs, MinimizationConfig config) {
        if (prefs == null) {
            return;
        }
        prefs.setBoolean(MATCH_STATUS, config.isMatchStatusCode());
        prefs.setBoolean(MATCH_LENGTH, config.isMatchBodyLength());
        prefs.setString(LENGTH_TOLERANCE, Double.toString(config.getBodyLengthTolerancePercent()));
        prefs.setBoolean(CHECK_JSON, config.isCheckJsonStructure());
        prefs.setBoolean(VERIFY_BASELINE, config.isVerifyBaselineStability());
        prefs.setString(REQUIRED_SUBSTRING, config.getRequiredSubstring());
        prefs.setString(FORBIDDEN_SUBSTRING, config.getForbiddenSubstring());
        prefs.setString(PINNED_HEADERS, config.getPinnedHeadersCsv());
        prefs.setInteger(REQUEST_DELAY, config.getRequestDelayMs());
        prefs.setInteger(PARALLELISM, config.getParallelism());
        prefs.setString(RESPONSE_MATCHING, config.getResponseMatching().name());
    }
}
