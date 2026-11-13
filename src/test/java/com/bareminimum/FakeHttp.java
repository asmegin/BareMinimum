package com.bareminimum;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
            int colon = line.indexOf(':');
            headers.add(header(line.substring(0, colon), line.substring(colon + 1).trim()));
        }
        return build(mode, method, path, headers, body == null ? "" : body);
    }

    private static HttpRequest build(RemoveMode mode, String method, String path, List<HttpHeader> headers, String body) {
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "method" -> method;
                    case "path" -> path;
                    case "pathWithoutQuery" -> path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
                    case "query" -> path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
                    case "headers" -> List.copyOf(headers);
                    case "bodyToString" -> body;
                    case "body" -> bytes(body);
                    case "toByteArray" -> bytes(render(method, path, headers, body));
                    case "httpService" -> null;
                    case "copyToTempFile" -> proxy;
                    case "withPath" -> build(mode, method, (String) args[0], headers, body);
                    case "withBody" -> build(mode, method, path, withContentLength(headers, (String) args[0]), (String) args[0]);
                    case "withAddedHeader" -> {
                        List<HttpHeader> next = new ArrayList<>(headers);
                        next.add(header((String) args[0], (String) args[1]));
                        yield build(mode, method, path, next, body);
                    }
                    case "withUpdatedHeader" -> {
                        List<HttpHeader> next = new ArrayList<>(headers);
                        for (int i = 0; i < next.size(); i++) {
                            if (next.get(i).name().equalsIgnoreCase((String) args[0])) {
                                next.set(i, header(next.get(i).name(), (String) args[1]));
                                break;
                            }
                        }
                        yield build(mode, method, path, next, body);
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
                        yield build(mode, method, path, next, body);
                    }
                    case "toString" -> render(method, path, headers, body);
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

    public static HttpResponse response(int statusCode, String body) {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "statusCode" -> (short) statusCode;
                    case "bodyToString" -> body;
                    case "body" -> bytes(body);
                    case "toString" -> statusCode + " " + body;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException("FakeHttp response: " + m.getName());
                });
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
