package com.bareminimum.core;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.model.MinimizationConfig;

import java.util.Set;

/**
 * Compares a test {@link HttpResponse} against a baseline {@link HttpResponse}
 * using the rules configured in {@link MinimizationConfig}.
 *
 * <p>Response content is untrusted: it is only ever measured and searched, never interpreted.
 */
public final class ResponseComparator {
    private final short baselineStatusCode;
    private final int baselineBodyLength;
    private final Set<String> baselineJsonKeys;
    private final MinimizationConfig config;

    public ResponseComparator(HttpResponse baseline, MinimizationConfig config) {
        String body = bodyText(baseline);
        this.baselineStatusCode = baseline.statusCode();
        this.baselineBodyLength = bodyLength(baseline, body);
        this.baselineJsonKeys = JsonBodyHelper.topLevelKeys(body);
        this.config = config;
    }

    public boolean isEquivalent(HttpResponse candidate) {
        if (candidate == null) {
            return false;
        }

        // 1. Status code check
        if (config.isMatchStatusCode() && candidate.statusCode() != baselineStatusCode) {
            return false;
        }

        String candidateBody = bodyText(candidate);

        // 2. Required substring check
        String requiredSubstring = config.getRequiredSubstring();
        if (!requiredSubstring.isEmpty() && !candidateBody.contains(requiredSubstring)) {
            return false;
        }

        // 3. Forbidden substring check
        String forbiddenSubstring = config.getForbiddenSubstring();
        if (!forbiddenSubstring.isEmpty() && candidateBody.contains(forbiddenSubstring)) {
            return false;
        }

        // 4. JSON top-level structure check (if baseline was a JSON object)
        if (config.isCheckJsonStructure() && !baselineJsonKeys.isEmpty()
                && !baselineJsonKeys.equals(JsonBodyHelper.topLevelKeys(candidateBody))) {
            return false;
        }

        // 5. Body length tolerance check
        if (config.isMatchBodyLength()) {
            int candidateLength = bodyLength(candidate, candidateBody);
            double pct = config.getBodyLengthTolerancePercent();
            long diff = Math.abs((long) candidateLength - (long) baselineBodyLength);
            if (pct == 0.0) {
                return diff == 0;
            }
            // A small absolute allowance stops tiny bodies from failing on a few bytes of noise.
            long allowedDelta = Math.max(16L, Math.round(baselineBodyLength * (pct / 100.0)));
            return diff <= allowedDelta;
        }

        return true;
    }

    public short getBaselineStatusCode() {
        return baselineStatusCode;
    }

    public int getBaselineBodyLength() {
        return baselineBodyLength;
    }

    /** Body length in bytes of a response (0 for {@code null}). */
    public static int bodyLength(HttpResponse response) {
        return response == null ? 0 : bodyLength(response, bodyText(response));
    }

    private static int bodyLength(HttpResponse response, String bodyText) {
        ByteArray body = response.body();
        return body != null ? body.length() : bodyText.length();
    }

    private static String bodyText(HttpResponse response) {
        String body = response.bodyToString();
        return body == null ? "" : body;
    }
}
