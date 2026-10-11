/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.apitoken.JwtTokenService.JwtValidationResult;
import ch.plaintext.McpUserRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ApiTokenValidatorServiceImpl#validateRequest(String, String)}.
 * <p>
 * In particular it secures the regression of the three audit findings:
 * <ul>
 *   <li>A real exp check (previously only {@code contains("exp")} — EVERY token with an exp claim
 *       was reported as "expired", whether it had actually expired or not).</li>
 *   <li>The revoked branch is reachable (signature valid, but not in the DB).</li>
 *   <li>The JWT signature is validated exactly ONCE (no second
 *       {@code validateToken} run).</li>
 * </ul>
 */
class ApiTokenValidatorServiceImplTest {

    private final ApiTokenService apiTokenService = mock(ApiTokenService.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final McpUserRoles mcpUserRoles = mock(McpUserRoles.class);
    private final ApiTokenValidatorServiceImpl validator =
            new ApiTokenValidatorServiceImpl(apiTokenService, jwtTokenService, mcpUserRoles, new ApiTokenScopeDeckel());

    private static final String PATH = "/api/test";

    /** The controllers run inside an MVC request; the plain cases below are reads (GET). */
    @BeforeEach
    void getAnfrage() {
        anfrage("GET", PATH);
    }

    @AfterEach
    void anfrageWeg() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void anfrage(String methode, String pfad) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(methode, pfad)));
    }

    /** Token user 7 with the given scope claim; the owner holds {@code rollen}. */
    private String gueltigesToken(String scope, String... rollen) {
        String token = tokenWithPayload("{\"userId\":7}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), scope, null);
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.of(
                new ApiTokenValidationResult(7L, "plaintext", "u@x.ch", "cli", jwt.expiresAt(), scope)));
        when(mcpUserRoles.rolesForUser(7L)).thenReturn(Set.of(rollen));
        return token;
    }

    /** Builds a JWT-shaped token (header.payload.sig) with the given payload JSON. */
    private static String tokenWithPayload(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private static ApiErrorResponse errorBody(ITokenValidationOutcome outcome) {
        assertTrue(outcome.hasError());
        return (ApiErrorResponse) outcome.getErrorResponse().getBody();
    }

    @Test
    void fehlenderTokenGibtTokenMissing() {
        ITokenValidationOutcome outcome = validator.validateRequest(null, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(401, error.status());
        assertEquals("TOKEN_MISSING", error.error());
        verify(jwtTokenService, never()).validateToken(any());
    }

    @Test
    void gueltigerTokenGibtValidationOhneError() {
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
        ApiTokenValidationResult result = new ApiTokenValidationResult(7L, "plaintext", "u@x.ch", "cli", jwt.expiresAt());
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.of(result));

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertFalse(outcome.hasError());
        assertNull(outcome.getErrorResponse());
        assertEquals(result, outcome.getValidation());
        // Dedup regression: signature validated exactly ONCE, no second full validation run.
        verify(jwtTokenService, times(1)).validateToken(anyString());
        verify(apiTokenService, never()).validateToken(anyString());
    }

    @Test
    void abgelaufenerTokenGibtTokenExpiredMitAblaufzeit() {
        Instant expiredAt = Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + expiredAt.getEpochSecond() + "}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals("TOKEN_EXPIRED", error.error());
        assertEquals(expiredAt, error.expiredAt());
        verify(apiTokenService, never()).validateVerifiedToken(anyString(), any());
    }

    @Test
    void ungueltigeSignaturMitZukunftsExpGibtTokenInvalidNichtExpired() {
        // Regression for the contains("exp") bug: exp claim present, but in the FUTURE →
        // the token has not expired, it has an invalid signature.
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void tokenOhneExpGiltAlsNichtAbgelaufen() {
        // Documented semantics: without an exp claim never "expired" → invalid path.
        String token = tokenWithPayload("{\"userId\":7}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void gueltigeSignaturOhneDbEintragGibtTokenRevoked() {
        // Revoked branch (previously dead code): signature valid, but the hash is not (any longer) in the DB.
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(401, error.status());
        assertEquals("TOKEN_REVOKED", error.error());
        assertNotNull(error.timestamp());
    }

    @Test
    void headerOhneBearerPrefixGibtTokenMissing() {
        ITokenValidationOutcome outcome = validator.validateRequest("Basic abc123", PATH);

        assertEquals("TOKEN_MISSING", errorBody(outcome).error());
        verify(jwtTokenService, never()).validateToken(any());
    }

    /**
     * Card 1484 (M1 from review 1450): a {@code ui:} token (watch link, Bieler viewer link) is a
     * browser credential. At the REST controllers that use this validator — {@code /nosec/root/upload},
     * the gear REST, {@code /api/kontakte} — it must be rejected like at {@code /mcp}.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "ui:watch-handy-link, /nosec/root/upload/services",
            "ui:Bieler-Public: Zuschauer, /api/gear/items",
            "UI:Gross geschrieben, /api/kontakte"})
    void uiTokenWirdAnDerRestApiAbgewiesen(String tokenName, String pfad) {
        String token = tokenWithPayload("{\"userId\":7}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", tokenName, Instant.now().plusSeconds(3600), "READ", null);
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.of(
                new ApiTokenValidationResult(7L, "plaintext", "u@x.ch", tokenName, jwt.expiresAt(), "READ")));

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, pfad);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(401, error.status());
        assertNull(outcome.getValidation(), "ein ui:-Token darf keinen Mandanten/User an den Controller liefern");
    }

    /** A token whose name merely contains "ui:" further on is a normal API token. */
    @Test
    void normalerTokenMitUiImNamenBleibtGueltig() {
        String token = tokenWithPayload("{\"userId\":7}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "upload-cli (kein ui:)", Instant.now().plusSeconds(3600), null, null);
        ApiTokenValidationResult result = new ApiTokenValidationResult(7L, "plaintext", "u@x.ch", "upload-cli (kein ui:)", jwt.expiresAt());
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.of(result));

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, "/nosec/root/upload/services");

        assertFalse(outcome.hasError());
        assertEquals(result, outcome.getValidation());
    }

    /**
     * Card 1485 (M1 from review 1450): a READ token must not write. Writing ways of the REST
     * controllers (gear packing lists, {@code /nosec/root/upload}) are POST/PUT/DELETE; they need
     * WRITE, like the write tools at {@code /mcp} — even if the owner is an admin.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "POST, /nosec/root/upload",
            "POST, /api/gear/packing-lists",
            "PUT, /api/gear/packing-lists/1",
            "DELETE, /api/gear/packing-lists/1/items/2",
            "PATCH, /api/gear/packing-lists/1"})
    void readTokenDarfNichtSchreiben(String methode, String pfad) {
        anfrage(methode, pfad);
        String token = gueltigesToken("READ", "ADMIN");

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, pfad);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(403, error.status());
        assertEquals("INSUFFICIENT_SCOPE", error.error());
        assertNull(outcome.getValidation());
    }

    /** A token without scope claim counts as READ (as at /mcp without legacy switch). */
    @Test
    void tokenOhneScopeDarfNichtSchreiben() {
        anfrage("POST", "/nosec/root/upload");
        String token = gueltigesToken(null, "ADMIN");

        assertEquals(403, errorBody(validator.validateRequest("Bearer " + token, "/nosec/root/upload")).status());
    }

    /** Scope cap (cards 1363/1365): a WRITE claim of a plain USER is worth READ. */
    @Test
    void writeClaimEinesUsersWirdGedeckelt() {
        anfrage("POST", "/api/gear/packing-lists");
        String token = gueltigesToken("WRITE", "USER");

        assertEquals("INSUFFICIENT_SCOPE", errorBody(validator.validateRequest("Bearer " + token, "/api/gear/packing-lists")).error());
    }

    /** Without a bound request the method is unknown: fail closed, treat it as a write. */
    @Test
    void ohneAnfrageGiltSchreiben() {
        RequestContextHolder.resetRequestAttributes();
        String token = gueltigesToken("READ", "ADMIN");

        assertEquals(403, errorBody(validator.validateRequest("Bearer " + token, PATH)).status());
    }

    /** Counter-check: the legitimate ways keep working. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "POST, /nosec/root/upload, WRITE, ADMIN",
            "DELETE, /api/gear/packing-lists/1, ADMIN, ROOT",
            "POST, /api/gear/packing-lists, EINTRAGEN, ADMIN",
            "GET, /api/gear/items, READ, USER",
            "HEAD, /api/kontakte, READ, USER",
            "GET, /nosec/root/upload/services, , USER"})
    void legitimerWegGehtWeiter(String methode, String pfad, String scope, String rolle) {
        anfrage(methode, pfad);
        String token = gueltigesToken(scope, rolle);

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, pfad);

        assertFalse(outcome.hasError());
        assertEquals(7L, outcome.getValidation().userId());
    }

    /** If the app restricts tokens to some roles (lese-rollen), others get 403 even for reads — like /mcp. */
    @Test
    void ohneLeseRolleKeinZugriff() {
        ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
        deckel.setLeseRollen(java.util.List.of("API"));
        ApiTokenValidatorServiceImpl streng = new ApiTokenValidatorServiceImpl(apiTokenService, jwtTokenService, mcpUserRoles, deckel);
        String token = gueltigesToken("READ", "USER");

        assertEquals(403, errorBody(streng.validateRequest("Bearer " + token, PATH)).status());
    }
}
