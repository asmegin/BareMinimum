package com.bareminimum.core;

import com.bareminimum.core.JsonBodyHelper.JsonField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    // --- Full tree --------------------------------------------------------------------------

    @Test
    void treeListsNestedMembersInDocumentOrderWithPaths() {
        JsonBodyHelper.Tree tree = JsonBodyHelper.parseTree(
                " {\"a\": {\"b\": [1, {\"c\": null}]}, \"caf\\u00e9\": \"x\", \"e\": [], \"f\": {}} ");
        assertNotNull(tree);
        assertEquals(List.of("a", "a.b", "a.b[0]", "a.b[1]", "a.b[1].c", "caf\u00e9", "e", "f"),
                tree.nodes().stream().map(node -> node.path).toList());
        assertEquals(List.of(-1, 0, 1, 1, 3, -1, -1, -1), tree.nodes().stream().map(node -> node.parent).toList());
        assertEquals(List.of(1, 2, 3, 3, 4, 1, 1, 1), tree.nodes().stream().map(node -> node.depth).toList());
        assertEquals("[1, {\"c\": null}]", tree.rawValue(tree.nodes().get(1)));
        assertEquals("\"x\"", tree.rawValue(tree.nodes().get(5)));
    }

    @Test
    void treeRenderingRewritesOnlyContainersThatLostMembers() {
        String json = " {\"a\":{\"x\":1,\"y\":2},\"b\":[1,2,3],\"c\":{ \"keep\" : true }} ";
        JsonBodyHelper.Tree tree = JsonBodyHelper.parseTree(json);
        assertNotNull(tree);
        // nodes: a, a.x, a.y, b, b[0], b[1], b[2], c, c.keep
        boolean[] removed = new boolean[tree.nodes().size()];
        assertSame(json, tree.render(removed));

        removed[2] = true; // a.y
        removed[5] = true; // b[1]
        assertEquals(" {\"a\": {\"x\": 1}, \"b\": [1, 3], \"c\": { \"keep\" : true }} ", tree.render(removed));

        boolean[] all = new boolean[tree.nodes().size()];
        java.util.Arrays.fill(all, true);
        assertEquals(" {} ", tree.render(all));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "1", "\"s\"", "{", "[", "[1,]", "[,1]", "[1 2]", "{\"a\":1,}", "{\"a\":[1,}",
            "{\"a\":{\"b\":1}", "[1]]", "[1]x", "{\"a\":01}", "[tru]", "{\"a\":\"x}", "{\"a\" 1}"
    })
    void treeRejectsMalformedDocuments(String input) {
        assertNull(JsonBodyHelper.parseTree(input), "should reject: " + input);
    }

    @Test
    void treeHandlesPathologicalNestingWithoutRecursion() {
        String deep = "[".repeat(200_000) + "]".repeat(200_000);
        JsonBodyHelper.Tree tree = assertDoesNotThrow(() -> JsonBodyHelper.parseTree(deep));
        assertNotNull(tree);
        assertEquals(199_999, tree.nodes().size());
        assertNull(JsonBodyHelper.parseTree("[".repeat(200_000)));
    }
}
