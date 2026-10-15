/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Karte 1514: Inhaltspruefung vor dem Start. Die Jars liegen schon mit passendem Hash im Cache, damit nur
 * die Pruefung zaehlt (die Quelle zeigt ins Leere und wird nie gefragt).
 */
class ModulPruefungTest {

    @TempDir
    Path dir;

    @TempDir
    Path huellenDir;

    private ModulLader.Huelle huelle;
    private final List<String> eintraege = new ArrayList<>();

    @BeforeEach
    void huelle() throws IOException {
        huelle = new ModulLader.Huelle("1.780.0", "1.767.0", TestJars.huelle(huellenDir.resolve("app.jar")));
        Files.createDirectories(dir.resolve("cache"));
    }

    /** Legt ein Jar in den Cache und traegt es in die Konfig ein. */
    private void jar(String name, String zusatz, String... inhalt) throws IOException {
        byte[] b = TestJars.zip(inhalt);
        Files.write(dir.resolve("cache/" + name + "-1.0.0.jar"), b);
        eintraege.add("""
                  - name: %s
                    version: 1.0.0
                    sha256: %s
                %s""".formatted(name, ModulLaderTest.sha(b), zusatz));
    }

    private void modul(String name, String... inhalt) throws IOException {
        jar(name, "    root-min: 1.770.0\n", inhalt);
    }

    private List<Path> laden() throws Exception {
        Files.writeString(dir.resolve("module.yaml"), "module:\n" + String.join("", eintraege));
        ModulQuelle nirgends = new ModulQuelle(URI.create("http://127.0.0.1:9/"), URI.create("http://127.0.0.1:9/"));
        return new ModulLader(dir, nirgends, null, huelle).vorbereiten();
    }

    // --- Spring-Konfig im Jar ---

    @Test
    void sauberesModulWirdGeladen() throws Exception {
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a", "META-INF/resources/a.xhtml", "x",
                "ch/plaintext/a/package-info.class", "p", "META-INF/versions/21/ch/plaintext/a/A.class", "mr");
        modul("plaintext-z-b", "ch/plaintext/b/B.class", "b", "ch/plaintext/a/package-info.class", "p");
        assertThat(laden()).hasSize(2);
    }

    @Test
    void modulMitApplicationYmlWirdAbgewiesen() throws Exception {
        for (String datei : List.of("application.yml", "application-prod.yaml", "application.properties",
                "config/application.yml", "bootstrap.yml")) {
            eintraege.clear();
            modul("plaintext-z-a", "ch/plaintext/a/A.class", "a", datei, "spring.application.name: modul-gewinnt");
            assertThatThrownBy(this::laden).as(datei)
                    .hasMessageContaining("enthaelt " + datei).hasMessageContaining("verdraengen");
        }
    }

    @Test
    void auchBibliothekMitApplicationYmlWirdAbgewiesen() throws Exception {
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a");
        eintraege.set(0, eintraege.get(0) + "    bibliotheken: [org.example:lib:1.0.0]\n");
        jar("lib", "    gruppe: org.example\n", "org/example/L.class", "l", "application.properties", "x=1");
        assertThatThrownBy(this::laden).hasMessageContaining("lib-1.0.0.jar enthaelt application.properties");
    }

    @Test
    void modulDarfSichNichtInDenSpringStartEinhaengen() throws Exception {
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a",
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "ch.plaintext.a.A");
        assertThatThrownBy(this::laden).hasMessageContaining("AutoConfiguration.imports");
        eintraege.clear();
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a", "META-INF/spring.factories", "x=y");
        assertThatThrownBy(this::laden).hasMessageContaining("spring.factories");
    }

    @Test
    void bibliothekDarfSpringFactoriesHaben() throws Exception {
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a");
        eintraege.set(0, eintraege.get(0) + "    bibliotheken: [org.example:starter:1.0.0]\n");
        jar("starter", "    gruppe: org.example\n", "org/example/S.class", "s",
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "org.example.S");
        assertThat(laden()).hasSize(2);
    }

    // --- Klassen-Duplikate ---

    @Test
    void zweiModuleMitDerselbenKlasseWerdenAbgewiesen() throws Exception {
        modul("plaintext-z-a", "ch/plaintext/gemeinsam/Vertrag.class", "a");
        modul("plaintext-z-b", "ch/plaintext/gemeinsam/Vertrag.class", "b");
        assertThatThrownBy(this::laden).hasMessageContaining(
                "Klasse ch.plaintext.gemeinsam.Vertrag doppelt: plaintext-z-a-1.0.0.jar und plaintext-z-b-1.0.0.jar");
    }

    @Test
    void klasseDerHuelleImModulWirdAbgewiesen() throws Exception {
        modul("plaintext-z-a", TestJars.HUELLE_KLASSE, "schatten");
        assertThatThrownBy(this::laden).hasMessageContaining("ch.RootBootApplication aus plaintext-z-a-1.0.0.jar")
                .hasMessageContaining("BOOT-INF/classes");
        eintraege.clear();
        modul("plaintext-z-a", TestJars.HUELLE_LIB_KLASSE, "schatten");
        assertThatThrownBy(this::laden).hasMessageContaining("gibt es schon in der Huelle (huelle-lib-1.0.jar)");
    }

    @Test
    void ohneHuellenArchivKeineModule() throws Exception {
        huelle = new ModulLader.Huelle("1.780.0", "1.767.0", null);
        modul("plaintext-z-a", "ch/plaintext/a/A.class", "a");
        assertThatThrownBy(this::laden).hasMessageContaining("Exec-Jar der Huelle unbekannt");
    }

    @Test
    void archivAusManifestAdresse() throws Exception {
        assertThat(ModulLader.archivVon(URI.create("jar:file:/app/app.jar!/META-INF/MANIFEST.MF").toURL()))
                .isEqualTo(Path.of("/app/app.jar"));
        assertThat(ModulLader.archivVon(URI.create("file:/x/META-INF/MANIFEST.MF").toURL())).isNull();
    }
}
