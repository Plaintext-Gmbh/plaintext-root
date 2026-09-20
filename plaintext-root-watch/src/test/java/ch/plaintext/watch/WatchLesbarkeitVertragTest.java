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
 * Karte 1285: Die Uhr muss lesbar bleiben, und wem sie zu klein ist, der muss zoomen koennen.
 *
 * <h2>Warum dieser Test existiert</h2>
 *
 * <p>Daniel hat die Schrift auf der Uhr dreimal als zu klein gemeldet — am 19.09.2026 zweimal
 * und am 20.09.2026 mit den Worten „ich kann dort fast nichts sehen viel zu klein alles".
 * Zwischen der ersten und der letzten Meldung wurde sie tatsaechlich zweimal erhoeht
 * (15px -> 17px in Karte 1258, 17px -> 21px in Karte 1276). Gesehen hat er davon nichts:
 * die zweite Erhoehung lag in root 1.700.0, und {@code plaintext-app} hing auf 1.699.0.</p>
 *
 * <p>Ein Test kann einen fehlenden Versionsbump nicht verhindern. Er kann aber verhindern, dass
 * jemand die Groesse spaeter beilaeufig wieder absenkt — etwa weil auf einem breiten
 * Entwicklerbildschirm alles zu gross wirkt. Auf einem Uhrendisplay wirkt es das nie.</p>
 *
 * <h2>Die Zoom-Sperre</h2>
 *
 * <p>Der Rahmen setzte bis zum 20.09.2026 {@code maximum-scale=1.0, user-scalable=no}. Damit
 * hatte niemand, dem die Schrift zu klein war, ein eigenes Mittel — und genau in dieser Lage
 * war Daniel drei Tage lang. Safari ignoriert die Sperre seit iOS 10, Brave und Chrome nicht,
 * und Daniels Bildschirmfotos stammen aus Brave.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchLesbarkeitVertragTest {

    /** Unter dieser Groesse war die Uhr nachweislich unbrauchbar. */
    private static final int MINDESTGROESSE_PX = 24;

    private static Path datei(String name) {
        Path p = Path.of("src/main/resources/META-INF/resources/watch/" + name);
        return Files.exists(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    @Test
    @DisplayName("Die Grundschrift der Uhr liegt bei mindestens " + MINDESTGROESSE_PX + "px")
    void grundschriftIstGrossGenug() throws IOException {
        String css = Files.readString(datei("watch.css"));

        Matcher m = Pattern.compile("--w-schrift:\\s*(\\d+)px").matcher(css);
        assertTrue(m.find(), "Die Variable --w-schrift ist nicht als px-Wert gesetzt. Sie ist die "
                + "einzige Stellschraube fuer die Schriftgroesse der ganzen Uhr — wird sie in eine "
                + "andere Einheit umgeschrieben, greift dieser Test nicht mehr.");

        int px = Integer.parseInt(m.group(1));
        assertTrue(px >= MINDESTGROESSE_PX,
                "--w-schrift steht auf " + px + "px, mindestens " + MINDESTGROESSE_PX + "px sind "
                        + "noetig. Daniel hat 15px, 17px und 21px nacheinander als zu klein "
                        + "gemeldet; 26px ist der Stand, auf den er sich am 20.09.2026 "
                        + "festgelegt hat (Karte 1285).");
    }

    @Test
    @DisplayName("Alle uebrigen Groessen haengen an der Grundschrift, nicht an festen px")
    void dieGroessenSkalierenMit() throws IOException {
        String css = Files.readString(datei("watch.css"));

        assertTrue(css.contains("html, body {"),
                "Die Schriftgroesse muss auf html gesetzt sein, nicht nur auf body — sonst bezieht "
                        + "sich jedes rem in dieser Datei weiter auf die 16px des Browsers, und das "
                        + "Anheben von --w-schrift laesst Beschriftungen und Knoepfe unveraendert "
                        + "klein. Genau daran haette die Erhoehung sonst vorbeigewirkt.");

        int festeGroessen = 0;
        Matcher m = Pattern.compile("font-size:\\s*(\\d+)px").matcher(css);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) < MINDESTGROESSE_PX) {
                festeGroessen++;
            }
        }
        assertTrue(festeGroessen == 0,
                festeGroessen + " Regel(n) setzen eine feste Schriftgroesse unter "
                        + MINDESTGROESSE_PX + "px. Feste px-Werte wachsen beim Anheben von "
                        + "--w-schrift nicht mit und sind deshalb genau die Stellen, die auf der "
                        + "Uhr unlesbar bleiben. Nimm rem.");
    }

    @Test
    @DisplayName("Der Rahmen sperrt das Zoomen nicht")
    void zoomenIstErlaubt() throws IOException {
        String rahmen = Files.readString(datei("frame.xhtml"));

        Matcher m = Pattern.compile("name=\"viewport\"[^>]*content=\"([^\"]*)\"",
                Pattern.DOTALL).matcher(rahmen);
        assertTrue(m.find(), "Kein viewport-Meta im Rahmen gefunden. Ohne es rendert der Browser "
                + "auf 980px virtueller Breite und skaliert die Seite herunter — dann ist jede "
                + "Schriftgroesse vergebens.");

        String inhalt = m.group(1);
        assertTrue(inhalt.contains("width=device-width"),
                "Der viewport muss width=device-width setzen, sonst rechnet der Browser mit 980px.");
        assertFalse(inhalt.contains("user-scalable=no"),
                "user-scalable=no nimmt dem Benutzer das Aufziehen mit zwei Fingern. Wem die "
                        + "Schrift zu klein ist, dem bleibt damit kein eigenes Mittel — und "
                        + "dieser Fall ist hier dreimal eingetreten (Karten 1258, 1276, 1285).");
        assertFalse(inhalt.contains("maximum-scale"),
                "maximum-scale deckelt das Zoomen und wirkt wie user-scalable=no. Beides gehoert "
                        + "nicht auf eine Oberflaeche, deren Lesbarkeit strittig ist.");
    }
}
