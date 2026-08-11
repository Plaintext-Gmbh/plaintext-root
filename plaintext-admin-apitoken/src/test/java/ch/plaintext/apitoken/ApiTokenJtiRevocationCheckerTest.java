/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 664: Der Checker, der {@code revoke_api_token} auch im JWT-Modus wirksam macht.
 *
 * <p>Die wichtigste Prüfung ist nicht „widerrufen wird abgewiesen", sondern die Gegenrichtung:
 * <b>ein unbekannter jti muss durchgelassen werden.</b> Daran hängt, ob die JWT-only-Tokens
 * (Zeiterfassungs-Uhr, Juriwagen, {@code minten}) weiter funktionieren — genau die, die ein
 * Umstellen auf {@code validation=DATABASE} aussperren würde.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class ApiTokenJtiRevocationCheckerTest {

    /** Zählt die Lookups mit, damit der Cache nicht nur behauptet, sondern belegt wird. */
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
        // DAS ist der Unterschied zu validation=DATABASE: kein Eintrag heisst "nicht widerrufen",
        // nicht "unbekannt, also sperren". Sonst faellt die Zeiterfassungs-Uhr aus.
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
        // Fail-open mit Absicht: Bei einem DB-Aussetzer waere fail-closed ein Totalausfall aller
        // MCP-Zugaenge — fuer eine Luecke ohne bekannten Missbrauchsfall der falsche Tausch.
        var lookup = new ZaehlenderLookup(null, new IllegalStateException("DB weg"));
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertFalse(checker.isRevoked("irgendein-jti"));
    }

    @Test
    void fehlerWirdNichtAlsNichtWiderrufenGecacht() {
        // Sonst wuerde ein einzelner DB-Aussetzer ein widerrufenes Token eine Minute lang
        // freischalten — die Panne haette eine Nachwirkung, die niemand erwartet.
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
        // Der Cache darf einen Widerruf verzoegern, aber nicht verhindern. Ohne kuenstliche Uhr
        // gemessen: der Eintrag wird mit abgelaufener TTL vorbelegt.
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
