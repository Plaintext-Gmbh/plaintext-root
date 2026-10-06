/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.modules.katalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Karte 1416 (Sonar-Hotspot java:S5852): Modulname aus dem Ort einer Klasse, jetzt ohne regulaere
 * Ausdruecke. Die Faelle sind zeichengleich zu den alten Ausdruecken
 * {@code .*}{@code /([^/]+)/target/classes/?.*} und {@code -\d+(\.\d+)*(-SNAPSHOT)?$}.
 */
class SchnittstellenKatalogModulTest {

    /** Die Klasse laedt beim ersten Zugriff Jackson; das soll nicht in die Zeitgrenze unten fallen. */
    @BeforeAll
    static void klasseLaden() {
        SchnittstellenKatalog.modulAusOrt("file:/w/x-1.jar");
    }

    /** Der alte Weg, nur hier im Test als Vergleich. */
    private static String alt(String s) {
        int jar = s.lastIndexOf(".jar");
        if (jar < 0) {
            return s.contains("/target/classes") ? s.replaceAll(".*/([^/]+)/target/classes/?.*", "$1") : "?";
        }
        String datei = s.substring(s.lastIndexOf('/', jar) + 1, jar);
        return datei.replaceAll("-\\d+(\\.\\d+)*(-SNAPSHOT)?$", "");
    }

    @Test
    @DisplayName("gleiches Ergebnis wie die alten Ausdruecke")
    void gleichWieAlt() {
        String[] orte = {
                "jar:file:/app/BOOT-INF/lib/plaintext-z-fotos-2.1905.0.jar!/",
                "file:/home/x/.m2/repository/ch/plaintext/plaintext-root-interfaces/1.747.0/plaintext-root-interfaces-1.747.0.jar",
                "file:/w/plaintext-admin-modules-1.747.0-SNAPSHOT.jar",
                "file:/w/junit-jupiter-api-5.12.2.jar",
                "file:/w/ohne-version.jar",
                "file:/w/nur-SNAPSHOT-SNAPSHOT.jar",
                "file:/w/komisch-1..2.jar",
                "file:/w/punkt-1.2..jar",
                "file:/w/plaintext-root/plaintext-admin-modules/target/classes/",
                "file:/w/a/target/classes",
                "file:/w/a/target/classes/x/target/classes/",
                "file:/irgendwo/klassen/",
        };
        for (String o : orte) {
            assertThat(SchnittstellenKatalog.modulAusOrt(o)).as(o).isEqualTo(alt(o));
        }
    }

    @Test
    @DisplayName("lange boesartige Eingaben laufen linear durch (zusammen unter 1 s)")
    void keinRueckverfolgen() {
        String lang = "file:" + "/a".repeat(100_000) + "/x/target/classes";
        String wiederholt = "file:/w" + "/target/classe".repeat(10_000) + "s";
        String versionen = "file:/w/x-" + "1.".repeat(100_000) + "a.jar";
        // Nur die Aufrufe stehen unter der Zeitgrenze, nicht das erste Laden von AssertJ.
        String[] r = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> new String[]{
                SchnittstellenKatalog.modulAusOrt(lang),
                SchnittstellenKatalog.modulAusOrt(wiederholt),
                SchnittstellenKatalog.modulAusOrt(versionen)});
        assertThat(r[0]).isEqualTo("x");
        assertThat(r[1]).isEqualTo("classe");
        assertThat(r[2]).startsWith("x-1.");
    }
}
