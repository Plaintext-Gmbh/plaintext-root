/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 664: The checker that makes {@code revoke_api_token} effective in JWT mode as well.
 *
 * <p>The most important check is not "a revoked token is rejected" but the opposite direction:
 * <b>an unknown jti must be let through.</b> Whether the JWT-only tokens
 * (time-tracking clock, Juriwagen, {@code minten}) keep working depends on it — exactly the ones
 * that a switch to {@code validation=DATABASE} would lock out.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class ApiTokenJtiRevocationCheckerTest {

    /** Counts the lookups, so that the cache is not merely claimed but proven. */
    private static final class ZaehlenderLookup implements ApiTokenRevocationLookup {
        private final AtomicInteger aufrufe = new AtomicInteger();
        private final String widerrufenerJti;
        private final RuntimeException fehler;

        ZaehlenderLookup(String widerrufenerJti, RuntimeException fehler) {
            this.widerrufenerJti = widerrufenerJti;
            this.fehler = fehler;
        }

        @Override
        public boolean isJtiRevoked(String jti) {
            aufrufe.incrementAndGet();
            if (fehler != null) {
                throw fehler;
            }
            return jti.equals(widerrufenerJti);
        }

        @Override
        public Optional<TokenZustand> findForValidation(String tokenHash) {
            throw new UnsupportedOperationException("im Test nicht benutzt");
        }

        @Override
        public void markUsed(long id) {
            throw new UnsupportedOperationException("im Test nicht benutzt");
        }
    }

    @Test
    void widerrufenerJtiWirdAbgewiesen() {
        var lookup = new ZaehlenderLookup("jti-weg", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertTrue(checker.isRevoked("jti-weg"), "ein invalidiertes Token muss als widerrufen gelten");
    }

    @Test
    void unbekannterJtiLaeuftDurch() {
        // THIS is the difference from validation=DATABASE: no row means "not revoked",
        // not "unknown, therefore block". Otherwise the time-tracking clock stops working.
        var lookup = new ZaehlenderLookup("jti-weg", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked("jti-einer-uhr-ohne-db-zeile"));
    }

    @Test
    void nullUndLeerFragenGarNichtErstNach() {
        var lookup = new ZaehlenderLookup("jti-weg", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked(null));
        assertFalse(checker.isRevoked("  "));
        assertEquals(0, lookup.aufrufe.get(), "ohne jti darf es keinen Datenbankzugriff geben");
    }

    @Test
    void datenbankfehlerLaesstDurchStattAlleAuszusperren() {
        // Fail-open on purpose: on a DB outage, fail-closed would be a total outage of all
        // MCP accesses — the wrong trade-off for a gap with no known case of abuse.
        var lookup = new ZaehlenderLookup(null, new IllegalStateException("DB weg"));
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked("irgendein-jti"));
    }

    @Test
    void fehlerWirdNichtAlsNichtWiderrufenGecacht() {
        // Otherwise a single DB outage would unlock a revoked token for a whole minute —
        // the glitch would have an after-effect that nobody expects.
        var lookup = new ZaehlenderLookup("jti-weg", new IllegalStateException("DB kurz weg"));
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked("jti-weg"));
        assertFalse(checker.isRevoked("jti-weg"));
        assertEquals(2, lookup.aufrufe.get(), "nach einem Fehler muss erneut nachgesehen werden");
    }

    @Test
    void zweiteFrageZumSelbenJtiKommtAusDemCache() {
        var lookup = new ZaehlenderLookup("jti-weg", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked("jti-ok"));
        assertFalse(checker.isRevoked("jti-ok"));
        assertFalse(checker.isRevoked("jti-ok"));
        assertEquals(1, lookup.aufrufe.get(), "eine MCP-Sitzung darf nicht je Request nachschlagen");
    }

    @Test
    void widerrufeneTokensWerdenNichtGecachtUndBleibenAbgewiesen() {
        var lookup = new ZaehlenderLookup("jti-weg", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertTrue(checker.isRevoked("jti-weg"));
        assertTrue(checker.isRevoked("jti-weg"));
        assertEquals(2, lookup.aufrufe.get(), "positive Treffer werden bewusst nicht zwischengespeichert");
    }

    @Test
    void einWiderrufWirktTrotzVorherigemNegativCacheNachAblaufDerTtl() throws Exception {
        // The cache may delay a revocation, but must not prevent it. Measured without an
        // artificial clock: the entry is pre-seeded with an expired TTL.
        var lookup = new ZaehlenderLookup("jti-dreht", null);
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        var feld = ApiTokenJtiRevocationChecker.class.getDeclaredField("nichtWiderrufenBis");
        feld.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (java.util.Map<String, Long>) feld.get(checker);
        cache.put("jti-dreht", System.currentTimeMillis() - 1);

        assertTrue(checker.isRevoked("jti-dreht"), "abgelaufener Cache-Eintrag muss neu geprueft werden");
    }
}
