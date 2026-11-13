package com.bareminimum.core;

import com.bareminimum.core.JsonBodyHelper.JsonField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBodyHelperTest {

    @Test
    void parsesTopLevelMembersKeepingRawValues() {
        String json = "{\"userId\": 42, \"nested\": {\"a\": \"hello, \\\"world\\\"\"}, \"arr\": [1, 2, 3], \"tracking\": \"junk\"}";
        List<JsonField> fields = JsonBodyHelper.parseTopLevelObject(json);

        assertEquals(List.of("userId", "nested", "arr", "tracking"), fields.stream().map(JsonField::name).toList());
        assertEquals("42", fields.get(0).rawValue());
        assertEquals("{\"a\": \"hello, \\\"world\\\"\"}", fields.get(1).rawValue());
        assertEquals("[1, 2, 3]", fields.get(2).rawValue());
        assertEquals("\"junk\"", fields.get(3).rawValue());
    }

    @Test
    void rebuildKeepsOnlyTheGivenMembersVerbatim() {
        List<JsonField> fields = JsonBodyHelper.parseTopLevelObject("{\"a\":1,\"b\":{\"x\":[true,null]},\"c\":\"z\"}");
        String rebuilt = JsonBodyHelper.rebuildJsonObject(List.of(fields.get(1), fields.get(2)));

        assertEquals("{\"b\": {\"x\":[true,null]}, \"c\": \"z\"}", rebuilt);
        assertEquals(Set.of("b", "c"), JsonBodyHelper.topLevelKeys(rebuilt));
        assertEquals("{}", JsonBodyHelper.rebuildJsonObject(List.of()));
    }

    @Test
    void escapedKeysAreDecodedForDisplayButWrittenBackUntouched() {
        // A unicode escape and an escaped quote in the key must survive a rebuild byte-for-byte.
        String json = "{\"caf\\u00e9\": 1, \"say \\\"hi\\\"\": 2}";
        List<JsonField> fields = JsonBodyHelper.parseTopLevelObject(json);

        assertEquals("caf\u00e9", fields.get(0).name());
        assertEquals("say \"hi\"", fields.get(1).name());
        assertEquals("{\"caf\\u00e9\": 1, \"say \\\"hi\\\"\": 2}", JsonBodyHelper.rebuildJsonObject(fields));
    }

    @Test
    void duplicateKeysArePreservedInOrder() {
        List<JsonField> fields = JsonBodyHelper.parseTopLevelObject("{\"a\":1,\"a\":2}");
        assertEquals(2, fields.size());
        assertEquals("{\"a\": 2}", JsonBodyHelper.rebuildJsonObject(List.of(fields.get(1))));
    }

    @Test
    void toleratesSurroundingWhitespaceAndEmptyObject() {
        assertEquals(Set.of("a"), JsonBodyHelper.topLevelKeys(" \r\n{ \"a\" : -1.5e+3 }\n"));
        assertTrue(JsonBodyHelper.parseTopLevelObject("{}").isEmpty());
        assertTrue(JsonBodyHelper.parseTopLevelObject(" { } ").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "[1,2,3]",
            "\"just a string\"",
            "a=1&b=2",
            "<html>{}</html>",
            "{",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\":1,}",
            "{,\"a\":1}",
            "{\"a\":1 \"b\":2}",
            "{a:1}",
            "{'a':1}",
            "{\"a\":tru}",
            "{\"a\":01}",
            "{\"a\":1}trailing",
            "{\"a\":\"unterminated}",
            "{\"a\":[1,2}",
            "{\"a\":{\"b\":1}",
            "{\"a\":1}{\"b\":2}"
    })
    void rejectsAnythingThatIsNotAWellFormedObject(String input) {
        assertTrue(JsonBodyHelper.parseTopLevelObject(input).isEmpty(), "should reject: " + input);
    }

    @Test
    void nullInputIsNotJson() {
        assertTrue(JsonBodyHelper.parseTopLevelObject(null).isEmpty());
        assertTrue(JsonBodyHelper.topLevelKeys(null).isEmpty());
    }

    @Test
    void hostileInputCannotExhaustTheStackOrThrow() {
        // Untrusted response bodies are parsed, so pathological nesting must be handled iteratively.
        String deep = "{\"a\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}";
        assertDoesNotThrow(() -> assertEquals(Set.of("a"), JsonBodyHelper.topLevelKeys(deep)));

        String unbalanced = "{\"a\":" + "[".repeat(200_000) + "}";
        assertDoesNotThrow(() -> assertTrue(JsonBodyHelper.parseTopLevelObject(unbalanced).isEmpty()));

        assertDoesNotThrow(() -> JsonBodyHelper.parseTopLevelObject("{\"a\\"));
        assertDoesNotThrow(() -> JsonBodyHelper.parseTopLevelObject("{\"\\u12\":1}"));
    }
}
