/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1256: Jedes Formular einer Watch-Seite muss die CSP-Nonce mitschicken.
 *
 * <h2>Der Fehler, den dieser Test festhaelt</h2>
 *
 * <p>Mit {@code joinfaces.primefaces.csp=true} vergleicht PrimeFaces bei <b>jedem</b> Postback
 * die Nonce aus der View-Map gegen den gleichnamigen Request-Parameter und wirft sonst
 * {@code CSP nonce mismatch} — eine Whitelabel-Fehlerseite ohne verwertbare Meldung. Auf einer
 * normalen Seite haengt PrimeFaces' eigenes JavaScript den Parameter an. Die Watch-Seiten
 * tragen bewusst kein PrimeFaces (Karte 1247: ein einziges {@code p:}-Tag kostete an derselben
 * Stelle gemessen 1 055 KB statt 56 KB), also haengt ihn niemand an.</p>
 *
 * <p>Ergebnis: <b>jeder</b> Knopfdruck auf einer Watch-Seite endete in einer Fehlerseite. Daniel
 * hat es am 19.09.2026 vom Telefon aus gemeldet; im Log stand es zweimal, um 08:26 und 08:35.
 * Aufgefallen ist es niemandem vorher, weil die Seiten fehlerfrei <i>rendern</i> — nur das
 * Abschicken bricht.</p>
 *
 * <p>Der Test prueft beide Pflichtfelder zusammen, weil sie dieselbe Ursache haben: ein
 * Formular, das ohne die Rahmenarbeit eines Komponenten-Frameworks abgeschickt wird, muss alles
 * selbst mitbringen. {@code _csrf} war von Anfang an dabei, die Nonce wurde vergessen.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchNonceFeldVertragTest {

    private static final Pattern FORM = Pattern.compile(
            "<h:form\\b(.*?)</h:form>", Pattern.DOTALL);

    private static final Pattern CSRF = Pattern.compile(
            "name=\"_csrf\"");

    private static final Pattern NONCE = Pattern.compile(
            "name=\"primefaces\\.nonce\"");

    private static Path wurzel() {
        Path p = Path.of("src/main/resources/META-INF/resources/watch");
        return Files.isDirectory(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    @Test
    @DisplayName("Jedes h:form einer Watch-Seite traegt _csrf UND die CSP-Nonce")
    void jedesFormularTraegtBeides() throws IOException {
        Path wurzel = wurzel();
        assertTrue(Files.isDirectory(wurzel), "Watch-Seiten nicht gefunden: " + wurzel.toAbsolutePath());

        List<String> maengel = new ArrayList<>();
        int formulare = 0;

        List<Path> dateien;
        try (Stream<Path> s = Files.list(wurzel)) {
            dateien = s.filter(p -> p.toString().endsWith(".xhtml")).sorted().toList();
        }

        for (Path datei : dateien) {
            String inhalt = Files.readString(datei);
            Matcher m = FORM.matcher(inhalt);
            while (m.find()) {
                formulare++;
                String form = m.group(1);
                if (!CSRF.matcher(form).find()) {
                    maengel.add(datei.getFileName() + ": ein h:form ohne _csrf");
                }
                if (!NONCE.matcher(form).find()) {
                    maengel.add(datei.getFileName() + ": ein h:form ohne primefaces.nonce — "
                            + "jeder Knopfdruck darin gibt eine Whitelabel-Seite");
                }
            }
        }

        assertTrue(formulare >= 2,
                "Nur " + formulare + " Formulare gefunden — dann sucht der Test falsch und "
                        + "belegt nichts. Erwartet mindestens das Navigationsformular und eines auf "
                        + "der Elemente-Seite.");
        assertTrue(maengel.isEmpty(), "Watch-Formulare ohne Pflichtfeld:\n  "
                + String.join("\n  ", maengel));
    }

    @Test
    @DisplayName("Gegenprobe: ein Formular ohne die Nonce faellt auf")
    void gegenprobe() {
        String ohne = """
                <h:form id="x">
                    <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                    <h:commandButton value="Los" action="#{b.los}"/>
                </h:form>
                """;
        Matcher m = FORM.matcher(ohne);
        assertTrue(m.find(), "Das Formular selbst wird erkannt …");
        assertTrue(CSRF.matcher(m.group(1)).find(), "… _csrf ist da …");
        assertFalse(NONCE.matcher(m.group(1)).find(),
                "… und die fehlende Nonce muss auffallen. Sonst waere der Test gruen, waehrend "
                        + "jeder Knopfdruck in PROD eine Fehlerseite gibt.");
    }

    @Test
    @DisplayName("Der Schluessel ist derselbe, den PrimeFaces zurueckliest")
    void schluesselStimmtMitDerBeanUeberein() throws IOException {
        // Wenn jemand den Namen an einer der beiden Stellen aendert, bricht die Kette lautlos:
        // das Feld hiesse anders als der Parameter, den PrimeFaces sucht.
        assertEquals("primefaces.nonce", WatchFrameBean.NONCE_SCHLUESSEL);

        String frame = Files.readString(wurzel().resolve("frame.xhtml"));
        assertTrue(frame.contains("name=\"primefaces.nonce\""),
                "Das Feld im Rahmen muss genau so heissen wie die Konstante");
        assertTrue(frame.contains("#{watchFrameBean.cspNonce}"),
                "und seinen Wert aus der Bean holen");
    }
}
