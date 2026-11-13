package com.bareminimum.model;

/**
 * Defines which categories of HTTP request elements to minimize.
 */
public enum MinimizationTarget {
    COOKIES("Find required cookies", "COOKIES", "cookies", true, false, false),
    HEADERS("Find required headers", "HEADERS", "headers (other than pinned ones)", false, true, false),
    PARAMETERS("Find required parameters", "PARAMS", "URL, body or JSON parameters", false, false, true),
    ALL("Find required ALL", "ALL", "cookies, headers or parameters", true, true, true);

    private final String menuLabel;
    private final String shortCode;
    private final String itemNoun;
    private final boolean includesCookies;
    private final boolean includesHeaders;
    private final boolean includesParameters;

    MinimizationTarget(
            String menuLabel,
            String shortCode,
            String itemNoun,
            boolean includesCookies,
            boolean includesHeaders,
            boolean includesParameters) {
        this.menuLabel = menuLabel;
        this.shortCode = shortCode;
        this.itemNoun = itemNoun;
        this.includesCookies = includesCookies;
        this.includesHeaders = includesHeaders;
        this.includesParameters = includesParameters;
    }

    public String menuLabel() {
        return menuLabel;
    }

    public String shortCode() {
        return shortCode;
    }

    /** Human-readable description of the kind of items this target removes, for status messages. */
    public String itemNoun() {
        return itemNoun;
    }

    public boolean includesCookies() {
        return includesCookies;
    }

    public boolean includesHeaders() {
        return includesHeaders;
    }

    public boolean includesParameters() {
        return includesParameters;
    }
}
