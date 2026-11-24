package com.shahabuddin.jobfinder.shared;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTests {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "  "})  // incl. no-break and em space
    void blankIsNoSearch(String text) {
        assertThat(SearchText.normalize(text)).isNull();
    }

    @Test
    void whitespaceIsTrimmedAndCollapsed() {
        assertThat(SearchText.normalize("  java \t\n  developer  ")).isEqualTo("java developer");
    }

    @Test
    void textIsOtherwiseUnchanged() {
        // Case, punctuation and wildcards are kept: escaping is the persistence layer's job.
        assertThat(SearchText.normalize("C++ 100%_Remote")).isEqualTo("C++ 100%_Remote");
    }
}
