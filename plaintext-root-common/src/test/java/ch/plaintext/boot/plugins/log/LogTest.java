/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogTest {

    @Test
    void maskiertNormaleAdresse() {
        assertEquals("v***@example.com", Log.mail("vorname.nachname@example.com"));
    }

    @Test
    void behaeltErstesZeichenUndVolleDomain() {
        assertEquals("m***@plaintext.ch", Log.mail("mail@plaintext.ch"));
    }

    @Test
    void einzelnesZeichenVorDemAt() {
        assertEquals("x***@example.com", Log.mail("x@example.com"));
    }

    @Test
    void nullBleibtNull() {
        assertNull(Log.mail(null));
    }

    @Test
    void leererStringBleibtLeer() {
        assertEquals("", Log.mail(""));
        assertEquals("   ", Log.mail("   "));
    }

    @Test
    void ohneAtWirdVollstaendigMaskiert() {
        assertEquals("***", Log.mail("keine-email-adresse"));
    }

    @Test
    void atAlsErstesZeichenWirdVollstaendigMaskiert() {
        // no local part to keep a character of -> full mask rather than an empty/odd result
        assertEquals("***", Log.mail("@example.com"));
    }

    @Test
    void istIdempotentGenugFuerWiederholtesLoggenDerselbenVariable() {
        String maskiert = Log.mail("vorname.nachname@example.com");
        assertEquals(maskiert, Log.mail("vorname.nachname@example.com"));
    }
}
