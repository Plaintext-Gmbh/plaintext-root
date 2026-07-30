/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Geteilte Leitplanke gegen <b>private Schlüssel im ausgelieferten Artefakt</b>: scannt in ALLEN
 * Modulen des jeweiligen Reactors die {@code src/main/resources} und schlägt fehl, sobald dort eine
 * Datei mit privatem Schlüsselmaterial liegt.
 *
 * <p><b>Hintergrund (Karte 305/347):</b> In {@code plaintext-admin-apitoken/src/main/resources/keys/}
 * lag der private JWT-Signierschlüssel im Repo — und damit im gebauten JAR, das über das NAS-Maven-Repo
 * an alle Apps verteilt wird. Wer Zugriff auf Repo, JAR oder Image hatte, konnte damit ein Token für
 * einen ROOT-Benutzer signieren und die MCP-Endpoints jeder Instanz übernehmen. Der Schlüssel ist
 * entfernt, jede Instanz signiert und validiert seither ausschliesslich mit ihrem eigenen Schlüsselpaar
 * aus dem Vault. Dieser Test verhindert, dass so etwas versehentlich zurückkommt — auch in einem der
 * Consumer-Repos (app/guild/schuetu/iot), wo der Linter als Jar mitläuft.</p>
 *
 * <p><b>Warum ein Dateiscan statt ArchUnit?</b> Schlüssel sind Ressourcen, kein Bytecode — ArchUnit
 * sieht sie gar nicht. Gescannt wird ab der Reactor-Wurzel jedes Modul-{@code src/main/resources};
 * {@code src/test/resources} bleibt bewusst aussen vor: ein reiner Test-Schlüssel wird nicht
 * ausgeliefert und kann in PROD nichts signieren (dort ist der Classpath-Fallback fail-closed
 * abgeschaltet).</p>
 *
 * <p>Wie die übrigen Klassen dieses Moduls liegt der Test in {@code src/main/java} von
 * {@code plaintext-root-archtests} und läuft im Consumer via Surefire {@code <dependenciesToScan>}
 * gegen dessen Ressourcen.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextPrivateKeyBanTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * PEM-Kopfzeilen privater Schlüssel. Der öffentliche Teil ({@code BEGIN PUBLIC KEY}) ist
     * ausdrücklich erlaubt — er darf und soll ausgeliefert werden.
     */
    private static final List<String> PRIVATE_KEY_MARKERS = List.of(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN DSA PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "-----BEGIN PGP PRIVATE KEY BLOCK-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----");

    /** Kleiner Deckel, damit der Scan nicht an einer grossen Binärdatei hängen bleibt. */
    private static final long MAX_SCAN_BYTES = 2L * 1024 * 1024;

    /**
     * Marker der eigenen Linter-Quelle: Dieses Modul liefert den Linter aus und trägt die
     * PEM-Kopfzeilen als String-Literale — es darf sich nicht selbst prüfen. Im Consumer liegt der
     * Linter als Jar vor, dort greift die Ausnahme nie.
     */
    private static final String OWN_SOURCE_MARKER = "ch/plaintext/arch/PlaintextPrivateKeyBanTest.java";

    @Test
    void keinPrivaterSchluesselInAusgeliefertenRessourcen() throws IOException {
        List<Path> resourceRoots = findResourceRoots();

        Path repoRoot = findRepoRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());

        List<String> violations = new ArrayList<>();
        for (Path root : resourceRoots) {
            // Modulpfad mit ausgeben, sonst sagt "keys/private.pem" nicht, WELCHES Modul betroffen ist.
            String modul = (repoRoot != null && root.startsWith(repoRoot)) ? repoRoot.relativize(root).toString()
                                                                          : root.toString();
            for (String hit : scanForPrivateKeys(root)) {
                violations.add(modul + "/" + hit);
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("\n\n=== PRIVATER SCHLUESSEL IM AUSGELIEFERTEN ARTEFAKT ===\n");
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nDateien unter src/main/resources landen im JAR und werden ueber das NAS-Maven-Repo\n")
               .append("an alle Apps verteilt — ein privater Schluessel dort gilt als kompromittiert (Karte 305).\n")
               .append("Private Schluessel gehoeren in den Vault: plaintext.jwt.private-key-vault-item\n")
               .append("(bzw. plaintext.jwt.private-key-file fuer ein gemountetes Secret). Oeffentliche\n")
               .append("Schluessel duerfen bleiben.\n");
            fail(msg.toString());
        }
    }

    @Test
    void scannerErkenntPrivatenSchluesselUndErlaubtOeffentlichen(@TempDir Path tmp) throws IOException {
        Path keys = Files.createDirectories(tmp.resolve("keys"));
        Files.writeString(keys.resolve("private.pem"),
                "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADAN\n-----END PRIVATE KEY-----\n");
        Files.writeString(keys.resolve("legacy.key"),
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKC\n-----END RSA PRIVATE KEY-----\n");
        Files.writeString(keys.resolve("public.pem"),
                "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkq\n-----END PUBLIC KEY-----\n");
        Files.writeString(tmp.resolve("application.yml"),
                "plaintext:\n  jwt:\n    private-key-vault-item: app.jwt-signing-key\n");

        List<String> hits = scanForPrivateKeys(tmp);

        assertTrue(hits.stream().anyMatch(h -> h.contains("private.pem")),
                "PKCS#8-Privatkey muss erkannt werden: " + hits);
        assertTrue(hits.stream().anyMatch(h -> h.contains("legacy.key")),
                "RSA-Privatkey muss auch ohne .pem-Endung erkannt werden: " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.contains("public.pem")),
                "Oeffentlicher Schluessel darf NICHT als Verstoss zaehlen: " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.contains("application.yml")),
                "Ein Vault-Item-Verweis ist kein Schluesselmaterial: " + hits);
    }

    /**
     * Die Modulsuche muss auch <b>verschachtelte</b> Module finden (Karte 350) und
     * Build-Ausgabeverzeichnisse ({@code target}) auslassen — dort liegen Kopien, keine Quellen.
     */
    @Test
    void modulsucheFindetVerschachtelteModuleUndUeberspringtTarget(@TempDir Path reactor) throws IOException {
        Files.createDirectories(reactor.resolve("modul-a/" + RESOURCES_SUFFIX));
        Files.createDirectories(reactor.resolve("gruppe/modul-b/" + RESOURCES_SUFFIX));
        Files.createDirectories(reactor.resolve("modul-a/target/classes/keys"));

        List<Path> roots = new ArrayList<>();
        collectResourceRoots(reactor, roots, 0);

        assertTrue(roots.contains(reactor.resolve("modul-a/" + RESOURCES_SUFFIX)),
                "Modul auf erster Ebene muss gefunden werden: " + roots);
        assertTrue(roots.contains(reactor.resolve("gruppe/modul-b/" + RESOURCES_SUFFIX)),
                "Verschachteltes Modul muss gefunden werden: " + roots);
        assertFalse(roots.stream().anyMatch(p -> p.toString().contains("target")),
                "target/ darf nicht gescannt werden: " + roots);
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() throws IOException {
        assertTrue(scanForPrivateKeys(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(scanForPrivateKeys(null).isEmpty());
    }

    /**
     * Meldet jede Datei unter {@code root}, die eine PEM-Kopfzeile eines PRIVATEN Schlüssels enthält.
     * Entscheidend ist der Inhalt, nicht der Dateiname — ein umbenannter Schlüssel rutscht so nicht durch.
     */
    static List<String> scanForPrivateKeys(Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (enthaeltPrivatenSchluessel(file)) {
                    hits.add(root.relativize(file).toString());
                }
            }
        }
        return hits;
    }

    private static boolean enthaeltPrivatenSchluessel(Path file) {
        try {
            if (Files.size(file) > MAX_SCAN_BYTES) {
                return false;
            }
            String inhalt = Files.readString(file).toUpperCase(Locale.ROOT);
            return PRIVATE_KEY_MARKERS.stream().anyMatch(inhalt::contains);
        } catch (IOException e) {
            return false; // nicht lesbar/kein Text -> kein PEM
        }
    }

    /**
     * Alle {@code src/main/resources} des Reactors — eigenes Modul, Geschwistermodule und
     * <b>verschachtelte</b> Module (Karte 350). Ein Modul in einem Unterverzeichnis
     * ({@code gruppe/modul/src/main/resources}) wurde vorher nicht gescannt und war damit ein
     * blinder Fleck: dort abgelegtes Schlüsselmaterial landete unbemerkt im Artefakt.
     */
    private static List<Path> findResourceRoots() throws IOException {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        List<Path> roots = new ArrayList<>();
        Path own = start.resolve(RESOURCES_SUFFIX);
        if (Files.isDirectory(own) && !shipsThisLinter(start)) {
            roots.add(own);
        }

        Path repoRoot = findRepoRoot(start);
        if (repoRoot != null) {
            collectResourceRoots(repoRoot, roots, 0);
        }
        return roots;
    }

    /** Verzeichnisse, in denen keine Modulquellen liegen — nicht betreten (Laufzeit + Fehlalarme). */
    private static final List<String> SKIP_DIRS = List.of("target", "src", ".git", "node_modules", ".mvn", ".idea");

    /** Maximale Modultiefe unter der Reactor-Wurzel. */
    private static final int MAX_MODULE_DEPTH = 5;

    private static void collectResourceRoots(Path dir, List<Path> roots, int depth) throws IOException {
        if (depth > MAX_MODULE_DEPTH || shipsThisLinter(dir)) {
            return;
        }
        Path resources = dir.resolve(RESOURCES_SUFFIX);
        if (Files.isDirectory(resources) && !roots.contains(resources)) {
            roots.add(resources);
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (!SKIP_DIRS.contains(name) && !name.startsWith(".")) {
                    collectResourceRoots(child, roots, depth + 1);
                }
            }
        }
    }

    private static boolean shipsThisLinter(Path moduleDir) {
        return Files.isRegularFile(moduleDir.resolve("src/main/java").resolve(OWN_SOURCE_MARKER));
    }

    private static Path findRepoRoot(Path start) throws IOException {
        Path dir = start;
        for (int i = 0; i < 8 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
