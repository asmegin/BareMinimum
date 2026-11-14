package com.bareminimum.core;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.responses.analysis.Attribute;
import burp.api.montoya.http.message.responses.analysis.AttributeType;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationConfig.ResponseMatching;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a test {@link HttpResponse} still matches the baseline, using the rules
 * configured in {@link MinimizationConfig}.
 *
 * <p>In {@link ResponseMatching#RULES} mode the status code, body length (within a tolerance) and
 * top-level JSON keys are compared. In {@link ResponseMatching#BURP_ANALYSIS} mode the comparator
 * is built from several responses to the unmodified request and keeps the response attributes
 * Burp computes (status code, content type, page title, word and line counts, visible text, tag
 * structure, cookie names ...) that are identical in all of them; a test response must have the
 * same value for every one of those. Attributes that vary on their own, such as a timestamp in
 * the body, are learned and ignored. The required and forbidden substrings apply in both modes.
 *
 * <p>Response content is untrusted: it is only ever measured and searched, never interpreted.
 */
public final class ResponseComparator {
    private final short baselineStatusCode;
    private final int baselineBodyLength;
    private final Set<String> baselineJsonKeys;
    private final MinimizationConfig config;
    private final boolean analysis;
    private final Map<AttributeType, Integer> stableAttributes;
    private final AttributeType[] stableTypes;

    public ResponseComparator(HttpResponse baseline, MinimizationConfig config) {
        this(List.of(baseline), config);
    }

    /**
     * @param samples responses to the unmodified request, the baseline first. Burp analysis mode
     *                learns from all of them; rules mode only uses the first.
     * @throws RuntimeException if Burp cannot analyse the responses (analysis mode only)
     */
    public ResponseComparator(List<HttpResponse> samples, MinimizationConfig config) {
        HttpResponse baseline = samples.get(0);
        String body = bodyText(baseline);
        this.baselineStatusCode = baseline.statusCode();
        this.baselineBodyLength = bodyLength(baseline, body);
        this.baselineJsonKeys = JsonBodyHelper.topLevelKeys(body);
        this.config = config;
        this.analysis = config.getResponseMatching() == ResponseMatching.BURP_ANALYSIS;

        if (analysis) {
            Map<AttributeType, Integer> stable = attributeValues(baseline, AttributeType.values());
            for (int i = 1; i < samples.size(); i++) {
                Map<AttributeType, Integer> other = attributeValues(samples.get(i), AttributeType.values());
                stable.entrySet().removeIf(entry -> !Objects.equals(entry.getValue(), other.get(entry.getKey())));
            }
            // As in Burp's own tools: Last-Modified says nothing about the content.
            stable.remove(AttributeType.LAST_MODIFIED_HEADER);
            if (!config.isMatchStatusCode()) {
                stable.remove(AttributeType.STATUS_CODE);
            }
            this.stableAttributes = Collections.unmodifiableMap(stable);
        } else {
            this.stableAttributes = Map.of();
        }
        this.stableTypes = stableAttributes.keySet().toArray(new AttributeType[0]);
    }

    public boolean isEquivalent(HttpResponse candidate) {
        if (!passesBasicRules(candidate)) {
            return false;
        }
        if (analysis) {
            return keepsStableAttributes(candidate);
        }

        // Top-level JSON structure (if the baseline was a JSON object)
        if (config.isCheckJsonStructure() && !baselineJsonKeys.isEmpty()
                && !baselineJsonKeys.equals(JsonBodyHelper.topLevelKeys(bodyText(candidate)))) {
            return false;
        }

        // Body length within the tolerance
        if (config.isMatchBodyLength()) {
            int candidateLength = bodyLength(candidate);
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

    /**
     * Whether another response to the unmodified request is consistent with the baseline. In
     * rules mode that is {@link #isEquivalent}; in analysis mode the sample itself defines what
     * may vary, so only the status code and substring rules are checked.
     */
    public boolean acceptsSample(HttpResponse sample) {
        return analysis ? passesBasicRules(sample) : isEquivalent(sample);
    }

    /** Number of Burp response attributes a test response must keep (analysis mode only). */
    public int stableAttributeCount() {
        return stableAttributes.size();
    }

    public short getBaselineStatusCode() {
        return baselineStatusCode;
    }

    public int getBaselineBodyLength() {
        return baselineBodyLength;
    }

    /** Body length in bytes of a response (0 for {@code null}). */
    public static int bodyLength(HttpResponse response) {
        if (response == null) {
            return 0;
        }
        ByteArray body = response.body();
        return body != null ? body.length() : bodyText(response).length();
    }

    /** Status code and substring rules, which apply in both modes. */
    private boolean passesBasicRules(HttpResponse candidate) {
        if (candidate == null) {
            return false;
        }
        if (config.isMatchStatusCode() && candidate.statusCode() != baselineStatusCode) {
            return false;
        }
        String requiredSubstring = config.getRequiredSubstring();
        String forbiddenSubstring = config.getForbiddenSubstring();
        if (requiredSubstring.isEmpty() && forbiddenSubstring.isEmpty()) {
            return true;
        }
        String body = bodyText(candidate);
        if (!requiredSubstring.isEmpty() && !body.contains(requiredSubstring)) {
            return false;
        }
        return forbiddenSubstring.isEmpty() || !body.contains(forbiddenSubstring);
    }

    private boolean keepsStableAttributes(HttpResponse candidate) {
        if (stableTypes.length == 0) {
            return true;
        }
        Map<AttributeType, Integer> values = attributeValues(candidate, stableTypes);
        for (Map.Entry<AttributeType, Integer> entry : stableAttributes.entrySet()) {
            if (!entry.getValue().equals(values.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<AttributeType, Integer> attributeValues(HttpResponse response, AttributeType[] types) {
        Map<AttributeType, Integer> values = new EnumMap<>(AttributeType.class);
        List<Attribute> attributes = response.attributes(types);
        if (attributes != null) {
            for (Attribute attribute : attributes) {
                if (attribute != null && attribute.type() != null) {
                    values.put(attribute.type(), attribute.value());
                }
            }
        }
        return values;
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
