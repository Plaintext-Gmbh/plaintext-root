/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ApiTokenValidationResultTest {

    @Test
    void recordAccessors_workCorrectly() {
        Instant expires = Instant.now().plusSeconds(3600);

        IApiTokenService.ApiTokenValidationResult result =
                new IApiTokenService.ApiTokenValidationResult(
                        42L, "test-mandat", "user@test.ch", "my-token", expires
                );

        assertEquals(42L, result.userId());
        assertEquals("test-mandat", result.mandat());
        assertEquals("user@test.ch", result.email());
        assertEquals("my-token", result.tokenName());
        assertEquals(expires, result.expiresAt());
    }

    @Test
    void equalsAndHashCode_sameValues() {
        Instant expires = Instant.parse("2026-01-01T00:00:00Z");

        IApiTokenService.ApiTokenValidationResult r1 =
                new IApiTokenService.ApiTokenValidationResult(1L, "m", "e@t.ch", "t", expires);
        IApiTokenService.ApiTokenValidationResult r2 =
                new IApiTokenService.ApiTokenValidationResult(1L, "m", "e@t.ch", "t", expires);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void notEquals_differentUserId() {
        Instant expires = Instant.now();

        IApiTokenService.ApiTokenValidationResult r1 =
                new IApiTokenService.ApiTokenValidationResult(1L, "m", "e@t.ch", "t", expires);
        IApiTokenService.ApiTokenValidationResult r2 =
                new IApiTokenService.ApiTokenValidationResult(2L, "m", "e@t.ch", "t", expires);

        assertNotEquals(r1, r2);
    }

    @Test
    void toString_containsValues() {
        IApiTokenService.ApiTokenValidationResult result =
                new IApiTokenService.ApiTokenValidationResult(1L, "mandat", "email@t.ch", "token", Instant.now());

        String str = result.toString();
        assertTrue(str.contains("mandat"));
        assertTrue(str.contains("email@t.ch"));
        assertTrue(str.contains("token"));
    }

    @Test
    void nullFields_areAllowed() {
        IApiTokenService.ApiTokenValidationResult result =
                new IApiTokenService.ApiTokenValidationResult(null, null, null, null, null);

        assertNull(result.userId());
        assertNull(result.mandat());
        assertNull(result.email());
        assertNull(result.tokenName());
        assertNull(result.expiresAt());
    }
}
