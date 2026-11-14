package com.bareminimum.model;

import com.bareminimum.FakeHttp;
import com.bareminimum.core.ProfileStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    void profileNamesFollowMethodTargetCounterConvention() {
        ProfileStore store = new ProfileStore();
        assertEquals("GET - ALL 1", store.nextProfileName("GET", MinimizationTarget.ALL));
        assertEquals("GET - ALL 2", store.nextProfileName("get", MinimizationTarget.ALL));
        assertEquals("POST - COOKIES 1", store.nextProfileName("POST", MinimizationTarget.COOKIES));
        assertEquals("POST - COOKIES 2", store.nextProfileName("POST", MinimizationTarget.COOKIES));
        assertEquals("GET - HEADERS 1", store.nextProfileName("GET", MinimizationTarget.HEADERS));
        assertEquals("POST - PARAMS 1", store.nextProfileName("POST", MinimizationTarget.PARAMETERS));
        assertEquals("GET - ALL 3", store.nextProfileName(" ", MinimizationTarget.ALL));
    }

    @Test
    void storeTracksActiveProfileAcrossAddAndRemove() {
        ProfileStore store = new ProfileStore();
        var request = FakeHttp.request("GET", "/a?x=1", "", "Host: h", "Cookie: a=1");

        MinimizationProfile first = store.createAndSaveProfile(request, request, MinimizationTarget.COOKIES, 1,
                List.of(new RemovableItem(RemovableItem.Category.COOKIE, "a", "1", 0)), 3);
        MinimizationProfile second = store.createAndSaveProfile(request, request, MinimizationTarget.COOKIES, 1, List.of(), 3);

        assertEquals("/a", first.getEndpointPath());
        assertEquals(Set.of("a"), first.getRequiredCookies());
        assertEquals("1 cookie", first.getShortSummary());
        assertEquals("0 cookies", second.getShortSummary());
        assertSame(second, store.getActiveProfile());

        store.removeProfile(second);
        assertSame(first, store.getActiveProfile());
        store.clearAll();
        assertNull(store.getActiveProfile());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void renamingIgnoresBlankNames() {
        MinimizationProfile profile = new MinimizationProfile("GET - ALL 1", "GET", "/", MinimizationTarget.ALL,
                Set.of(), Set.of("X-Api-Key"), Set.of(), Set.of(), 1, 1, 1, null, null);

        profile.setName("   ");
        assertEquals("GET - ALL 1", profile.getName());
        profile.setName("  login flow ");
        assertEquals("login flow", profile.getName());
        assertTrue(profile.isHeaderRequired("x-api-key"));
        assertFalse(profile.isHeaderRequired("x-other"));
    }

    @Test
    void hostAndContentLengthAreAlwaysPinned() {
        MinimizationConfig config = new MinimizationConfig();
        assertTrue(config.isHeaderPinned("Host"));
        assertTrue(config.isHeaderPinned("content-length"));
        assertFalse(config.isHeaderPinned("User-Agent"));

        config.setPinnedHeadersFromCsv("Authorization; X-Csrf  user-agent,,");
        assertEquals("host, content-length, authorization, x-csrf, user-agent", config.getPinnedHeadersCsv());
        assertTrue(config.isHeaderPinned("AUTHORIZATION"));

        config.setPinnedHeadersFromCsv("");
        assertEquals("host, content-length", config.getPinnedHeadersCsv());
        config.setPinnedHeadersFromCsv(null);
        assertEquals("host, content-length", config.getPinnedHeadersCsv());
    }

    @Test
    void settingsAreClampedToSaneRanges() {
        MinimizationConfig config = new MinimizationConfig();

        config.setBodyLengthTolerancePercent(250);
        assertEquals(100.0, config.getBodyLengthTolerancePercent());
        config.setBodyLengthTolerancePercent(-3);
        assertEquals(0.0, config.getBodyLengthTolerancePercent());
        config.setBodyLengthTolerancePercent(Double.NaN);
        assertEquals(0.0, config.getBodyLengthTolerancePercent());

        config.setRequestDelayMs(-5);
        assertEquals(0, config.getRequestDelayMs());
        config.setRequestDelayMs(Integer.MAX_VALUE);
        assertEquals(MinimizationConfig.MAX_REQUEST_DELAY_MS, config.getRequestDelayMs());

        config.setRequiredSubstring(null);
        assertEquals("", config.getRequiredSubstring());
    }

    @Test
    void snapshotIsIndependentAndChangeListenerFiresOnEveryChange() {
        MinimizationConfig config = new MinimizationConfig();
        AtomicInteger changes = new AtomicInteger();
        config.setChangeListener(changes::incrementAndGet);

        config.setForbiddenSubstring("Unauthorized");
        config.setRequestDelayMs(250);
        assertEquals(2, changes.get());

        MinimizationConfig snapshot = config.snapshot();
        config.setForbiddenSubstring("changed");
        config.setVerifyBaselineStability(false);
        config.setPinnedHeadersFromCsv("x-later");

        assertEquals("Unauthorized", snapshot.getForbiddenSubstring());
        assertEquals(250, snapshot.getRequestDelayMs());
        assertTrue(snapshot.isVerifyBaselineStability());
        assertFalse(snapshot.isHeaderPinned("x-later"));

        // Changing the snapshot must not trigger the live config's listener.
        int before = changes.get();
        snapshot.setMatchStatusCode(false);
        assertEquals(before, changes.get());

        // A failing listener must never break the setter.
        config.setChangeListener(() -> {
            throw new IllegalStateException("disk full");
        });
        config.setMatchBodyLength(false);
        assertFalse(config.isMatchBodyLength());
    }

    @Test
    void responseMatchingDefaultsToRulesAndIsCopiedBySnapshots() {
        MinimizationConfig config = new MinimizationConfig();
        assertEquals(MinimizationConfig.ResponseMatching.RULES, config.getResponseMatching());

        config.setResponseMatching(MinimizationConfig.ResponseMatching.BURP_ANALYSIS);
        MinimizationConfig snapshot = config.snapshot();
        config.setResponseMatching(null);

        assertEquals(MinimizationConfig.ResponseMatching.RULES, config.getResponseMatching());
        assertEquals(MinimizationConfig.ResponseMatching.BURP_ANALYSIS, snapshot.getResponseMatching());
        assertTrue(new RemovableItem(RemovableItem.Category.XML_PARAM, "a/b", "", 0).category().isParameter());
    }
}
