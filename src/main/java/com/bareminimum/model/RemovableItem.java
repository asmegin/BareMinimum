package com.bareminimum.model;

import java.util.Objects;

/**
 * Represents a single removable component of an HTTP request (a cookie, header, or parameter).
 */
public record RemovableItem(
        Category category,
        String name,
        String value,
        int occurrenceIndex
) {
    public enum Category {
        COOKIE("Cookie"),
        HEADER("Header"),
        URL_PARAM("URL Param"),
        BODY_PARAM("Body Param"),
        JSON_PARAM("JSON Body Field");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public boolean isParameter() {
            return this == URL_PARAM || this == BODY_PARAM || this == JSON_PARAM;
        }
    }

    public RemovableItem {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(name, "name");
        value = value == null ? "" : value;
    }

    public String summary() {
        String preview = value.length() > 36 ? value.substring(0, 33) + "..." : value;
        return "[" + category.displayName() + "] " + name + "=" + preview;
    }
}
