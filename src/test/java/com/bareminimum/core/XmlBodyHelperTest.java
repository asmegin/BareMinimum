package com.bareminimum.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class XmlBodyHelperTest {

    private static List<String> paths(XmlBodyHelper.Tree tree) {
        return tree.nodes().stream().map(node -> node.path).toList();
    }

    @Test
    void rejectsAnythingThatIsNotOneWellFormedRootElement() {
        assertNull(XmlBodyHelper.parse(null));
        assertNull(XmlBodyHelper.parse(""));
        assertNull(XmlBodyHelper.parse("just text"));
        assertNull(XmlBodyHelper.parse("<a><b></a>"));
        assertNull(XmlBodyHelper.parse("<a></b>"));
        assertNull(XmlBodyHelper.parse("<a/><b/>"));
        assertNull(XmlBodyHelper.parse("<a/>trailing"));
        assertNull(XmlBodyHelper.parse("<a x=1/>"));
        assertNull(XmlBodyHelper.parse("<a x=\"1\"y=\"2\"/>"));
        assertNull(XmlBodyHelper.parse("<a x=\"<\"/>"));
        assertNull(XmlBodyHelper.parse("<a><!-- never closed </a>"));
        assertNull(XmlBodyHelper.parse("<a>"));
    }

    @Test
    void handlesPrologCommentsCdataAndSelfClosingTags() {
        XmlBodyHelper.Tree tree = XmlBodyHelper.parse("﻿<?xml version=\"1.0\"?>\n<!-- c -->\n"
                + "<r><a><![CDATA[<not-a-tag/>]]></a><b k='v &amp; w'/><!-- <c/> --></r>\n");
        assertNotNull(tree);
        assertEquals(List.of("r/a", "r/b", "r/b/@k"), paths(tree));
        assertEquals("<![CDATA[<not-a-tag/>]]>", tree.value(tree.nodes().get(0)));
        assertEquals("v &amp; w", tree.value(tree.nodes().get(2)));
    }

    @Test
    void doctypeIsSkippedAndEntitiesAreNeverExpanded() {
        String xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x><y>&e;</y><z/></x>";
        XmlBodyHelper.Tree tree = XmlBodyHelper.parse(xml);
        assertNotNull(tree);
        assertEquals(List.of("x/y", "x/z"), paths(tree));
        assertEquals("&e;", tree.value(tree.nodes().get(0)));
        assertEquals("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x><z/></x>",
                tree.render(new boolean[]{true, false}));
    }

    @Test
    void namespaceDeclarationsAreNeverOfferedForRemoval() {
        XmlBodyHelper.Tree tree = XmlBodyHelper.parse("<r xmlns=\"urn:a\" xmlns:p=\"urn:p\" p:id=\"1\"><p:c/></r>");
        assertNotNull(tree);
        assertEquals(List.of("r/@p:id", "r/p:c"), paths(tree));
    }

    @Test
    void renderingCutsRemovedNodesWithTheirIndentationAndKeepsEverythingElse() {
        String xml = "<r>\n  <a x=\"1\"  y=\"2\">\n    <b>1</b>\n  </a>\n  <c>keep</c>\n</r>";
        XmlBodyHelper.Tree tree = XmlBodyHelper.parse(xml);
        assertNotNull(tree);
        assertEquals(List.of("r/a", "r/a/@x", "r/a/@y", "r/a/b", "r/c"), paths(tree));

        assertSame(xml, tree.render(new boolean[5]));
        assertEquals("<r>\n  <a x=\"1\">\n    <b>1</b>\n  </a>\n  <c>keep</c>\n</r>",
                tree.render(new boolean[]{false, false, true, false, false}));
        // A removed element takes its attributes and children with it.
        assertEquals("<r>\n  <c>keep</c>\n</r>", tree.render(new boolean[]{true, false, true, true, false}));
        assertEquals("<r>\n</r>", tree.render(new boolean[]{true, true, true, true, true}));
    }

    @Test
    void deeplyNestedInputDoesNotOverflowTheStack() {
        String xml = "<a>".repeat(100_000) + "</a>".repeat(100_000);
        XmlBodyHelper.Tree tree = XmlBodyHelper.parse(xml);
        assertNotNull(tree);
        assertEquals(99_999, tree.nodes().size());
        assertNull(tree.nodes().get(99_998).path);
        assertEquals("a/a", tree.nodes().get(0).path);
    }
}
