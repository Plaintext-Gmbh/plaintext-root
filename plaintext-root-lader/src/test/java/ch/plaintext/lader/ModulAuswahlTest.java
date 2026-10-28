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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1608: signierte Registries, Auswahl mit Version/latest, letzter guter Stand, festes Image. */
class ModulAuswahlTest {

    private static final byte[] A1 = TestJars.zip("ch/plaintext/a/A.class", "a1");
    private static final byte[] A2 = TestJars.zip("ch/plaintext/a/A.class", "a2");
    private static final byte[] A3 = TestJars.zip("ch/plaintext/a/A.class", "a3");
    private static final byte[] API = TestJars.zip("ch/plaintext/api/Api.class", "api");
    private static final byte[] LIB = TestJars.zip("org/example/lib/Lib.class", "lib");

    @TempDir
    Path dir;
    @TempDir
    Path konfig;
    @TempDir
    Path huellenDir;

    private static final String GEHEIM_KOPF = "Basic " + Base64.getEncoder().encodeToString("tok:pw".getBytes(StandardCharsets.UTF_8));

    private ModulLader.Huelle huelle;
    private HttpServer server;
    private final Map<String, byte[]> dateien = new ConcurrentHashMap<>();
    private KeyPair schluessel;
    private KeyPair fremderSchluessel;
    private final List<String> index = new ArrayList<>();

    @BeforeEach
    void vorbereiten() throws Exception {
        huelle = new ModulLader.Huelle("1.780.0", "1.767.0", TestJars.huelle(huellenDir.resolve("app.jar")));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String pfad = ex.getRequestURI().getPath();
            if (pfad.startsWith("/geheim/")) { // Karte 1618: wie eine Nextcloud-Freigabe, nur mit HTTP Basic
                if (!GEHEIM_KOPF.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    ex.sendResponseHeaders(401, -1);
                    ex.close();
                    return;
                }
                pfad = "/reg/" + pfad.substring("/geheim/".length());
            }
            byte[] b = pfad.equals("/reg/index.txt")
                    ? String.join("\n", index).getBytes(StandardCharsets.UTF_8) : dateien.get(pfad);
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
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        schluessel = g.generateKeyPair();
        fremderSchluessel = g.generateKeyPair();
        index.add(ModulAuswahl.INDEX_KOPF);
        quellen(schluessel, "");
    }

