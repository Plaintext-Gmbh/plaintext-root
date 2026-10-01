/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1355: das Skript des Mobil-Frameworks bleibt klein und CSP-tauglich.
 *
 * <p>Der Zielwert der Karte ist „eine kleine eigene JS-Datei, unter 5 KB". Eine Grenze, die
 * kein Test haelt, ist nach drei Erweiterungen weg — und genau das Wachsen ist, was die
 * PrimeFaces-Seiten am Telefon so langsam gemacht hat (1.9 MB, gemessen 01.10.2026).</p>
 */
class MobilJsVertragTest {

    private static String js() throws IOException {
        Path p = Path.of("src/main/resources/META-INF/resources/watch/mobil.js");
        if (!Files.exists(p)) {
            p = Path.of("plaintext-root-watch").resolve(p);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("mobil.js ist unter 5 KB, komprimiert unter 2.5 KB")
    void klein() throws IOException {
        byte[] roh = js().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(roh);
        }
        assertThat(roh.length).as("Bytes roh").isPositive().isLessThan(5 * 1024);
        assertThat(gz.size()).as("Bytes gzip").isLessThan(2560);
    }

    @Test
    @DisplayName("Kein eval, kein new Function, kein document.write — die CSP bleibt ohne unsafe-eval")
    void keinEval() throws IOException {
        String js = js();
        assertThat(js).doesNotContainPattern("\\beval\\s*\\(")
                .doesNotContainPattern("new\\s+Function\\s*\\(")
                .doesNotContain("document.write")
                .doesNotContainPattern("setTimeout\\s*\\(\\s*['\"]");
        // Positivkontrolle: es ist wirklich das Framework-Skript.
        assertThat(js).contains("data-mobil").contains("fetch(");
    }

    @Test
    @DisplayName("Die Ids, die das Skript sucht, sind die, die MobilHtml schreibt")
    void idsStimmen() throws IOException {
        String js = js();
        assertThat(js).contains("'" + MobilHtml.INHALT_ID + "'").contains("'" + MobilHtml.MELDUNG_ID + "'");
    }
}
