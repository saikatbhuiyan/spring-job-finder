package com.shahabuddin.jobfinder.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * A validated {@code Idempotency-Key} (docs/adr/0014-idempotency-keys.md). The draft standard
 * (draft-ietf-httpapi-idempotency-key-header) defines the value as a Structured Field String,
 * {@code "8e03978e-40d5-43e8-bc93-6894a57f9324"}; widely used clients send it unquoted, so a bare
 * token is accepted as the same key.
 *
 * <p>The draft's security section asks for a fixed, published format, validated before any lookup:
 * low-entropy keys ({@code "1"}, {@code "order"}) collide between clients and can be guessed. A UUID
 * is recommended; at least 16 characters are required.
 *
 * @param value the key without quotes or escapes
 */
record IdempotencyKey(String value) {

    static final String HEADER = "Idempotency-Key";

    static final int MIN_LENGTH = 16;
    static final int MAX_LENGTH = 255;

    /** For the {@code detail} of a validation error, and the docs. */
    static final String FORMAT = "must be a string of " + MIN_LENGTH + " to " + MAX_LENGTH
            + " printable ASCII characters, e.g. \"8e03978e-40d5-43e8-bc93-6894a57f9324\"";

    /** The request's key, or {@code null} if it has none or it is invalid (also: sent more than once). */
    static @Nullable IdempotencyKey of(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        return values.size() == 1 ? parse(values.getFirst()) : null;
    }

    static @Nullable IdempotencyKey parse(String header) {
        String key = header.startsWith("\"") ? unquote(header) : bare(header);
        return key != null && key.length() >= MIN_LENGTH && key.length() <= MAX_LENGTH ? new IdempotencyKey(key) : null;
    }

    /**
     * RFC 9651 §3.3.3 String: printable ASCII between double quotes, {@code \"} and {@code \\} escaped.
     * Nothing may follow the closing quote (an Item with parameters isn't a valid key).
     */
    private static @Nullable String unquote(String header) {
        StringBuilder key = new StringBuilder(header.length());
        for (int i = 1; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '\\') {
                if (++i == header.length() || (header.charAt(i) != '"' && header.charAt(i) != '\\')) {
                    return null;
                }
                key.append(header.charAt(i));
            } else if (c == '"') {
                return i == header.length() - 1 ? key.toString() : null;
            } else if (c < 0x20 || c > 0x7E) {
                return null;
            } else {
                key.append(c);
            }
        }
        return null; // no closing quote
    }

    /** Unquoted: the characters of UUIDs, ULIDs, base64 and similar random identifiers. */
    private static @Nullable String bare(String header) {
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-_.~:+/=".indexOf(c) >= 0;
            if (!allowed) {
                return null;
            }
        }
        return header;
    }
}