    @AfterEach
    void stoppen() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ Hilfen

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/reg/index.txt";
    }

    private void quellen(KeyPair k, String extra) throws IOException {
        Files.writeString(konfig.resolve(ModulAuswahl.QUELLEN), "# Test\nschluessel ed25519 "
                + Base64.getEncoder().encodeToString(k.getPublic().getEncoded()) + "\nregistry test " + url() + "\n" + extra);
    }

    /** Legt ein Jar in die Registry und eine signierte Zeile in den Index. */
    private String veroeffentlichen(String art, String name, String version, String rootMin, byte[] jar, String braucht,
                                    String bibliotheken, KeyPair k) throws Exception {
        String datei = "jars/" + name + "-" + version + ".jar";
        dateien.put("/reg/" + datei, jar);
        ModulAuswahl.Zeile z = new ModulAuswahl.Zeile(art, name, version, rootMin, sha(jar),
                braucht.isEmpty() ? List.of() : List.of(braucht.split(",")),
                bibliotheken.isEmpty() ? List.of() : List.of(bibliotheken.split(",")), datei, "x", "test", null);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(z.nachricht().getBytes(StandardCharsets.UTF_8));
        String zeile = z.alsIndexZeile().replace("signatur=x", "signatur=" + Base64.getEncoder().encodeToString(s.sign()));
        index.add(zeile);
        return zeile;
    }

    private String modul(String name, String version, String rootMin, byte[] jar) throws Exception {
        return veroeffentlichen("modul", name, version, rootMin, jar, "", "", schluessel);
    }

    private void auswahl(String... nameVersion) throws IOException {
        List<ModulAuswahl.Wahl> w = new ArrayList<>();
        for (int i = 0; i < nameVersion.length; i += 2) {
            w.add(new ModulAuswahl.Wahl(nameVersion[i], nameVersion[i + 1]));
        }
        Files.writeString(dir.resolve(ModulAuswahl.AUSWAHL), ModulAuswahl.auswahlText(w, "Test"));
    }

    private List<Path> starten() throws Exception {
        return new ModulLader(dir, new ModulQuelle(), null, huelle, konfig, dir.resolve("gibt-es-nicht")).vorbereiten();
    }

    private static List<String> namen(List<Path> jars) {
        return jars.stream().map(p -> p.getFileName().toString()).toList();
    }

    private Map<String, String> status() throws IOException {
        return ModulAuswahl.statusLesen(dir);
    }

    private List<String> cache() throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve("cache"))) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // ------------------------------------------------------------------ Tests

    @Test
    void festeVersionWirdGeladenUndAlsLetzterGuterStandGemerkt() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-a", "1.1.0", "1.770.0", A2);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(status()).containsEntry("ergebnis", "OK").containsEntry("geladen", "plaintext-z-a:1.0.0");
        assertThat(Files.readString(dir.resolve(ModulAuswahl.STAND))).contains("modul plaintext-z-a 1.0.0 ");
    }

    @Test
    void latestNimmtDieNeuesteZurHuellePassendeVersion() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-a", "1.10.0", "1.775.0", A2);
        modul("plaintext-z-a", "1.11.0", "1.790.0", A3); // braucht eine neuere Huelle
        auswahl("plaintext-z-a", "latest");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.10.0.jar");
    }

    @Test
    void latestPrueftV1RootMinGegenDieV1BasisDerV2Huelle() {
        ModulLader.Huelle v2 = new ModulLader.Huelle("2.805.2", "1.767.0", null);
        assertThat(ModulAuswahl.rootMinPasst("1.805.0", v2)).isNull();
        assertThat(ModulAuswahl.rootMinPasst("1.806.0", v2)).contains("v1-Basis 1.805.0");
        assertThat(ModulAuswahl.rootMinPasst("2.805.2", v2)).isNull();
        assertThat(ModulAuswahl.rootMinPasst("2.806.1", v2)).contains("> Huelle");
        assertThat(ModulAuswahl.rootMinPasst("1.760.0", v2)).contains("Kompatibel-Ab");
    }

    @Test
    void manipulierteSignaturWirdAbgelehntUndDerLetzteGuteStandStartet() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        auswahl("plaintext-z-a", "1.0.0");
        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");

        // neue Version mit ausgetauschtem Inhalt: Zeile nennt den neuen sha256, die Signatur ist die alte
        String echt = modul("plaintext-z-a", "1.1.0", "1.770.0", A2);
        index.remove(echt);
        dateien.put("/reg/jars/plaintext-z-a-1.1.0.jar", A3);
        index.add(echt.replaceFirst("sha256=[0-9a-f]{64}", "sha256=" + sha(A3)));
        auswahl("plaintext-z-a", "1.1.0");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(status()).containsEntry("ergebnis", "LETZTER-GUTER-STAND");
        assertThat(status().get("grund")).contains("Signatur ungueltig: plaintext-z-a 1.1.0");
        assertThat(cache()).containsExactly("plaintext-z-a-1.0.0.jar");
    }

    @Test
    void signaturEinesFremdenSchluesselsGiltNicht() throws Exception {
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "", "", fremderSchluessel);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status()).containsEntry("ergebnis", "OHNE-MODULE");
        assertThat(status().get("grund")).contains("Signatur ungueltig");
        assertThat(dir.resolve("cache/plaintext-z-a-1.0.0.jar")).doesNotExist();
    }

    @Test
    void ersterStartOhneLetztenGutenStandFaelltAufModuleYamlZurueck() throws Exception {
        // Karte 1618: Auswahl nicht anwendbar (fremder Schluessel), noch kein stand.txt, aber module.yaml von start.sh
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "", "", fremderSchluessel);
        auswahl("plaintext-z-a", "1.0.0");
        Files.createDirectories(dir.resolve("cache"));
        Files.write(dir.resolve("cache/plaintext-z-b-2.0.0.jar"), A2);
        Files.writeString(dir.resolve(ModulLader.KONFIG), """
                module:
                  - name: plaintext-z-b
                    version: 2.0.0
                    sha256: %s
                    root-min: "1.770.0"
                """.formatted(sha(A2)));

        assertThat(namen(starten())).containsExactly("plaintext-z-b-2.0.0.jar");
        assertThat(status()).containsEntry("ergebnis", "RUECKFALL-MODULE-YAML");
        assertThat(status().get("grund")).contains("Signatur ungueltig");
        assertThat(dir.resolve(ModulAuswahl.STAND)).doesNotExist();
    }

    private void ausgerollt(String name, String version, byte[] jar) throws Exception {
        Files.createDirectories(dir.resolve("cache"));
        Files.write(dir.resolve("cache/" + name + "-" + version + ".jar"), jar);
        Files.writeString(dir.resolve(ModulLader.KONFIG), """
                module:
                  - name: %s
                    version: %s
                    sha256: %s
                    root-min: "1.770.0"
                """.formatted(name, version, sha(jar)));
    }

    @Test
    void latestHeisstFuerEinAusgerolltesModulDessenVersion() throws Exception {
        // Karte 1618: Registry hat schon 1.1.0 (Release ohne Deploy), ausgerollt ist 1.0.0 -> 1.0.0
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-a", "1.1.0", "1.770.0", A2);
        ausgerollt("plaintext-z-a", "1.0.0", A1);
        auswahl("plaintext-z-a", "latest");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(status()).containsEntry("ergebnis", "OK").containsEntry("geladen", "plaintext-z-a:1.0.0");
    }

    @Test
    void standEinesFrueherenDeploysWeichtDemModuleYamlWeg() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        ausgerollt("plaintext-z-a", "1.0.0", A1);
        auswahl("plaintext-z-a", "latest");
        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar"); // stand.txt = 1.0.0

        // Deploy von 1.1.0, die Registry hat es noch nicht (Upload laeuft noch)
        ausgerollt("plaintext-z-a", "1.1.0", A2);
        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.1.0.jar");
        assertThat(status()).containsEntry("ergebnis", "RUECKFALL-MODULE-YAML");
        assertThat(status().get("grund")).contains("plaintext-z-a").contains("1.1.0");
    }

    @Test
    void gemeinsameAuswahlGiltFuerBeideSlots(@TempDir Path blau, @TempDir Path gruen, @TempDir Path gemeinsam) throws Exception {
        // Karte 1618: abwaehlen (Seite im Slot blau) -> Slotwechsel nach gruen -> bleibt abgewaehlt
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-b", "2.0.0", "1.770.0", API);
        Files.writeString(gemeinsam.resolve(ModulAuswahl.AUSWAHL), ModulAuswahl.auswahlText(List.of(
                new ModulAuswahl.Wahl("plaintext-z-a", "latest"), new ModulAuswahl.Wahl("plaintext-z-b", "latest")), "t"));
        assertThat(namen(slot(blau, gemeinsam))).containsExactlyInAnyOrder("plaintext-z-a-1.0.0.jar", "plaintext-z-b-2.0.0.jar");

        Files.writeString(gemeinsam.resolve(ModulAuswahl.AUSWAHL), ModulAuswahl.auswahlText(List.of(
                new ModulAuswahl.Wahl("plaintext-z-a", "latest")), "t"));
        assertThat(namen(slot(gruen, gemeinsam))).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(gruen.resolve(ModulAuswahl.AUSWAHL)).doesNotExist();
        assertThat(ModulAuswahl.statusLesen(gruen)).containsEntry("geladen", "plaintext-z-a:1.0.0");
    }

    private List<Path> slot(Path slot, Path gemeinsam) throws Exception {
        return new ModulLader(slot, new ModulQuelle(), null, huelle, konfig, slot.resolve("gibt-es-nicht"), gemeinsam).vorbereiten();
    }

    @Test
    void registryMitAnmeldungAusDerUmgebung() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        java.net.URI geheim = java.net.URI.create(url().replace("/reg/", "/geheim/"));
        ModulAuswahl.Quellen q = new ModulAuswahl.Quellen(ModulAuswahl.quellenLesen(konfig.resolve(ModulAuswahl.QUELLEN))
                .schluessel(), List.of(new ModulAuswahl.Registry("g", geheim, "REG_TEST")), false);

        ModulQuelle quelle = new ModulQuelle();
        ModulAuswahl.Angebot mit = ModulAuswahl.angebot(q, quelle, Map.of("REG_TEST", "tok:pw"));
        assertThat(mit.fehler()).isEmpty();
        ModulAuswahl.Zeile z = mit.zeilen().getFirst();
        Path jar = ModulAuswahl.bereitstellen(z, Files.createDirectories(dir.resolve("cache")), quelle, true);
        assertThat(jar.getFileName()).hasToString("plaintext-z-a-1.0.0.jar"); // auch das Jar mit Anmeldung

        assertThat(ModulAuswahl.angebot(q, new ModulQuelle(), Map.of()).fehler().get("g"))
                .contains("REG_TEST fehlt");
        String falsch = ModulAuswahl.angebot(q, new ModulQuelle(), Map.of("REG_TEST", "tok:falsch")).fehler().get("g");
        assertThat(falsch).contains("401").doesNotContain("falsch").doesNotContain("tok");
    }

    @Test
    void anmeldungNurMitHttpsUndGueltigemVariablennamen() throws Exception {
        Path f = konfig.resolve("q2.txt");
        Files.writeString(f, "registry g https://example.org/r/index.txt anmeldung-env=REG_X\n");
        assertThat(ModulAuswahl.quellenLesen(f).registries().getFirst().anmeldungEnv()).isEqualTo("REG_X");
        Files.writeString(f, "registry g http://example.org/r/index.txt anmeldung-env=REG_X\n");
        assertThatThrownBy(() -> ModulAuswahl.quellenLesen(f)).hasMessageContaining("nur mit einer https");
        Files.writeString(f, "registry g https://example.org/r/index.txt anmeldung=tok:pw\n");
        assertThatThrownBy(() -> ModulAuswahl.quellenLesen(f)).hasMessageContaining("unbekannte Zeile");
    }

    @Test
    void ausgetauschtesJarBeiEchterZeileWirdAbgelehnt() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        dateien.put("/reg/jars/plaintext-z-a-1.0.0.jar", A2);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("passt nicht zur signierten Zeile");
    }

    @Test
    void ohneSchluesselWirdNichtsGeladen() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        Files.writeString(konfig.resolve(ModulAuswahl.QUELLEN), "registry test " + url() + "\n");
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("kein Signatur-Schluessel");
    }

    @Test
    void deaktiviertesModulVerschwindetAusDemCache() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-b", "1.0.0", "1.770.0", TestJars.zip("ch/plaintext/b/B.class", "b"));
        auswahl("plaintext-z-a", "1.0.0", "plaintext-z-b", "1.0.0");
        assertThat(starten()).hasSize(2);

        auswahl("plaintext-z-b", "1.0.0");
        assertThat(namen(starten())).containsExactly("plaintext-z-b-1.0.0.jar");
        assertThat(cache()).containsExactly("plaintext-z-b-1.0.0.jar");

        Files.writeString(dir.resolve(ModulAuswahl.AUSWAHL), "auswahl: []\n");
        assertThat(starten()).isEmpty();
        assertThat(cache()).isEmpty();
    }

    @Test
    void registryNichtErreichbarStartetMitDemLetztenGutenStand() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        auswahl("plaintext-z-a", "latest");
        assertThat(starten()).hasSize(1);
        server.stop(0);

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(status()).containsEntry("ergebnis", "LETZTER-GUTER-STAND");
        assertThat(status().get("grund")).contains("Registry-Fehler");
    }

    @Test
    void manipulierterLetzterGuterStandStartetOhneModule() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        auswahl("plaintext-z-a", "1.0.0");
        starten();
        // Cache-Jar im Volume ausgetauscht, Registry weg
        Files.write(dir.resolve("cache/plaintext-z-a-1.0.0.jar"), A2);
        server.stop(0);

        assertThat(starten()).isEmpty();
        assertThat(status()).containsEntry("ergebnis", "OHNE-MODULE");
        assertThat(status().get("grund")).contains("letzter guter Stand ungueltig");
    }

    @Test
    void brauchtUndSignierteBibliothekenKommenMit() throws Exception {
        veroeffentlichen("api", "plaintext-z-api", "1.0.0", "1.770.0", API, "", "", schluessel);
        dateien.put("/reg/lib/lib-1.2.0.jar", LIB);
        index.add("bib org.example:lib 1.2.0 datei=lib/lib-1.2.0.jar");
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "plaintext-z-api:1.0.0",
                "org.example:lib:1.2.0@" + sha(LIB), schluessel);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar", "plaintext-z-api-1.0.0.jar", "lib-1.2.0.jar");
        // letzter guter Stand traegt die Bibliothek mit, ihr sha256 kommt aus der Modul-Signatur
        server.stop(0);
        assertThat(starten()).hasSize(3);
        assertThat(status()).containsEntry("ergebnis", "LETZTER-GUTER-STAND");
    }

    @Test
    void ausgetauschteBibliothekFaelltAuf() throws Exception {
        dateien.put("/reg/lib/lib-1.2.0.jar", TestJars.zip("org/example/lib/Lib.class", "boese"));
        index.add("bib org.example:lib 1.2.0 datei=lib/lib-1.2.0.jar");
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "", "org.example:lib:1.2.0@" + sha(LIB), schluessel);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("lib").contains("passt nicht zur signierten Zeile");
    }

    @Test
    void zweiModuleMitVerschiedenenBibliotheksVersionenBrechenAb() throws Exception {
        byte[] lib2 = TestJars.zip("org/example/lib/Lib.class", "lib2");
        dateien.put("/reg/lib/lib-1.2.0.jar", LIB);
        dateien.put("/reg/lib/lib-1.3.0.jar", lib2);
        index.add("bib org.example:lib 1.2.0 datei=lib/lib-1.2.0.jar");
        index.add("bib org.example:lib 1.3.0 datei=lib/lib-1.3.0.jar");
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "", "org.example:lib:1.2.0@" + sha(LIB), schluessel);
        veroeffentlichen("modul", "plaintext-z-b", "1.0.0", "1.770.0", TestJars.zip("ch/plaintext/b/B.class", "b"), "",
                "org.example:lib:1.3.0@" + sha(lib2), schluessel);
        auswahl("plaintext-z-a", "1.0.0", "plaintext-z-b", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("Versionskonflikt: Bibliothek org.example:lib");
    }

    @Test
    void bibliothekInAndererVersionAlsDieHuelleBrichtAb() throws Exception {
        // die Test-Huelle hat BOOT-INF/lib/huelle-lib-1.0.jar
        byte[] lib = TestJars.zip("org/example/x/X.class", "x");
        dateien.put("/reg/lib/huelle-lib-1.1.jar", lib);
        index.add("bib org.example:huelle-lib 1.1 datei=lib/huelle-lib-1.1.jar");
        veroeffentlichen("modul", "plaintext-z-a", "1.0.0", "1.770.0", A1, "", "org.example:huelle-lib:1.1@" + sha(lib), schluessel);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("die Huelle liefert 1.0");
    }

    @Test
    void dateiAusserhalbDerRegistryWirdNichtGeholt() throws Exception {
        String z = modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        index.remove(z);
        index.add(z.replace("datei=jars/", "datei=//example.org/jars/"));
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty();
        assertThat(status().get("grund")).contains("Registry-Fehler");
        assertThatThrownBy(() -> ModulAuswahl.indexParsen(ModulAuswahl.INDEX_KOPF + "\n"
                + z.replace("datei=jars/", "datei=https://example.org/"), "t", null)).hasMessageContaining("relativer Pfad");
    }

    @Test
    void lokaleVerzeichnisRegistryOhneServer() throws Exception {
        Path reg = Files.createDirectories(huellenDir.resolve("reg"));
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        Files.createDirectories(reg.resolve("jars"));
        Files.write(reg.resolve("jars/plaintext-z-a-1.0.0.jar"), A1);
        Files.writeString(reg.resolve("index.txt"), String.join("\n", index));
        Files.writeString(konfig.resolve(ModulAuswahl.QUELLEN), "schluessel ed25519 "
                + Base64.getEncoder().encodeToString(schluessel.getPublic().getEncoded()) + "\nregistry lokal "
                + reg.resolve("index.txt").toUri() + "\n");
        server.stop(0);
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(namen(starten())).containsExactly("plaintext-z-a-1.0.0.jar");
    }

    @Test
    void festesImageEinbettenUndStartenOhneRegistry() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-a", "1.1.0", "1.770.0", A2);
        Path eingabe = huellenDir.resolve("auswahl.yaml");
        Files.writeString(eingabe, "auswahl:\n  - name: plaintext-z-a\n    version: latest\n");
        Path ziel = huellenDir.resolve("eingebettet");
        assertThatThrownBy(() -> ModulLader.einbetten(eingabe, konfig.resolve(ModulAuswahl.QUELLEN), ziel,
                new ModulQuelle(), huelle)).hasMessageContaining("'latest' ist hier nicht erlaubt");

        Files.writeString(eingabe, "auswahl:\n  - name: plaintext-z-a\n    version: 1.0.0\n");
        ModulLader.einbetten(eingabe, konfig.resolve(ModulAuswahl.QUELLEN), ziel, new ModulQuelle(), huelle);
        assertThat(ziel.resolve("plaintext-z-a-1.0.0.jar")).exists();
        server.stop(0);

        // Laufzeit-Auswahl liegt daneben, wird aber nicht genommen
        auswahl("plaintext-z-a", "1.1.0");
        List<Path> jars = new ModulLader(dir, new ModulQuelle(), null, huelle, konfig, ziel).vorbereiten();
        assertThat(namen(jars)).containsExactly("plaintext-z-a-1.0.0.jar");
        assertThat(jars.get(0).getParent()).isEqualTo(ziel);

        // manipuliertes eingebettetes Jar: kein Start
        Files.write(ziel.resolve("plaintext-z-a-1.0.0.jar"), A3);
        assertThatThrownBy(() -> new ModulLader(dir, new ModulQuelle(), null, huelle, konfig, ziel).vorbereiten())
                .hasMessageContaining("sha256 passt nicht");
    }

    @Test
    void eingebettetNurAusdruecklichUeberschreibbar() throws Exception {
        modul("plaintext-z-a", "1.0.0", "1.770.0", A1);
        modul("plaintext-z-a", "1.1.0", "1.770.0", A2);
        Path eingabe = huellenDir.resolve("auswahl.yaml");
        Files.writeString(eingabe, "auswahl:\n  - name: plaintext-z-a\n    version: 1.0.0\n");
        Path ziel = huellenDir.resolve("eingebettet");
        ModulLader.einbetten(eingabe, konfig.resolve(ModulAuswahl.QUELLEN), ziel, new ModulQuelle(), huelle);
        quellen(schluessel, "eingebettet ueberschreibbar\n");
        auswahl("plaintext-z-a", "1.1.0");

        List<Path> jars = new ModulLader(dir, new ModulQuelle(), null, huelle, konfig, ziel).vorbereiten();
        assertThat(namen(jars)).containsExactly("plaintext-z-a-1.1.0.jar");
    }

    @Test
    void ohneAuswahlBleibtDerAlteWegUnveraendert() throws Exception {
        assertThat(starten()).isEmpty();
        assertThat(dir.resolve(ModulAuswahl.STATUS)).doesNotExist();
    }

    @Test
    void auswahlTextUndParser() {
        String t = ModulAuswahl.auswahlText(List.of(new ModulAuswahl.Wahl("plaintext-z-a", "latest"),
                new ModulAuswahl.Wahl("plaintext-z-b", "2.0.0")), "Seite");
        assertThat(ModulAuswahl.auswahlParsen(t, "t")).containsExactly(new ModulAuswahl.Wahl("plaintext-z-a", "latest"),
                new ModulAuswahl.Wahl("plaintext-z-b", "2.0.0"));
        assertThatThrownBy(() -> ModulAuswahl.auswahlParsen("auswahl:\n  - name: ../x\n    version: 1\n", "t"))
                .hasMessageContaining("ungueltiger Name");
        assertThatThrownBy(() -> ModulAuswahl.auswahlParsen("auswahl:\n  - name: a\n    url: http://x\n", "t"))
                .hasMessageContaining("genau 'name' und 'version'");
        assertThatThrownBy(() -> ModulAuswahl.auswahlText(List.of(new ModulAuswahl.Wahl("a", "1;rm")), "x"))
                .hasMessageContaining("ungueltige Version");
    }

    @Test
    void versionsvergleichNumerisch() {
        assertThat(ModulAuswahl.versionVergleich("2.2028.0", "3.0.0")).isNegative();
        assertThat(ModulAuswahl.versionVergleich("3.0.10", "3.0.9")).isPositive();
        assertThat(ModulAuswahl.versionVergleich("1.0", "1.0.0")).isZero();
    }

    // ------------------------------------------------------------------ Karte 1610: Entwicklungsmodus (./traeger)

    private static ModulAuswahl.Zeile unsigniert(String index) {
        return new ModulAuswahl.Zeile("modul", "plaintext-z-a", "1.0.0", "1.770.0", "0".repeat(64), List.of(), List.of(),
                "a.jar", ModulAuswahl.UNSIGNIERT, "lokal", java.net.URI.create(index));
    }

    private static final Map<String, String> DEV = Map.of("PLAINTEXT_MODULE_UNSIGNIERT", "entwicklung");

    @Test
    void unsigniertNurMitSchalterOhneProdAusFileRegistry() {
        System.clearProperty(ModulAuswahl.UNSIGNIERT_GELADEN);
        try {
            ModulAuswahl.signaturPruefen(unsigniert("file:/tmp/reg/index.txt"), List.of(), DEV, null);
            assertThat(System.getProperty(ModulAuswahl.UNSIGNIERT_GELADEN)).isEqualTo("plaintext-z-a:1.0.0");
        } finally {
            System.clearProperty(ModulAuswahl.UNSIGNIERT_GELADEN);
        }
    }

    @Test
    void unsigniertAbgelehntOhneSchalterMitProdOderNetzRegistry() {
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("file:/tmp/reg/index.txt"), List.of(), Map.of(), null))
                .hasMessageContaining("Entwicklungsmodus aus");
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("file:/tmp/reg/index.txt"), List.of(),
                Map.of("PLAINTEXT_MODULE_UNSIGNIERT", "ja"), null)).hasMessageContaining("Entwicklungsmodus aus");
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("file:/tmp/reg/index.txt"), List.of(),
                Map.of("PLAINTEXT_MODULE_UNSIGNIERT", "entwicklung", "SPRING_PROFILES_ACTIVE", "default, PROD"), null))
                .hasMessageContaining("Profil prod");
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("file:/tmp/reg/index.txt"), List.of(), DEV, "prod"))
                .hasMessageContaining("Profil prod");
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("https://registry.example/index.txt"), List.of(), DEV, null))
                .hasMessageContaining("nur aus einer file:-Registry");
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(unsigniert("http://127.0.0.1/index.txt"), List.of(), DEV, null))
                .hasMessageContaining("nur aus einer file:-Registry");
        assertThat(System.getProperty(ModulAuswahl.UNSIGNIERT_GELADEN)).isNull();
    }

    @Test
    void falscheSignaturBleibtImEntwicklungsmodusUngueltig() {
        ModulAuswahl.Zeile z = unsigniert("file:/tmp/reg/index.txt");
        ModulAuswahl.Zeile kaputt = new ModulAuswahl.Zeile(z.art(), z.name(), z.version(), z.rootMin(), z.sha256(), z.braucht(),
                z.bibliotheken(), z.datei(), "AAAA", z.registry(), z.index());
        assertThatThrownBy(() -> ModulAuswahl.signaturPruefen(kaputt, List.of(schluessel.getPublic()), DEV, null))
                .hasMessageContaining("Signatur ungueltig");
    }

    @Test
    void unsigniertesModulAusFileRegistryOhneSchalterStartetNicht() throws Exception {
        Path reg = Files.createDirectories(huellenDir.resolve("reg"));
        Files.write(reg.resolve("a.jar"), A1);
        Files.writeString(reg.resolve("index.txt"), ModulAuswahl.INDEX_KOPF + "\nmodul plaintext-z-a 1.0.0 root-min=1.770.0 sha256="
                + sha(A1) + " datei=a.jar signatur=" + ModulAuswahl.UNSIGNIERT + "\n");
        Files.writeString(konfig.resolve(ModulAuswahl.QUELLEN), "registry lokal " + reg.resolve("index.txt").toUri() + "\n");
        auswahl("plaintext-z-a", "1.0.0");

        assertThat(starten()).isEmpty(); // Testlauf ohne PLAINTEXT_MODULE_UNSIGNIERT: abgelehnt, kein Modul
        assertThat(status().get("grund")).contains("unsigniertes Modul plaintext-z-a 1.0.0").contains("Entwicklungsmodus aus");
    }
}
