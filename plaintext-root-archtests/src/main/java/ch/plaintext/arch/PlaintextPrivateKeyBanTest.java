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
 * Shared guardrail against <b>private keys in the shipped artifact</b>: scans the
 * {@code src/main/resources} in ALL modules of the respective reactor and fails as soon as a
 * file with private key material lies there.
 *
 * <p><b>Background (card 305/347):</b> in {@code plaintext-admin-apitoken/src/main/resources/keys/}
 * the private JWT signing key was kept in the repository — and thereby in the built JAR that is
 * distributed to all apps via the NAS Maven repository. Anyone with access to the repository, the JAR
 * or the image could sign a token for a ROOT user with it and take over the MCP endpoints of every
 * instance. The key has been removed; since then every instance signs and validates exclusively with
 * its own key pair from the vault. This test prevents such a thing from coming back by accident —
 * also in one of the consumer repositories (app/guild/schuetu/iot), where the linter runs along as a jar.</p>
 *
 * <p><b>Why a file scan instead of ArchUnit?</b> Keys are resources, not bytecode — ArchUnit
 * does not see them at all. Scanned is every module's {@code src/main/resources} from the reactor root;
 * {@code src/test/resources} deliberately stays out: a pure test key is not
 * shipped and can sign nothing in PROD (the classpath fallback is switched off fail-closed
 * there).</p>
 *
 * <p>Like the other classes of this module the test lives in {@code src/main/java} of
 * {@code plaintext-root-archtests} and runs in the consumer via Surefire {@code <dependenciesToScan>}
 * against that consumer's resources.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextPrivateKeyBanTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * PEM header lines of private keys. The public part ({@code BEGIN PUBLIC KEY}) is
     * explicitly allowed — it may and should be shipped.
     */
    private static final List<String> PRIVATE_KEY_MARKERS = List.of(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN DSA PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "-----BEGIN PGP PRIVATE KEY BLOCK-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----");

    /** A small cap so that the scan does not get stuck on a large binary file. */
    private static final long MAX_SCAN_BYTES = 2L * 1024 * 1024;

    /**
     * Marker of our own linter source: this module ships the linter and carries the
     * PEM header lines as string literals — it must not check itself. In a consumer the
     * linter is present as a jar, where the exemption never applies.
     */
    private static final String OWN_SOURCE_MARKER = "ch/plaintext/arch/PlaintextPrivateKeyBanTest.java";

    @Test
    void keinPrivaterSchluesselInAusgeliefertenRessourcen() throws IOException {
        List<Path> resourceRoots = findResourceRoots();

        Path repoRoot = findRepoRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());

        List<String> violations = new ArrayList<>();
        for (Path root : resourceRoots) {
            // Print the module path as well, otherwise "keys/private.pem" does not say WHICH module is affected.
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
     * The module lookup must also find <b>nested</b> modules (card 350) and
     * skip build output directories ({@code target}) — those hold copies, not sources.
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
     * Reports every file below {@code root} that contains a PEM header line of a PRIVATE key.
     * What counts is the content, not the file name — a renamed key does not slip through this way.
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
            return false; // not readable / not text -> no PEM
        }
    }

    /**
     * All {@code src/main/resources} of the reactor — our own module, sibling modules and
     * <b>nested</b> modules (card 350). A module in a subdirectory
     * ({@code gruppe/modul/src/main/resources}) used not to be scanned and was therefore a
     * blind spot: key material placed there ended up in the artifact unnoticed.
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

    /** Directories that contain no module sources — do not descend into them (runtime + false alarms). */
    private static final List<String> SKIP_DIRS = List.of("target", "src", ".git", "node_modules", ".mvn", ".idea");

    /** Maximum module depth below the reactor root. */
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
