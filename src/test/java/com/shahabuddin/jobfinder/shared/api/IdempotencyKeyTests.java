package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The published key format (docs/adr/0014, docs/problems.md): RFC 9651 String, or a bare token. */
class IdempotencyKeyTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324\"  | 8e03978e-40d5-43e8-bc93-6894a57f9324",
            "8e03978e-40d5-43e8-bc93-6894a57f9324      | 8e03978e-40d5-43e8-bc93-6894a57f9324",
            "\"clkyoesmbgybucifusbbtdsbohtyuuwz\"      | clkyoesmbgybucifusbbtdsbohtyuuwz",
            "01ARZ3NDEKTSV4RRFFQ69G5FAV                | 01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "\"order 42 for customer 7\"               | order 42 for customer 7",
            "'\"say \\\"hi\\\" twice, please\"'        | 'say \"hi\" twice, please'",
            "'\"back\\\\slash-0123456789\"'            | 'back\\slash-0123456789'"})
    void validKeys(String header, String key) {
        assertThat(IdempotencyKey.parse(header)).isEqualTo(new IdempotencyKey(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "\"\"",
            "short-key",
            "\"0123456789abcde\"",                            // 15 characters
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324",          // unterminated
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324\";a=1",    // parameters
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324\" ",       // trailing text
            "\"8e03978e-40d5-43e8\\n-bc93-6894a57f9324\"",     // escape other than \" \\
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324\\\"",      // escaped closing quote: unterminated
            "\"8e03978e-40d5-43e8-bc93-6894a57f93é4\"",        // not ASCII
            "\"8e03978e-40d5-43e8-bc93-6894a57f\t9324\"",      // control character
            "8e03978e 40d5 43e8 bc93 6894a57f9324",            // bare with spaces
            "8e03978e-40d5-43e8-bc93-6894a57f9324,next",       // bare list
            // Just outside each allowed range of a bare key: ` { @ [
            "0123456789abcde`", "0123456789abcde{", "0123456789abcde@", "0123456789abcde[",
            // Just outside the printable range of a quoted key: 0x1F, 0x7F
            "\"0123456789abcde\u001F\"", "\"0123456789abcde\u007F\""})
    void invalidKeys(String header) {
        assertThat(IdempotencyKey.parse(header)).isNull();
    }

    /** Found by mutation testing: no key used the first or last character of an allowed range. */
    @ParameterizedTest
    @ValueSource(strings = {
            "azAZ09-_.~:+/=az",          // a bare key of exactly 16 characters, every range boundary
            "\" ~ ~ ~ ~ ~ ~ ~ ~\""})       // quoted: space (0x20) and ~ (0x7E), the printable boundaries
    void boundaryCharactersAreValid(String header) {
        assertThat(IdempotencyKey.parse(header)).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"255, true", "256, false"})
    void atMost255Characters(int length, boolean valid) {
        assertThat(IdempotencyKey.parse("k".repeat(length)) != null).isEqualTo(valid);
        assertThat(IdempotencyKey.parse("\"" + "k".repeat(length) + "\"") != null).isEqualTo(valid);
    }
}
