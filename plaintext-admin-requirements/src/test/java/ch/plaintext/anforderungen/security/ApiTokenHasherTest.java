/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenHasherTest {

    @Test
    void sha256HexProducesKnownVector() {
        // SHA-256("abc") — official NIST test vector
        assertThat(ApiTokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256HexIsDeterministicAnd64CharsLowercase() {
        String h1 = ApiTokenHasher.sha256Hex("some-uuid-token");
        String h2 = ApiTokenHasher.sha256Hex("some-uuid-token");
        assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void sha256HexDiffersForDifferentTokens() {
        assertThat(ApiTokenHasher.sha256Hex("token-a"))
                .isNotEqualTo(ApiTokenHasher.sha256Hex("token-b"));
    }

    @Test
    void sha256HexReturnsNullForNull() {
        assertThat(ApiTokenHasher.sha256Hex(null)).isNull();
    }

    @Test
    void constantTimeEqualsMatchesEqualStrings() {
        assertThat(ApiTokenHasher.constantTimeEquals("abc", "abc")).isTrue();
    }

    @Test
    void constantTimeEqualsRejectsDifferentStrings() {
        assertThat(ApiTokenHasher.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(ApiTokenHasher.constantTimeEquals("abc", "abcd")).isFalse();
        assertThat(ApiTokenHasher.constantTimeEquals("", "abc")).isFalse();
    }

    @Test
    void constantTimeEqualsRejectsNulls() {
        assertThat(ApiTokenHasher.constantTimeEquals(null, "abc")).isFalse();
        assertThat(ApiTokenHasher.constantTimeEquals("abc", null)).isFalse();
        assertThat(ApiTokenHasher.constantTimeEquals(null, null)).isFalse();
    }
}
