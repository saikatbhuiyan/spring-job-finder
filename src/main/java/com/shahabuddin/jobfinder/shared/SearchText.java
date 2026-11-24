package com.shahabuddin.jobfinder.shared;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Normalizes free-text search input (see docs/adr/0011-filtering-and-search.md): surrounding
 * whitespace removed, inner whitespace collapsed to one space, and a blank value treated as
 * <b>no search</b>, since an empty search box submits {@code ?q=}.
 *
 * <p>Used in the compact constructor of a filter record, so that Bean Validation (e.g.
 * {@code @Size(min = 2, max = 100)}) checks the normalized text:
 *
 * <pre>{@code
 * record JobFilter(@Size(min = 2, max = 100) String q, ...) {
 *     JobFilter { q = SearchText.normalize(q); }
 * }
 * }</pre>
 *
 * Escaping for SQL {@code LIKE} or building a full-text query is the persistence layer's job.
 */
public final class SearchText {

    /** Any run of whitespace, including Unicode spaces such as no-break space. */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private SearchText() {
    }

    /** The normalized text, or {@code null} if the input is {@code null} or only whitespace. */
    public static @Nullable String normalize(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String normalized = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
