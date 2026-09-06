/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard for the files of {@code pt:tableSettings} that exist in more than one artifact
 * (Karte 1077, decision 4).
 *
 * <p><b>The situation.</b> {@code plaintext-root-template} ships the tag
 * ({@code META-INF/tags/tableSettings.xhtml}) with its script and stylesheet. A consumer
 * replaces the template artifact by its own theme artifact ({@code plaintext-oblique-theme})
 * and carries a <b>copy</b> of exactly these three files — byte-identical on 05.09.2026, and
 * nobody would notice the day that stops being true: the copy keeps rendering, only without the
 * corrections root made in the meantime. Its own POM says what that costs: "on a root update the
 * 42 files have to be compared".</p>
 *
 * <p><b>How it is checked.</b> This class carries the SHA-256 of every shared file as root
 * released it. Every copy in reach — in the sources of the reactor the test runs in, and on the
 * class path — has to match. Two consequences, both intended:</p>
 * <ul>
 *   <li><b>In root</b> the test goes red as soon as one of the files changes. The message carries
 *       the new hash; updating the constant is part of the change. That is the price for the
 *       guard working without a copy of the files and without a cross-repository dependency.</li>
 *   <li><b>In a consumer</b> that ships its own copy, the test goes red as soon as the copy
 *       differs from the root version the consumer builds against — drift becomes visible in the
 *       build instead of staying silent in production.</li>
 * </ul>
 *
 * <p><b>Why a hash and not the files themselves.</b> A second copy inside this module would be
 * the very duplication the test is meant to expose; a build-time copy from the sibling module
 * would be one more mechanism to understand before the test can be trusted. A hash is a fact
 * that fits on one line and needs nothing but the file.</p>
 *
 * <p>Line endings are normalised before hashing, so a checkout with CRLF does not count as
 * drift. Content is compared byte for byte otherwise — a changed comment is a change.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextTableSettingsDriftTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * The shared files and their SHA-256 as released by root. Regenerate after a change with
     * {@code tr -d '\r' < <datei> | sha256sum} — or take the value from the failing assertion.
     */
    private static final Map<String, String> GETEILTE_DATEIEN = new LinkedHashMap<>();

    static {
        GETEILTE_DATEIEN.put("META-INF/tags/tableSettings.xhtml",
                "04634f4c473721fe15efc6a1de527f4e74b530cec8934b34eb37b4889bc77188");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/table-settings.js",
                "a62c5a8edba4a4a9919b0fae39630a23a5cfa2abb8fe2ec191b0e5b035af479b");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/table-settings.css",
                "cea9597337f3a24d3363336c6d26b19b50184b4ba003a865b5cf1011c0428d19");
    }

    /** One copy of a shared file: where it was found and what it hashes to. */
    private record Fundstelle(String ort, String sha256) {
    }

    @Test
    @DisplayName("Jede Kopie von tableSettings.xhtml, table-settings.js und table-settings.css entspricht dem Stand von root")
    void kopienEntsprechenRoot() {
        List<String> fehler = new ArrayList<>();
        int geprueft = 0;
        for (Map.Entry<String, String> geteilt : GETEILTE_DATEIEN.entrySet()) {
            String pfad = geteilt.getKey();
            String erwartet = geteilt.getValue();
            List<Fundstelle> kopien = fundstellen(pfad);
            if (kopien.isEmpty()) {
                // Positive control: a guard that finds nothing must not pass as green.
                fehler.add(pfad + ": keine Kopie gefunden — weder in einem src/main/resources des Reactors "
                        + "noch auf dem Klassenpfad. Fehlt plaintext-root-template (oder das Ersatz-Artefakt) "
                        + "im Test-Klassenpfad?");
                continue;
            }
            for (Fundstelle kopie : kopien) {
                geprueft++;
                if (!erwartet.equals(kopie.sha256())) {
                    fehler.add(pfad + " in " + kopie.ort() + ": SHA-256 " + kopie.sha256()
                            + " statt " + erwartet + " (Stand von root).");
                }
            }
        }
        int kopien = geprueft;
        assertTrue(fehler.isEmpty(), () -> "Geteilte Dateien von pt:tableSettings weichen ab (" + kopien
                + " Kopien geprueft):\n  " + String.join("\n  ", fehler)
                + "\n\nIn root: die Datei wurde geaendert — den Hash in PlaintextTableSettingsDriftTest "
                + "nachfuehren, das gehoert zur Aenderung.\n"
                + "In einer App mit eigener Kopie (plaintext-oblique-theme): die Kopie ist gegenueber der "
                + "eingebundenen root-Version abgedriftet — aus plaintext-root-template neu uebernehmen, "
                + "sonst fehlen ihr die Korrekturen von root.");
    }

    /** All copies of one shared file: reactor sources first, then everything the class path offers. */
    private static List<Fundstelle> fundstellen(String pfad) {
        List<Fundstelle> gefunden = new ArrayList<>();
        for (Path root : ReactorLayout.sourceRoots(RESOURCES_SUFFIX)) {
            Path datei = root.resolve(pfad);
            if (Files.isRegularFile(datei)) {
                try {
                    gefunden.add(new Fundstelle(ReactorLayout.relativ(datei), sha256(Files.readAllBytes(datei))));
                } catch (IOException e) {
                    throw new UncheckedIOException("Datei nicht lesbar: " + datei, e);
                }
            }
        }
        try {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(pfad);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                // A reactor module's target/classes shows up here as well as under src — that is
                // two copies of the same content and counts twice; harmless, and honest.
                try (InputStream in = url.openStream()) {
                    gefunden.add(new Fundstelle(url.toString(), sha256(in.readAllBytes())));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Klassenpfad nach " + pfad + " nicht durchsuchbar", e);
        }
        return gefunden;
    }

    /** SHA-256 over the content with CRLF folded to LF. */
    static String sha256(byte[] roh) {
        String text = new String(roh, StandardCharsets.UTF_8).replace("\r\n", "\n");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }
}
