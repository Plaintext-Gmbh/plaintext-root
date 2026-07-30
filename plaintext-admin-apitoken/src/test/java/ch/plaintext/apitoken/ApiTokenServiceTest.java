/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
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
 * Tests für {@link ApiTokenService#validateToken(String)} — den Pfad, der aus Servlet-Filtern /
 * MCP-Kontexten läuft. Sichert das Validierungsverhalten (gültig / revoked / deleted / invalidated)
 * und als Regression, dass die Methode <b>nicht</b> {@code @Transactional} ist (sonst leakt sie
 * aus dem Filter-/Reactive-Kontext Hikari-Connections, bis der Pool leer ist).
 */
import org.mockito.ArgumentCaptor;

class ApiTokenServiceTest {

    private final ApiTokenRepository repo = mock(ApiTokenRepository.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final ApiTokenService service = new ApiTokenService(repo, jwt);

    private static final String TOKEN = "eyJ.header.sig";

    private JwtValidationResult jwtOk() {
        return new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
    }

    private ApiToken storedToken() {
        ApiToken t = new ApiToken();
        t.setTokenHash("hash");
        t.setUserId(7L);
        t.setUserEmail("u@x.ch");
        t.setTokenName("cli");
        t.setDeleted(false);
        t.setInvalidated(false);
        t.setUseCount(3L);
        return t;
    }

    @Test
    void gueltigerTokenGibtResultUndAktualisiertLastUsed() {
        ApiToken t = storedToken();
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(t));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiTokenValidationResult> res = service.validateToken(TOKEN);

        assertTrue(res.isPresent());
        assertEquals(7L, res.get().userId());
        assertEquals("plaintext", res.get().mandat());
        assertEquals(4L, t.getUseCount());          // use-count hochgezählt
        verify(repo).save(t);                       // last-used best effort persistiert
    }

    @Test
    void nullTokenGibtEmptyOhneDbZugriff() {
        assertTrue(service.validateToken(null).isEmpty());
        assertTrue(service.validateToken("").isEmpty());
        verify(jwt, never()).validateToken(any());
        verify(repo, never()).findByTokenHash(any());
    }

    @Test
    void ungueltigeJwtSignaturGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.empty());
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(repo, never()).findByTokenHash(any());
    }

    @Test
    void hashNichtInDbRevoktGibtEmpty() {
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void geloeschterTokenGibtEmpty() {
        ApiToken t = storedToken();
        t.setDeleted(true);
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(t));
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void invalidierterTokenGibtEmpty() {
        ApiToken t = storedToken();
        t.setInvalidated(true);
        when(jwt.validateToken(TOKEN)).thenReturn(Optional.of(jwtOk()));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(t));
        assertTrue(service.validateToken(TOKEN).isEmpty());
        verify(repo, never()).save(any());
    }

    /**
     * Regression gegen den Hikari-Connection-Leak: {@code validateToken} wird aus Servlet-Filtern /
     * reaktiven MCP-Kontexten aufgerufen; eine dort gebundene {@code @Transactional} gibt ihre
     * Connection nie an den Pool zurück. Die Methode muss daher transaktionsfrei bleiben.
     */
    @Test
    void validateTokenIstNichtTransactional() throws NoSuchMethodException {
        Method m = ApiTokenService.class.getMethod("validateToken", String.class);
        assertFalse(m.isAnnotationPresent(Transactional.class),
                "validateToken darf NICHT @Transactional sein — leakt sonst Hikari-Connections aus Filter/Reactive-Kontext");
    }

    // ── Service-Tokens fuer maschinelle Aussteller (Karte 349) ──────────────────────────────────

    /**
     * Der Juriwagen mintet bei JEDEM Oeffnen der SPA ein Token. Die Duplikat-Namen-Pruefung von
     * createToken() wuerde beim zweiten Oeffnen werfen — fuer einen maschinellen Flow darf das nicht
     * passieren, sonst steht der Betrieb am Turniertag.
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

    /** Jedes Service-Token bekommt eine DB-Zeile mit Hash — genau das braucht validation=DATABASE. */
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
     * Abgelaufene Tokens desselben Flows werden aufgeraeumt, ein noch GUELTIGES bleibt bestehen —
     * sonst wuerde ein zweites Geraet desselben Users mitten im Betrieb ausgesperrt.
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
}
