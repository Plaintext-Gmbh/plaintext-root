/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.JwtTokenService.JwtValidationResult;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ApiTokenService#validateToken(String)} — the path that runs from servlet
 * filters / MCP contexts. Secures the validation behaviour (valid / revoked / deleted / invalidated)
 * and, as a regression, that the method is <b>not</b> {@code @Transactional} (otherwise it leaks
 * Hikari connections out of the filter/reactive context until the pool is empty).
 */
import org.mockito.ArgumentCaptor;

class ApiTokenServiceTest {

    private final ApiTokenRepository repo = mock(ApiTokenRepository.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final ApiTokenRevocationLookup lookup = mock(ApiTokenRevocationLookup.class);
    private final ApiTokenService service = new ApiTokenService(repo, jwt, lookup);

    private static final String TOKEN = "eyJ.header.sig";

    private JwtValidationResult jwtOk() {
        return new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
    }

    /**
     * The state that the revocation lookup returns (card 659) — since the switch to JDBC this one
     * path no longer goes through the JPA repository.
     */
    private ApiTokenRevocationLookup.TokenZustand storedToken() {
        return new ApiTokenRevocationLookup.TokenZustand(42L, false, false, "u@x.ch");
    }

    @Test
    void gueltigerTokenGibtResultUndAktualisiertLastUsed() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(lookup.findForValidation(anyString())).thenReturn(Optional.of(storedToken()));

        Optional<ApiTokenValidationResult> res = service.validateToken(TOKEN);

        assertTrue(res.isPresent());
        assertEquals(7L, res.get().userId());
        assertEquals("plaintext", res.get().mandat());
        assertEquals("u@x.ch", res.get().email());
        verify(lookup).markUsed(42L);               // last-used updated on a best-effort basis
        verify(repo, never()).save(any());          // and that WITHOUT JPA in the filter path
    }

    @Test
    void nullTokenGibtEmptyOhneDbZugriff() {
        assertTrue(service.validateToken(null).isEmpty());
        assertTrue(service.validateToken("").isEmpty());
        verify(jwt, never()).validateToken(any());
        verify(lookup, never()).findForValidation(any());
    }

