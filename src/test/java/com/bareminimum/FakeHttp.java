package com.bareminimum;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.responses.analysis.Attribute;
import burp.api.montoya.http.message.responses.analysis.AttributeType;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal in-memory stand-ins for the Montoya HTTP message interfaces, which normally only exist
 * inside Burp. A fake request is just a method, a request target, an ordered header list and a
 * body string, and supports exactly the operations BareMinimum relies on.
 */
public final class FakeHttp {

    /**
     * Montoya does not say whether {@code withRemovedHeader(name)} removes one matching header or
     * all of them, so the tests run against both behaviours.
     */
    public enum RemoveMode { FIRST_MATCH, ALL_MATCHES }

    private FakeHttp() {
    }

    public static HttpHeader header(String name, String value) {
        return new HttpHeader() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String value() {
                return value;
            }

            @Override
            public String toString() {
                return name + ": " + value;
            }
        };
    }

    /** Builds a request from header lines of the form {@code "Name: value"}. */
    public static HttpRequest request(String method, String path, String body, String... headerLines) {
        return request(RemoveMode.FIRST_MATCH, method, path, body, headerLines);
    }

    public static HttpRequest request(RemoveMode mode, String method, String path, String body, String... headerLines) {
        List<HttpHeader> headers = new ArrayList<>();
        for (String line : headerLines) {
            int colon = line.startsWith(":") ? line.indexOf(':', 1) : line.indexOf(':');
            headers.add(header(line.substring(0, colon), line.substring(colon + 1).trim()));
        }
        return build(mode, method, path, headers, body == null ? "" : body, false);
    }

    /**
     * Builds an HTTP/2 request whose {@code headers()} includes the {@code :method},
     * {@code :scheme}, {@code :path}, and {@code :authority} pseudo-headers, matching Montoya's
     * HTTP/2 representation (where removing {@code :path} resets {@code path()} to {@code "/"}).
     */
    public static HttpRequest http2Request(
            RemoveMode mode,
            String method,
            String path,
            String authority,
            String body,
            String... headerLines) {
        List<HttpHeader> headers = new ArrayList<>();
        headers.add(header(":method", method));
        headers.add(header(":scheme", "https"));
        headers.add(header(":path", path));
        headers.add(header(":authority", authority));
        for (String line : headerLines) {
            int colon = line.startsWith(":") ? line.indexOf(':', 1) : line.indexOf(':');
            headers.add(header(line.substring(0, colon), line.substring(colon + 1).trim()));
        }
        return build(mode, method, path, headers, body == null ? "" : body, true);
    }

    public static HttpRequest http2Request(
            String method,
            String path,
            String authority,
            String body,
            String... headerLines) {
        return http2Request(RemoveMode.FIRST_MATCH, method, path, authority, body, headerLines);
    }

    private static String effectivePath(String path, List<HttpHeader> headers, boolean http2) {
        if (!http2) {
            return path;
        }
        for (HttpHeader h : headers) {
            if (":path".equalsIgnoreCase(h.name())) {
                return h.value();
            }
        }
        return "/";
    }

    private static HttpRequest build(
            RemoveMode mode,
            String method,
            String path,
            List<HttpHeader> headers,
            String body,
            boolean http2) {
        String currentPath = effectivePath(path, headers, http2);
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "method" -> method;
                    case "path" -> currentPath;
                    case "pathWithoutQuery" -> currentPath.contains("?")
                            ? currentPath.substring(0, currentPath.indexOf('?'))
                            : currentPath;
                    case "query" -> currentPath.contains("?")
                            ? currentPath.substring(currentPath.indexOf('?') + 1)
                            : "";
                    case "headers" -> List.copyOf(headers);
                    case "bodyToString" -> body;
                    case "body" -> bytes(body);
                    case "toByteArray" -> bytes(render(method, currentPath, headers, body));
                    case "httpService" -> null;
                    case "copyToTempFile" -> proxy;
                    case "withPath" -> {
                        String newPath = (String) args[0];
                        List<HttpHeader> next = new ArrayList<>(headers);
                        if (http2) {
                            boolean updated = false;
                            for (int i = 0; i < next.size(); i++) {
                                if (":path".equalsIgnoreCase(next.get(i).name())) {
                                    next.set(i, header(next.get(i).name(), newPath));
                                    updated = true;
                                    break;
                                }
                            }
                            if (!updated) {
                                next.add(0, header(":path", newPath));
                            }
                        }
                        yield build(mode, method, newPath, next, body, http2);
                    }
                    case "withBody" -> build(mode, method, currentPath,
                            withContentLength(headers, (String) args[0]), (String) args[0], http2);
                    case "withAddedHeader" -> {
                        List<HttpHeader> next = new ArrayList<>(headers);
                        next.add(header((String) args[0], (String) args[1]));
                        yield build(mode, method, currentPath, next, body, http2);
                    }
                    case "withUpdatedHeader" -> {
                        List<HttpHeader> next = new ArrayList<>(headers);
                        for (int i = 0; i < next.size(); i++) {
                            if (next.get(i).name().equalsIgnoreCase((String) args[0])) {
                                next.set(i, header(next.get(i).name(), (String) args[1]));
                                break;
                            }
                        }
                        yield build(mode, method, currentPath, next, body, http2);
                    }
                    case "withRemovedHeader" -> {
                        String name = (String) args[0];
                        List<HttpHeader> next = new ArrayList<>(headers);
                        if (mode == RemoveMode.ALL_MATCHES) {
                            next.removeIf(h -> h.name().equalsIgnoreCase(name));
                        } else {
                            for (int i = 0; i < next.size(); i++) {
                                if (next.get(i).name().equalsIgnoreCase(name)) {
                                    next.remove(i);
                                    break;
                                }
                            }
                        }
                        yield build(mode, method, currentPath, next, body, http2);
                    }
                    case "toString" -> render(method, currentPath, headers, body);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("FakeHttp request: " + m.getName());
                });
    }

    private static List<HttpHeader> withContentLength(List<HttpHeader> headers, String newBody) {
        List<HttpHeader> next = new ArrayList<>(headers);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).name().equalsIgnoreCase("Content-Length")) {
                next.set(i, header(next.get(i).name(), String.valueOf(newBody.length())));
            }
        }
        return next;
    }

    private static String render(String method, String path, List<HttpHeader> headers, String body) {
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        for (HttpHeader h : headers) {
            sb.append(h.name()).append(": ").append(h.value()).append("\r\n");
        }
        return sb.append("\r\n").append(body).toString();
    }

    private static final AtomicInteger LAST_MODIFIED = new AtomicInteger();

    /**
     * A response that also supports {@code attributes(...)}, with a few of Burp's response
     * attributes worked out from the status and body: status code, content length, body content,
     * word count and page title. Last-Modified differs on every call, as it does on many servers.
     */
    public static HttpResponse response(int statusCode, String body) {
        return response(statusCode, body, true);
    }

    /** A response on which Burp's response analysis fails. */
    public static HttpResponse responseWithoutAnalysis(int statusCode, String body) {
        return response(statusCode, body, false);
    }

    private static HttpResponse response(int statusCode, String body, boolean analysable) {
        int lastModified = LAST_MODIFIED.incrementAndGet();
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "statusCode" -> (short) statusCode;
                    case "bodyToString" -> body;
                    case "body" -> bytes(body);
                    case "attributes" -> {
                        if (!analysable) {
                            throw new UnsupportedOperationException("response analysis");
                        }
                        yield attributes((AttributeType[]) args[0], statusCode, body, lastModified);
                    }
                    case "toString" -> statusCode + " " + body;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("FakeHttp response: " + m.getName());
                });
    }

    private static List<Attribute> attributes(AttributeType[] types, int statusCode, String body, int lastModified) {
        List<Attribute> result = new ArrayList<>();
        for (AttributeType type : types) {
            Integer value = switch (type) {
                case STATUS_CODE -> statusCode;
                case CONTENT_LENGTH -> body.length();
                case BODY_CONTENT -> body.hashCode();
                case WORD_COUNT -> body.isBlank() ? 0 : body.trim().split("\\s+").length;
                case PAGE_TITLE -> {
                    int start = body.indexOf("<title>");
                    int end = body.indexOf("</title>");
                    yield start >= 0 && end > start ? body.substring(start + 7, end).hashCode() : 0;
                }
                case LAST_MODIFIED_HEADER -> lastModified;
                default -> null;
            };
            if (value != null) {
                int v = value;
                result.add(new Attribute() {
                    @Override
                    public AttributeType type() {
                        return type;
                    }

                    @Override
                    public int value() {
                        return v;
                    }
                });
            }
        }
        return result;
    }

    private static ByteArray bytes(String text) {
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        return (ByteArray) Proxy.newProxyInstance(
                ByteArray.class.getClassLoader(),
                new Class<?>[]{ByteArray.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "length" -> data.length;
                    case "getBytes" -> data.clone();
                    case "toString" -> text;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("FakeHttp bytes: " + m.getName());
                });
    }

    /** Header lines of a request as {@code "Name: value"} strings, in order. */
    public static List<String> headerLines(HttpRequest request) {
        return request.headers().stream().map(h -> h.name() + ": " + h.value()).toList();
    }
}
