package com.bareminimum.core;

import com.bareminimum.core.RequestParts.Multipart;
import com.bareminimum.core.RequestParts.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestPartsTest {

    @Test
    void cookiesKeepTheirExactText() {
        List<Segment> cookies = RequestParts.splitCookies("a=1;  empty= ; flag ;jwt=aaa.bbb==; ;");

        assertEquals(List.of("a", "empty", "flag", "jwt"), cookies.stream().map(Segment::name).toList());
        assertEquals(List.of("1", "", "", "aaa.bbb=="), cookies.stream().map(Segment::value).toList());
        // "empty=" and "flag" are different cookies and must not be normalized into each other.
        assertEquals(List.of("a=1", "empty=", "flag", "jwt=aaa.bbb=="), cookies.stream().map(Segment::raw).toList());
        assertTrue(RequestParts.splitCookies(null).isEmpty());
        assertTrue(RequestParts.splitCookies("  ").isEmpty());
    }

    @Test
    void pairsSplitOnAmpersandWithoutDecoding() {
        List<Segment> pairs = RequestParts.splitPairs("a=1&a=2&&flag&q=x%26y%3Dz&e=");

        assertEquals(List.of("a", "a", "flag", "q", "e"), pairs.stream().map(Segment::name).toList());
        assertEquals(List.of("1", "2", "", "x%26y%3Dz", ""), pairs.stream().map(Segment::value).toList());
        assertEquals("a=2&q=x%26y%3Dz", RequestParts.joinPairs(List.of(pairs.get(1), pairs.get(3))));
        assertTrue(RequestParts.splitPairs("").isEmpty());
        assertTrue(RequestParts.splitPairs(null).isEmpty());
    }

    @Test
    void urlEncodedHeuristicIsConservative() {
        assertTrue(RequestParts.looksUrlEncoded("a=1&b=two"));
        assertTrue(RequestParts.looksUrlEncoded("token="));
        assertFalse(RequestParts.looksUrlEncoded("{\"a\":1}"));
        assertFalse(RequestParts.looksUrlEncoded("<x a=\"1\"/>"));
        assertFalse(RequestParts.looksUrlEncoded("plain text with = sign"));
        assertFalse(RequestParts.looksUrlEncoded(""));
        assertFalse(RequestParts.looksUrlEncoded(null));
    }

    @Test
    void boundaryIsReadFromContentType() {
        assertEquals("XyZ", RequestParts.multipartBoundary("multipart/form-data; boundary=XyZ"));
        assertEquals("a b", RequestParts.multipartBoundary("Multipart/Form-Data; charset=utf-8; boundary=\"a b\""));
        assertNull(RequestParts.multipartBoundary("multipart/form-data"));
        assertNull(RequestParts.multipartBoundary("application/json; boundary=x"));
        assertNull(RequestParts.multipartBoundary(null));
    }

    @Test
    void multipartRoundTripsAndDropsWholeParts() {
        String body = "--B\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--B\r\n"
                + "Content-Disposition: form-data; name=\"upload\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "line1\r\n--Bogus is not a boundary\r\nline3\r\n"
                + "--B\r\n"
                + "Content-Disposition: form-data; name=\"empty\"\r\n\r\n"
                + "\r\n"
                + "--B--\r\n";

        Multipart multipart = RequestParts.parseMultipart(body, "B");
        assertNotNull(multipart);
        List<Segment> parts = multipart.parts();
        assertEquals(List.of("title", "upload", "empty"), parts.stream().map(Segment::name).toList());
        assertEquals("hello", parts.get(0).value());
        assertTrue(parts.get(1).value().startsWith("[file \"a.bin\""));
        assertEquals("", parts.get(2).value());

        // Keeping everything reproduces the body exactly.
        assertEquals(body, multipart.rebuild(parts));

        String withoutUpload = multipart.rebuild(List.of(parts.get(0), parts.get(2)));
        assertFalse(withoutUpload.contains("a.bin"));
        assertEquals(List.of("title", "empty"),
                RequestParts.parseMultipart(withoutUpload, "B").parts().stream().map(Segment::name).toList());

        assertEquals("--B--\r\n", multipart.rebuild(List.of()));
    }

    @Test
    void multipartWithBareLineFeedsKeepsThatStyle() {
        String body = "--B\nContent-Disposition: form-data; name=x\n\n1\n--B\nContent-Disposition: form-data; name=y\n\n2\n--B--\n";
        Multipart multipart = RequestParts.parseMultipart(body, "B");

        assertNotNull(multipart);
        assertEquals("\n", multipart.lineEnding());
        assertEquals(List.of("x", "y"), multipart.parts().stream().map(Segment::name).toList());
        assertEquals(body, multipart.rebuild(multipart.parts()));
    }

    @Test
    void malformedMultipartIsRejectedRatherThanGuessed() {
        assertNull(RequestParts.parseMultipart("--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n", "B"));
        assertNull(RequestParts.parseMultipart("no boundary here", "B"));
        assertNull(RequestParts.parseMultipart("", "B"));
        assertNull(RequestParts.parseMultipart("--B--\r\n", null));
        assertNull(RequestParts.parseMultipart(null, "B"));

        Multipart empty = RequestParts.parseMultipart("--B--\r\n", "B");
        assertNotNull(empty);
        assertTrue(empty.parts().isEmpty());
    }
}
