/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1258: Kein Element darf breiter werden als sein Rahmen.
 *
 * <h2>Der Fehler, den dieser Test festhaelt</h2>
 *
 * <p>{@code .w-btn} setzt {@code width: 100%} — richtig fuer einen Knopf, der allein in einer
 * Karte steht. In einer {@code .w-row} rechnet der Browser diese 100 % aber auf den ganzen
 * Zeilenkasten: der Knopf wird so breit wie die Zeile und schiebt sich ueber den rechten
 * Bildschirmrand. Auf Daniels Screenshot vom 19.09.2026 ist der Loeschknopf der Zeit-Seite
 * genau so halb abgeschnitten.</p>
 *
 * <p>Sichtbar wird das nur <b>neben</b> anderen Elementen. Wer eine Seite mit einem einzelnen
 * Knopf baut, sieht nie etwas — deshalb steht die Regel hier und nicht im Kopf des naechsten
 * Bearbeiters.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchBreitenVertragTest {

    private static Path css() {
        Path p = Path.of("src/main/resources/META-INF/resources/watch/watch.css");
        return Files.exists(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    /** Der Rumpf einer CSS-Regel, ueber Zeilen hinweg. */
    private static String regel(String css, String selektor) {
        Matcher m = Pattern.compile(Pattern.quote(selektor) + "\\s*\\{([^}]*)\\}").matcher(css);
        return m.find() ? m.group(1) : "";
    }

    @Test
    @DisplayName(".w-btn-small setzt eine eigene Breite, statt die 100% von .w-btn zu erben")
    void kleinerKnopfHatEigeneBreite() throws IOException {
        String css = Files.readString(css());

        String klein = regel(css, ".w-btn-small");
        assertFalse(klein.isBlank(), ".w-btn-small nicht gefunden");
        assertTrue(klein.contains("width: auto"),
                ".w-btn-small muss width:auto setzen — sonst erbt es das width:100% von .w-btn "
                        + "und laeuft in einer .w-row aus dem Bild");

        // Positivkontrolle: dass .w-btn die 100% ueberhaupt setzt, ist die Voraussetzung des Befunds.
        assertTrue(regel(css, ".w-btn").contains("width: 100%"),
                "Wenn .w-btn keine 100% mehr setzt, ist diese Regel gegenstandslos — dann gehoert "
                        + "der Test angepasst statt stillschweigend gruen zu bleiben");
    }

    @Test
    @DisplayName("Kein Kind einer Zeile darf breiter sein als die Zeile")
    void zeileHatFangnetz() throws IOException {
        String css = Files.readString(css());
        assertTrue(css.contains(".w-row > * { max-width: 100%; }")
                        || regel(css, ".w-row > *").contains("max-width: 100%"),
                "Der Zeile fehlt das Fangnetz max-width:100% fuer ihre Kinder");
    }

    @Test
    @DisplayName("Keine Schrift unter 14px — auf der Uhr ist alles darunter nicht lesbar")
    void schriftNichtZuKlein() throws IOException {
        String css = Files.readString(css());

        Matcher px = Pattern.compile("font-size:\\s*(\\d+)px").matcher(css);
        while (px.find()) {
            int wert = Integer.parseInt(px.group(1));
            assertTrue(wert >= 14, "font-size " + wert + "px ist zu klein (mindestens 14px)");
        }

        Matcher rem = Pattern.compile("font-size:\\s*(\\.\\d+)rem").matcher(css);
        while (rem.find()) {
            double wert = Double.parseDouble("0" + rem.group(1));
            assertTrue(wert >= 0.85,
                    "font-size " + rem.group(1) + "rem ist zu klein (mindestens .85rem)");
        }
    }
}