    @Test
    void ungueltigeJwtSignaturGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.empty());
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(lookup, never()).findForValidation(any());
    }

    @Test
    void hashNichtInDbRevoktGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(lookup.findForValidation(anyString())).thenReturn(Optional.empty());
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(lookup, never()).markUsed(anyLong());
    }

    @Test
    void geloeschterTokenGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(lookup.findForValidation(anyString()))
                .thenReturn(Optional.of(new ApiTokenRevocationLookup.TokenZustand(42L, true, false, "u@x.ch")));
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(lookup, never()).markUsed(anyLong());
    }

    @Test
    void invalidierterTokenGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(lookup.findForValidation(anyString()))
                .thenReturn(Optional.of(new ApiTokenRevocationLookup.TokenZustand(42L, false, true, "u@x.ch")));
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(lookup, never()).markUsed(anyLong());
    }

    /**
     * The usage statistic is explicitly <b>best effort</b> (card 659): if the counter update fails,
     * the access stays valid. Otherwise a blocked write access to
     * {@code api_token} would reject every bearer call — an outage caused by a statistics row.
     */
    @Test
    void fehlerBeimNutzungszaehlerKipptDieEntscheidungNicht() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(lookup.findForValidation(anyString())).thenReturn(Optional.of(storedToken()));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB weg"))
                .when(lookup).markUsed(42L);

        Optional<ApiTokenValidationResult> res = service.validateToken(TOKEN);

        assertTrue(res.isPresent(), "ein fehlgeschlagener Statistik-Update darf den Token nicht entwerten");
        assertEquals(7L, res.get().userId());
    }

    /**
     * Regression against the Hikari connection leak: {@code validateToken} is called from servlet
     * filters / reactive MCP contexts; a {@code @Transactional} bound there never returns its
     * connection to the pool. The method must therefore stay free of transactions.
     */
    @Test
    void validateTokenIstNichtTransactional() throws NoSuchMethodException {
        Method m = ApiTokenService.class.getMethod("validateToken", String.class);
        assertFalse(m.isAnnotationPresent(Transactional.class),
                "validateToken darf NICHT @Transactional sein — leakt sonst Hikari-Connections aus Filter/Reactive-Kontext");
    }

    // ── Service tokens for machine issuers (card 349) ──────────────────────────────────

    /**
     * The Juriwagen mints a token EVERY time the SPA is opened. The duplicate-name check of
     * createToken() would throw on the second open — that must not happen for a machine-driven
     * flow, otherwise operations come to a standstill on tournament day.
     */
    @Test
    void serviceToken_darfMehrfachMitGleichemNamenAusgestelltWerden() {
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(repo.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(7L, "plaintext", false))
                .thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.createServiceToken(7L, "plaintext", "Turnier-UI", "u@x.ch", 90, "EINTRAGEN"));
        assertDoesNotThrow(() -> service.createServiceToken(7L, "plaintext", "Turnier-UI", "u@x.ch", 90, "EINTRAGEN"));

        verify(repo, times(2)).save(any(ApiToken.class));
    }

    /** Every service token gets a DB row with a hash — exactly what validation=DATABASE needs. */
    @Test
    void serviceToken_persistiertHashUndScope() {
        when(jwt.generateToken(eq(7L), eq("plaintext"), eq("u@x.ch"), eq("Zeiterfassung-Uhr"), eq(90), eq("READ")))
                .thenReturn(TOKEN);
        when(repo.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(7L, "plaintext", false))
                .thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String ausgegeben = service.createServiceToken(7L, "plaintext", "Zeiterfassung-Uhr", "u@x.ch", 90, "READ");

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(repo).save(captor.capture());
        ApiToken gespeichert = captor.getValue();
        assertEquals(TOKEN, ausgegeben);
        assertNotNull(gespeichert.getTokenHash());
        assertFalse(gespeichert.getTokenHash().isBlank());
        assertNotEquals(TOKEN, gespeichert.getTokenHash(), "es darf nur der Hash gespeichert werden");
        assertEquals("Zeiterfassung-Uhr", gespeichert.getTokenName());
        assertNotNull(gespeichert.getExpiresAt());
    }

    /**
     * Expired tokens of the same flow are cleaned up, a still VALID one is kept —
     * otherwise a second device of the same user would be locked out mid-operation.
     */
    @Test
    void serviceToken_raeumtNurAbgelaufeneAufUndLaesstAktiveBestehen() {
        ApiToken abgelaufen = new ApiToken();
        abgelaufen.setTokenName("Turnier-UI");
        abgelaufen.setDeleted(false);
        abgelaufen.setExpiresAt(LocalDateTime.now().minusDays(1));
        ApiToken aktiv = new ApiToken();
        aktiv.setTokenName("Turnier-UI");
        aktiv.setDeleted(false);
        aktiv.setExpiresAt(LocalDateTime.now().plusDays(30));
        ApiToken andererFlow = new ApiToken();
        andererFlow.setTokenName("cli");
        andererFlow.setDeleted(false);
        andererFlow.setExpiresAt(LocalDateTime.now().minusDays(5));

        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(repo.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(7L, "plaintext", false))
                .thenReturn(List.of(abgelaufen, aktiv, andererFlow));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createServiceToken(7L, "plaintext", "Turnier-UI", "u@x.ch", 90, "EINTRAGEN");

        assertTrue(abgelaufen.getDeleted(), "abgelaufenes Token desselben Flows muss weg");
        assertFalse(aktiv.getDeleted(), "noch gueltiges Token darf NICHT entfernt werden");
        assertFalse(andererFlow.getDeleted(), "anderer Flow darf nicht angetastet werden");
    }

    // -------------------------------------------------- Scope pass-through (card 504)

    /**
     * Card 504 demands the counter-check in <b>both</b> directions: a selected scope must
     * arrive — the wide one just as much as the narrow one. A test that only checks ADMIN would be
     * green even if the service stubbornly set ADMIN.
     *
     * <p>What is checked is the path the form takes: {@code createToken(..., scope)}.</p>
     */
    @Test
    void gewaehlterScopeKommtBeiDerJwtErzeugungAn() {
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);

        service.createToken(7L, "plaintext", "adminToken", "u@x.ch", 365, "ADMIN");
        service.createToken(7L, "plaintext", "leseToken", "u@x.ch", 90, "READ");
        service.createToken(7L, "plaintext", "erfassToken", "u@x.ch", 90, "EINTRAGEN");

        verify(jwt, times(3)).generateToken(anyLong(), anyString(), anyString(), anyString(),
                anyInt(), scope.capture());
        assertEquals(List.of("ADMIN", "READ", "EINTRAGEN"), scope.getAllValues(),
                "Der gewaehlte Scope muss unveraendert im JWT landen — in beide Richtungen. "
                        + "Am 03.08.2026 kam er gar nicht erst in der Bean an (Karte 504); die Ursache "
                        + "lag in der Maske, dieser Test sichert die Java-Seite dahinter.");
    }

    /**
     * <b>Known shortcoming, recorded here instead of being overlooked (card 504, side finding):</b>
     * "Token neu erzeugen" (regenerate token) passes <b>no</b> scope through —
     * {@code regenerateToken} calls the overload without a scope, and {@link ApiToken} has no scope
     * field from which the old value could be restored. The new token therefore carries no
     * {@code scope} claim at all.
     *
     * <p>Today that is <b>not</b> dangerous: since card 312 a missing claim counts
     * fail-closed as {@code READ}, and the fallback switch
     * {@code plaintext.mcp.bearer-filter.legacy-scope-admin} is not set in any repo (checked
     * in root, app, guild, schuetu, iot, fwtool). Whoever renews their ADMIN token therefore gets
     * one that is too weak, not one that is too strong.
     *
     * <p><b>This test pins down the actual state, not the target state.</b> Fixing it
     * needs a {@code scope} column in {@code api_token} — and that table is touched by card 349
     * ({@code validation: DATABASE}) anyway. Once it is dealt with there, this
     * expectation should be inverted.
     */
    @Test
    void regenerateTokenVerliertDenScope_bekannterMangel() {
        ApiToken alt = new ApiToken();
        alt.setId(42L);
        alt.setUserId(7L);
        alt.setMandat("plaintext");
        alt.setTokenName("adminToken");
        alt.setDeleted(false);
        when(repo.findById(42L)).thenReturn(Optional.of(alt));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);

        service.regenerateToken(42L, 7L, "plaintext", "u@x.ch", 365);

        verify(jwt).generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(),
                scope.capture());
        assertEquals(null, scope.getValue(),
                "Schlaegt dieser Test fehl, wurde der Scope-Erhalt beim Regenerieren nachgeruestet — "
                        + "dann diese Erwartung auf den erhaltenen Scope umstellen (Karte 504/349).");
    }

    /**
     * Card 664: Without the {@code jti} in the row, the filter cannot match an incoming token to
     * its row — {@code revoke_api_token} then reports success while the token
     * keeps working. This test pins down that link.
     */
    @Test
    void createTokenSchreibtDenJtiInDieZeile() {
        when(repo.countByUserIdAndMandatAndDeleted(anyLong(), anyString(), any())).thenReturn(0L);
        when(repo.findByUserIdAndMandatAndTokenNameAndDeleted(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(jwt.extractJti(TOKEN)).thenReturn(Optional.of("jti-4711"));
        ArgumentCaptor<ApiToken> gespeichert = ArgumentCaptor.forClass(ApiToken.class);

        service.createToken(7L, "plaintext", "cli", "u@x.ch", 90, "READ");

        verify(repo).save(gespeichert.capture());
        assertEquals("jti-4711", gespeichert.getValue().getJti());
    }

    /**
     * Card 664: Service tokens (clock, Juriwagen) must be revocable as well — they run
     * through their own issuing path, which is otherwise easily forgotten.
     */
    @Test
    void createServiceTokenSchreibtDenJtiEbenfalls() {
        when(repo.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(anyLong(), anyString(), any()))
                .thenReturn(List.of());
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(jwt.extractJti(TOKEN)).thenReturn(Optional.of("jti-uhr"));
        ArgumentCaptor<ApiToken> gespeichert = ArgumentCaptor.forClass(ApiToken.class);

        service.createServiceToken(7L, "plaintext", "uhr", "u@x.ch", 90, "EINTRAGEN");

        verify(repo).save(gespeichert.capture());
        assertEquals("jti-uhr", gespeichert.getValue().getJti());
    }

    /**
     * Card 664: An unreadable jti must not make the issuing fail. The row then
     * gets {@code null} — which means "unknown", not "revoked", and the token
     * behaves like one from before this card.
     */
    @Test
    void createTokenBleibtErfolgreichWennDerJtiNichtLesbarIst() {
        when(repo.countByUserIdAndMandatAndDeleted(anyLong(), anyString(), any())).thenReturn(0L);
        when(repo.findByUserIdAndMandatAndTokenNameAndDeleted(anyLong(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(TOKEN);
        when(jwt.extractJti(TOKEN)).thenReturn(Optional.empty());
        ArgumentCaptor<ApiToken> gespeichert = ArgumentCaptor.forClass(ApiToken.class);

        String ausgestellt = service.createToken(7L, "plaintext", "cli", "u@x.ch", 90, "READ");

        assertEquals(TOKEN, ausgestellt);
        verify(repo).save(gespeichert.capture());
        assertEquals(null, gespeichert.getValue().getJti());
    }
}
