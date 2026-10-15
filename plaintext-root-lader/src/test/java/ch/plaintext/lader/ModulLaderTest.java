/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1513: Hash, feste Quelle, Cache, Notfall-Schalter, Kompatibilitaet. */
class ModulLaderTest {

    static final byte[] JAR = TestJars.zip("ch/plaintext/probe/Probe.class", "probe");
    private static final String SHA = sha(JAR);
    private static final String PFAD = "/releases/ch/plaintext/plaintext-z-probe/1.0.0/plaintext-z-probe-1.0.0.jar";
    private ModulLader.Huelle huelle;

    @TempDir
    Path dir;

    @TempDir
    Path huellenDir;

    private HttpServer server;
    private HttpServer fremd;
    private final Map<String, byte[]> dateien = new ConcurrentHashMap<>();
    private final AtomicInteger anfragen = new AtomicInteger();
    private final AtomicInteger fremdeAnfragen = new AtomicInteger();
    private ModulQuelle quelle;

    @BeforeEach
    void starteServer() throws IOException {
        huelle = new ModulLader.Huelle("1.780.0", "1.767.0", TestJars.huelle(huellenDir.resolve("app.jar")));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            anfragen.incrementAndGet();
            String p = ex.getRequestURI().getPath();
            if (p.startsWith("/umleiten/")) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + fremd.getAddress().getPort() + PFAD);
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] b = dateien.get(p);
            if (b == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            }
            ex.close();
        });
        server.start();
        fremd = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fremd.createContext("/", ex -> {
            fremdeAnfragen.incrementAndGet();
            ex.sendResponseHeaders(200, JAR.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(JAR);
            }
            ex.close();
        });
        fremd.start();
        quelle = new ModulQuelle(basis("/releases/"), basis("/central/"));
        dateien.put(PFAD, JAR);
    }

    @AfterEach
    void stoppeServer() {
        server.stop(0);
        fremd.stop(0);
    }

    private URI basis(String pfad) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + pfad);
    }

    private ModulLader lader(String notfallEnv) {
        return new ModulLader(dir, quelle, notfallEnv, huelle);
    }

    private void konfig(String sha256) throws IOException {
        konfig(sha256, "1.770.0");
    }

    private void konfig(String sha256, String rootMin) throws IOException {
        Files.writeString(dir.resolve("module.yaml"), """
                # von der Modul-Ansicht geschrieben
                module:
                  - name: plaintext-z-probe
                    version: 1.0.0
                    sha256: %s
                    root-min: "%s"
                """.formatted(sha256, rootMin));
    }

    private List<String> cacheInhalt() throws IOException {
        Path cache = dir.resolve("cache");
        if (!Files.exists(cache)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cache)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // --- ohne Konfig: alles wie heute ---

    @Test
    void ohneKonfigWirdNichtsGeladen() throws Exception {
        assertThat(lader(null).vorbereiten()).isEmpty();
        assertThat(anfragen).hasValue(0);
        assertThat(dir.resolve("cache")).doesNotExist();
    }

    @Test
    void leereListeLaedtNichts() throws Exception {
        Files.writeString(dir.resolve("module.yaml"), "module: []\n");
        assertThat(lader(null).vorbereiten()).isEmpty();
        assertThat(anfragen).hasValue(0);
    }

    // --- Hash ---

    @Test
    void passenderHashWirdGeladenUndGecacht() throws Exception {
        konfig(SHA);
        List<Path> jars = lader(null).vorbereiten();
        assertThat(jars).containsExactly(dir.resolve("cache/plaintext-z-probe-1.0.0.jar"));
        assertThat(Files.readAllBytes(jars.get(0))).isEqualTo(JAR);
        assertThat(anfragen).hasValue(1);
    }

    @Test
    void falscherHashWirdNichtGeladen() throws Exception {
        konfig("0".repeat(64));
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("passt nicht").hasMessageContaining("nicht geladen");
        assertThat(cacheInhalt()).as("weder Jar noch Temp-Datei im Cache").isEmpty();
    }

    @Test
    void cacheTrefferOhneDownload() throws Exception {
        konfig(SHA);
        lader(null).vorbereiten();
        anfragen.set(0);
        dateien.clear(); // Quelle weg: der zweite Start darf sie nicht brauchen

        assertThat(lader(null).vorbereiten()).hasSize(1);
        assertThat(anfragen).hasValue(0);
    }

    @Test
    void manipulierteCacheDateiWirdVerworfenUndNeuGeholt() throws Exception {
        konfig(SHA);
        Files.createDirectories(dir.resolve("cache"));
        Files.writeString(dir.resolve("cache/plaintext-z-probe-1.0.0.jar"), "untergeschoben");

        List<Path> jars = lader(null).vorbereiten();
        assertThat(Files.readAllBytes(jars.get(0))).isEqualTo(JAR);
        assertThat(anfragen).hasValue(1);
    }

    @Test
    void manipulierteCacheDateiOhneQuelleBrichtAb() throws Exception {
        konfig(SHA);
        dateien.clear();
        Files.createDirectories(dir.resolve("cache"));
        Files.writeString(dir.resolve("cache/plaintext-z-probe-1.0.0.jar"), "untergeschoben");

        assertThatThrownBy(() -> lader(null).vorbereiten()).hasMessageContaining("404");
        assertThat(cacheInhalt()).isEmpty();
    }

    // --- Quelle ---

    @Test
    void urlInDerKonfigWirdAbgewiesen() throws Exception {
        Files.writeString(dir.resolve("module.yaml"), """
                module:
                  - name: plaintext-z-probe
                    version: 1.0.0
                    sha256: %s
                    root-min: 1.770.0
                    url: http://127.0.0.1:%d/x.jar
                """.formatted(SHA, fremd.getAddress().getPort()));
        assertThatThrownBy(() -> lader(null).vorbereiten()).hasMessageContaining("unbekannter Schluessel 'url'");
        assertThat(fremdeAnfragen).hasValue(0);
    }

    @Test
    void pfadAusbruchImNamenWirdAbgewiesen() {
        for (String name : List.of("../../etc/passwd", "a/b", "x%2f..", "http://evil")) {
            assertThatThrownBy(() -> ModulKonfig.parsen("""
                    module:
                      - name: "%s"
                        version: 1.0.0
                        sha256: %s
                        root-min: 1.770.0
                    """.formatted(name, SHA), "t")).as(name).hasMessageContaining("ungueltiger Wert fuer 'name'");
        }
        assertThatThrownBy(() -> ModulKonfig.parsen("""
                module:
                  - name: ok
                    version: 1.0.0/../../x
                    sha256: %s
                    root-min: 1.770.0
                """.formatted(SHA), "t")).hasMessageContaining("'version'");
        assertThatThrownBy(() -> ModulKonfig.parsen("""
                module:
                  - name: ok
                    gruppe: evil.com/x
                    version: 1.0.0
                    sha256: %s
                    root-min: 1.770.0
                """.formatted(SHA), "t")).hasMessageContaining("'gruppe'");
    }

    @Test
    void weiterleitungAufFremdesZielWirdNichtVerfolgt() throws Exception {
        quelle = new ModulQuelle(basis("/umleiten/"), basis("/central/"));
        konfig(SHA);
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .hasMessageContaining("302").hasMessageContaining("Weiterleitung");
        assertThat(fremdeAnfragen).as("fremder Server nie angefragt").hasValue(0);
        assertThat(cacheInhalt()).isEmpty();
    }

    @Test
    void festeQuellenJeGruppe() {
        ModulQuelle echt = new ModulQuelle();
        assertThat(echt.adresse(new ModulKonfig.Eintrag("ch.plaintext", "plaintext-z-a", "1.0.0", SHA, "1.770.0")))
                .hasToString("https://maven.plaintext.ch/releases/ch/plaintext/plaintext-z-a/1.0.0/plaintext-z-a-1.0.0.jar");
        assertThat(echt.adresse(new ModulKonfig.Eintrag("org.example", "lib", "2.0", SHA, "1.770.0")))
                .hasToString("https://repo1.maven.org/maven2/org/example/lib/2.0/lib-2.0.jar");
    }

    // --- Notfall-Schalter ---

    @Test
    void notfallUmgebungsvariableStartetOhneModule() throws Exception {
        konfig(SHA);
        assertThat(lader("aus").vorbereiten()).isEmpty();
        assertThat(lader(" AUS ").vorbereiten()).isEmpty();
        assertThat(anfragen).hasValue(0);
        assertThat(dir.resolve("cache")).doesNotExist();
    }

    @Test
    void notfallDateiStartetOhneModuleAuchBeiKaputterKonfig() throws Exception {
        Files.writeString(dir.resolve("module.yaml"), "voellig kaputt");
        Files.createFile(dir.resolve("NOTFALL-AUS"));
        assertThat(lader(null).vorbereiten()).isEmpty();
        assertThat(anfragen).hasValue(0);
    }

    @Test
    void ohneNotfallBrichtKaputteKonfigAb() throws Exception {
        Files.writeString(dir.resolve("module.yaml"), "voellig kaputt");
        assertThatThrownBy(() -> lader(null).vorbereiten()).hasMessageContaining("module:");
        assertThatThrownBy(() -> lader("an").vorbereiten()).hasMessageContaining("module:");
    }

    // --- Kompatibilitaet ---

    @Test
    void modulFuerNeuereHuelleWirdAbgewiesen() throws Exception {
        konfig(SHA, "1.781.0");
        assertThatThrownBy(() -> lader(null).vorbereiten()).hasMessageContaining("braucht root >= 1.781.0");
        assertThat(anfragen).hasValue(0);
    }

    @Test
    void modulVorKompatibelAbWirdAbgewiesen() throws Exception {
        konfig(SHA, "1.766.0");
        assertThatThrownBy(() -> lader(null).vorbereiten()).hasMessageContaining("erst ab 1.767.0");
        konfig(SHA, "1.767.0");
        assertThat(lader(null).vorbereiten()).hasSize(1);
    }

    @Test
    void ohneHuellenVersionKeineModule() throws Exception {
        konfig(SHA);
        assertThatThrownBy(() -> new ModulLader(dir, quelle, null, null).vorbereiten())
                .hasMessageContaining("Huellen-Version unbekannt");
        assertThat(anfragen).hasValue(0);
    }

    // --- Stueckliste (Karte 1514) ---

    private static final byte[] LIB = TestJars.zip("org/example/lib/Lib.class", "lib");
    private static final String LIB_PFAD = "/central/org/example/lib/1.2.0/lib-1.2.0.jar";

    private void konfigMitStueckliste(String bibliothek, String libVersion, String libSha) throws IOException {
        Files.writeString(dir.resolve("module.yaml"), """
                module:
                  - name: plaintext-z-probe
                    version: 1.0.0
                    sha256: %s
                    root-min: 1.770.0
                    bibliotheken: [%s]
                  - name: lib
                    gruppe: org.example
                    version: %s
                    sha256: %s
                """.formatted(SHA, bibliothek, libVersion, libSha));
    }

    @Test
    void stuecklisteMitPassendemHashWirdGeladen() throws Exception {
        dateien.put(LIB_PFAD, LIB);
        konfigMitStueckliste("org.example:lib:1.2.0", "1.2.0", sha(LIB));
        assertThat(lader(null).vorbereiten()).containsExactly(
                dir.resolve("cache/plaintext-z-probe-1.0.0.jar"), dir.resolve("cache/lib-1.2.0.jar"));
    }

    @Test
    void bibliothekMitAbweichendemHashWirdAbgewiesen() throws Exception {
        dateien.put(LIB_PFAD, TestJars.zip("org/example/lib/Lib.class", "manipuliert"));
        konfigMitStueckliste("org.example:lib:1.2.0", "1.2.0", sha(LIB));
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .hasMessageContaining("Modul 'lib' 1.2.0: sha256").hasMessageContaining("nicht geladen");
        assertThat(cacheInhalt()).doesNotContain("lib-1.2.0.jar");
    }

    @Test
    void stuecklisteOhneEintragMitHashWirdAbgewiesen() throws Exception {
        konfigMitStueckliste("org.example:fehlt:1.0.0", "1.2.0", sha(LIB));
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .hasMessageContaining("nennt org.example:fehlt:1.0.0, dazu fehlt ein Eintrag mit Hash");
        assertThat(anfragen).hasValue(0);
    }

    @Test
    void stuecklisteMitAndererVersionWirdAbgewiesen() throws Exception {
        konfigMitStueckliste("org.example:lib:1.1.0", "1.2.0", sha(LIB));
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .hasMessageContaining("nennt org.example:lib:1.1.0, eingetragen ist org.example:lib:1.2.0");
    }

    @Test
    void fremdJarOhneStuecklisteWirdAbgewiesen() throws Exception {
        konfigMitStueckliste("", "1.2.0", sha(LIB));
        assertThatThrownBy(() -> lader(null).vorbereiten())
                .hasMessageContaining("Fremd-Jar 'org.example:lib:1.2.0' steht in keiner Stueckliste");
        assertThat(anfragen).hasValue(0);
    }

    // --- Cache aufraeumen ---

    @Test
    void cacheBehaeltAktuellUndVorherUndLoeschtDenRest() throws Exception {
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve("plaintext-z-alt-0.9.0.jar"), "vorheriger Stand");
        Files.writeString(cache.resolve("plaintext-z-weg-0.1.0.jar"), "verwaist");
        Files.writeString(cache.resolve(".x.jar.abc.tmp"), "Rest eines Abbruchs");
        Files.writeString(cache.resolve("notiz.txt"), "fremd, bleibt");
        Files.writeString(dir.resolve("module.yaml.vorher"), """
                module:
                  - name: plaintext-z-alt
                    version: 0.9.0
                    sha256: %s
                    root-min: 1.770.0
                """.formatted("a".repeat(64)));
        konfig(SHA);

        lader(null).vorbereiten();

        assertThat(cacheInhalt()).containsExactly("notiz.txt", "plaintext-z-alt-0.9.0.jar", "plaintext-z-probe-1.0.0.jar");
    }

    @Test
    void unlesbaresVorherLoeschtNichts() throws Exception {
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve("plaintext-z-weg-0.1.0.jar"), "verwaist");
        Files.writeString(dir.resolve("module.yaml.vorher"), "kaputt");
        konfig(SHA);

        lader(null).vorbereiten();

        assertThat(cacheInhalt()).contains("plaintext-z-weg-0.1.0.jar");
    }

    // --- oeffentliche Pruefung fuer die Modul-Ansicht (Karte 1515) ---

    @Test
    void konfigPruefenNutztDenLaderParser() {
        assertThat(ModulLader.konfigPruefen("module:\n  - name: a\n    version: 1.0.0\n    sha256: " + SHA
                + "\n    root-min: 1.770.0\n")).containsExactly("a:1.0.0");
        assertThatThrownBy(() -> ModulLader.konfigPruefen("module:\n  - name: a\n    url: x\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbekannter Schluessel 'url'");
    }

    // --- Parser ---

    @Test
    void parserIstStreng() {
        assertThatThrownBy(() -> ModulKonfig.parsen("module:\n  - name: a\n    name: b\n", "t")).hasMessageContaining("doppelt");
        assertThatThrownBy(() -> ModulKonfig.parsen("module:\n - name: a\n", "t")).hasMessageContaining("Einrueckung");
        assertThatThrownBy(() -> ModulKonfig.parsen("module:\n  - name: a\n    version: 1.0.0\n", "t"))
                .hasMessageContaining("ohne 'sha256'");
        String zwei = """
                module:
                  - name: a
                    version: 1.0.0
                    sha256: %1$s
                    root-min: 1.770.0
                  - name: a
                    version: 2.0.0
                    sha256: %1$s
                    root-min: 1.770.0
                """.formatted(SHA);
        assertThatThrownBy(() -> ModulKonfig.parsen(zwei, "t")).hasMessageContaining("Modul 'a' doppelt");
        assertThat(ModulKonfig.parsen("module:\n  - name: a\n    version: 1.0.0\n    sha256: " + SHA.toUpperCase()
                + "\n    root-min: 1.770.0\n", "t").get(0).sha256()).isEqualTo(SHA);
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
