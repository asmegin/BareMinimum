package com.bareminimum.model;

import burp.api.montoya.http.message.requests.HttpRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a saved minimization result profile (e.g., "GET - ALL 1" or "POST - COOKIES 3")
 * that can be selected and applied to minimize requests in Repeater or via the context menu.
 */
public final class MinimizationProfile {
    private volatile String name;
    private final String httpMethod;
    private final String endpointPath;
    private final MinimizationTarget target;
    private final Set<String> requiredCookies;
    private final Set<String> requiredHeaders;
    private final Set<String> requiredHeadersLower;
    private final Set<String> requiredUrlParams;
    private final Set<String> requiredBodyParams;
    private final int originalItemCount;
    private final int requiredItemCount;
    private final int requestsSent;
    private final HttpRequest minimizedRequest;
    private final Instant createdAt;

    public MinimizationProfile(
            String name,
            String httpMethod,
            String endpointPath,
            MinimizationTarget target,
            Set<String> requiredCookies,
            Set<String> requiredHeaders,
            Set<String> requiredUrlParams,
            Set<String> requiredBodyParams,
            int originalItemCount,
            int requiredItemCount,
            int requestsSent,
            HttpRequest minimizedRequest,
            Instant createdAt) {
        this.name = Objects.requireNonNull(name, "name");
        this.httpMethod = httpMethod == null ? "GET" : httpMethod;
        this.endpointPath = endpointPath == null ? "/" : endpointPath;
        this.target = Objects.requireNonNull(target, "target");
        this.requiredCookies = Collections.unmodifiableSet(new LinkedHashSet<>(requiredCookies));
        this.requiredHeaders = Collections.unmodifiableSet(new LinkedHashSet<>(requiredHeaders));
        this.requiredHeadersLower = this.requiredHeaders.stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.requiredUrlParams = Collections.unmodifiableSet(new LinkedHashSet<>(requiredUrlParams));
        this.requiredBodyParams = Collections.unmodifiableSet(new LinkedHashSet<>(requiredBodyParams));
        this.originalItemCount = originalItemCount;
        this.requiredItemCount = requiredItemCount;
        this.requestsSent = requestsSent;
        this.minimizedRequest = minimizedRequest;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public MinimizationTarget getTarget() {
        return target;
    }

    public Set<String> getRequiredCookies() {
        return requiredCookies;
    }

    public Set<String> getRequiredHeaders() {
        return requiredHeaders;
    }

    public boolean isHeaderRequired(String headerName) {
        return headerName != null && requiredHeadersLower.contains(headerName.trim().toLowerCase(Locale.ROOT));
    }

    public Set<String> getRequiredUrlParams() {
        return requiredUrlParams;
    }

    public Set<String> getRequiredBodyParams() {
        return requiredBodyParams;
    }

    public int getOriginalItemCount() {
        return originalItemCount;
    }

    public int getRequiredItemCount() {
        return requiredItemCount;
    }

    public int getRequestsSent() {
        return requestsSent;
    }

    public HttpRequest getMinimizedRequest() {
        return minimizedRequest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getShortSummary() {
        StringBuilder sb = new StringBuilder();
        if (target.includesCookies()) {
            sb.append(requiredCookies.size()).append(" cookie").append(requiredCookies.size() == 1 ? "" : "s");
        }
        if (target.includesHeaders()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(requiredHeaders.size()).append(" header").append(requiredHeaders.size() == 1 ? "" : "s");
        }
        if (target.includesParameters()) {
            int totalParams = requiredUrlParams.size() + requiredBodyParams.size();
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(totalParams).append(" param").append(totalParams == 1 ? "" : "s");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name + " (" + getShortSummary() + ")";
    }
}
