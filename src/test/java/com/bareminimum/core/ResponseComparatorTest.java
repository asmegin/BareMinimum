package com.bareminimum.core;

import burp.api.montoya.http.message.responses.HttpResponse;
import com.bareminimum.FakeHttp;
import com.bareminimum.model.MinimizationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseComparatorTest {
    private static final HttpResponse BASELINE = FakeHttp.response(200, "{\"user\": \"bob\", \"balance\": 100}");

    @Test
    void identicalResponseIsEquivalentAndNullIsNot() {
        ResponseComparator comparator = new ResponseComparator(BASELINE, new MinimizationConfig());

        assertTrue(comparator.isEquivalent(FakeHttp.response(200, "{\"user\": \"bob\", \"balance\": 100}")));
        assertFalse(comparator.isEquivalent(null));
        assertEquals(200, comparator.getBaselineStatusCode());
        assertEquals(31, comparator.getBaselineBodyLength());
    }

    @Test
    void statusCodeRuleCanBeSwitchedOff() {
        MinimizationConfig config = new MinimizationConfig();
        HttpResponse sameBodyOtherStatus = FakeHttp.response(203, "{\"user\": \"bob\", \"balance\": 100}");

        assertFalse(new ResponseComparator(BASELINE, config).isEquivalent(sameBodyOtherStatus));
        config.setMatchStatusCode(false);
        assertTrue(new ResponseComparator(BASELINE, config).isEquivalent(sameBodyOtherStatus));
    }

    @Test
    void jsonKeyStructureMustMatchWhenBaselineIsAJsonObject() {
        MinimizationConfig config = new MinimizationConfig();
        // Same length as the baseline, different top-level keys.
        HttpResponse otherKeys = FakeHttp.response(200, "{\"oops\": \"bob\", \"balance\": 100}");

        assertFalse(new ResponseComparator(BASELINE, config).isEquivalent(otherKeys));
        config.setCheckJsonStructure(false);
        assertTrue(new ResponseComparator(BASELINE, config).isEquivalent(otherKeys));
    }

    @Test
    void bodyLengthToleranceIsAPercentageWithASmallAbsoluteFloor() {
        MinimizationConfig config = new MinimizationConfig();
        config.setCheckJsonStructure(false);
        HttpResponse baseline = FakeHttp.response(200, "a".repeat(1000));

        ResponseComparator fivePercent = new ResponseComparator(baseline, config);
        assertTrue(fivePercent.isEquivalent(FakeHttp.response(200, "a".repeat(1050))));
        assertTrue(fivePercent.isEquivalent(FakeHttp.response(200, "a".repeat(950))));
        assertFalse(fivePercent.isEquivalent(FakeHttp.response(200, "a".repeat(1051))));

        config.setBodyLengthTolerancePercent(0);
        ResponseComparator exact = new ResponseComparator(baseline, config);
        assertTrue(exact.isEquivalent(FakeHttp.response(200, "b".repeat(1000))));
        assertFalse(exact.isEquivalent(FakeHttp.response(200, "a".repeat(1001))));

        config.setBodyLengthTolerancePercent(5);
        ResponseComparator tiny = new ResponseComparator(FakeHttp.response(200, "ok"), config);
        assertTrue(tiny.isEquivalent(FakeHttp.response(200, "ok, thanks")));
        assertFalse(tiny.isEquivalent(FakeHttp.response(200, "x".repeat(40))));

        config.setMatchBodyLength(false);
        assertTrue(new ResponseComparator(baseline, config).isEquivalent(FakeHttp.response(200, "")));
    }

    @Test
    void requiredAndForbiddenSubstringsAreChecked() {
        MinimizationConfig config = new MinimizationConfig();
        config.setMatchBodyLength(false);
        config.setCheckJsonStructure(false);
        config.setRequiredSubstring("Welcome");
        config.setForbiddenSubstring("Sign in");
        ResponseComparator comparator = new ResponseComparator(FakeHttp.response(200, "Welcome back"), config);

        assertTrue(comparator.isEquivalent(FakeHttp.response(200, "Welcome, bob")));
        assertFalse(comparator.isEquivalent(FakeHttp.response(200, "Hello, bob")));
        assertFalse(comparator.isEquivalent(FakeHttp.response(200, "Welcome. Sign in to continue")));
    }

    @Test
    void comparatorUsesTheSettingsItWasGiven() {
        MinimizationConfig live = new MinimizationConfig();
        ResponseComparator comparator = new ResponseComparator(BASELINE, live.snapshot());

        live.setMatchStatusCode(false);

        assertFalse(comparator.isEquivalent(FakeHttp.response(500, "{\"user\": \"bob\", \"balance\": 100}")));
    }
}
