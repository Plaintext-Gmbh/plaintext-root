/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code w-} class used in a watch page must exist in {@code watch.css}.
 *
 * <p><b>Why this test exists.</b> A misspelt class name costs nothing at build time and nothing
 * at render time — the page simply appears unstyled, and on a 184 px display that means
 * unusable. The same failure shape cost two rounds in the alkohol module, where the menu
 * referenced the icon {@code pi-wine}, which PrimeIcons does not have (card 1243). A name that
 * is only ever checked by looking at the screen is not checked at all.
 *
 * <p><b>The positive control is not decoration.</b> This is a "nothing found" test: if the
 * regular expressions stop matching — a renamed file, a changed CSS format — it turns green
 * while checking nothing. So it first proves it can see both sides.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchStilVertragTest {

    /** Class attributes in the pages; {@code styleClass} and plain {@code class} alike. */
    private static final Pattern GENUTZT = Pattern.compile("\\bw-[a-z0-9-]+");

    /** Selectors in the stylesheet. */
    private static final Pattern DEFINIERT = Pattern.compile("\\.(w-[a-z0-9-]+)");

    private static Path wurzel() {
        Path p = Path.of("src/main/resources/META-INF/resources/watch");
        return Files.isDirectory(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    private static Set<String> gefunden(Pattern muster, String text, int gruppe) {
        Set<String> treffer = new TreeSet<>();
        Matcher m = muster.matcher(text);
        while (m.find()) {
            treffer.add(m.group(gruppe));
        }
        return treffer;
    }

    @Test
    @DisplayName("Jede in einer Watch-Seite benutzte w-Klasse steht auch im Stylesheet")
    void jedeGenutzteKlasseIstDefiniert() throws IOException {
        Path wurzel = wurzel();
        assertTrue(Files.isDirectory(wurzel), "Verzeichnis der Watch-Seiten nicht gefunden: " + wurzel.toAbsolutePath());

        String css = Files.readString(wurzel.resolve("watch.css"));
        Set<String> definiert = gefunden(DEFINIERT, css, 1);

        StringBuilder seiten = new StringBuilder();
        List<Path> dateien;
        try (Stream<Path> s = Files.list(wurzel)) {
            dateien = s.filter(p -> p.toString().endsWith(".xhtml")).sorted().toList();
        }
        for (Path datei : dateien) {
            seiten.append(Files.readString(datei)).append('\n');
        }
        Set<String> genutzt = gefunden(GENUTZT, seiten.toString(), 0);

        // Positivkontrolle: beide Seiten muessen ueberhaupt etwas hergeben, und ein Name, den es
        // sicher gibt, muss auf beiden Seiten auftauchen. Ohne das belegt ein leeres Ergebnis nur,
        // dass die Suche nicht funktioniert.
        assertFalse(dateien.isEmpty(), "Keine .xhtml unter " + wurzel.toAbsolutePath());
        assertTrue(definiert.size() >= 10, "Nur " + definiert.size() + " Klassen im Stylesheet erkannt — "
                + "das Muster passt nicht mehr auf das Format von watch.css");
        assertTrue(definiert.contains("w-btn") && genutzt.contains("w-btn"),
                "Positivkontrolle fehlgeschlagen: w-btn muesste auf beiden Seiten vorkommen "
                        + "(definiert=" + definiert.contains("w-btn") + ", genutzt=" + genutzt.contains("w-btn") + ")");

        Set<String> fehlend = new TreeSet<>(genutzt);
        fehlend.removeAll(definiert);
        assertTrue(fehlend.isEmpty(),
                () -> "Diese Klassen stehen in einer Watch-Seite, aber in keinem Selektor von watch.css:\n  "
                        + String.join("\n  ", fehlend)
                        + "\nAuf einer so kleinen Anzeige ist eine Seite ohne Stil unbedienbar, und der Fehler "
                        + "faellt weder beim Bauen noch beim Rendern auf.");
    }
}
