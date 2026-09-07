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
 * Drift guard for the files of {@code plaintext-root-template} that a consumer may carry as its
 * own copy instead of the dependency (Karte 1077 decision 4, widened by Karte 1088).
 *
 * <p><b>The situation.</b> {@code plaintext-root-template} ships 42 files under
 * {@code src/main/resources} that every plaintext-root application depends on. A consumer may
 * replace the template artifact by its own theme artifact ({@code plaintext-oblique-theme}, an
 * ESTV/Bund-branded ERSATZ per its own POM) and carries a <b>copy</b> of those same 42 paths.
 * Byte-identical the day the copy is made, and nobody would notice the day that stops being true:
 * the copy keeps rendering, only without the corrections root made in the meantime. Its own POM
 * says what that costs: "on a root update the 47 files have to be compared" (42 shared + 5 of its
 * own). Karte 1088 found the drift had already happened, unnoticed, for 17 of the 42 — this class
 * is the guard against it happening again silently.</p>
 *
 * <p><b>Not all 42 files carry the same rule.</b> 35 of them must stay byte-identical forever —
 * that is what this class checks, {@link #GETEILTE_DATEIEN}. The remaining 7
 * ({@code plaintext.taglib.xml}, {@code includes/template.xhtml}, {@code includes/topbar.xhtml},
 * {@code menu.xhtml}, {@code plaintext-layout/css/layout-{light,dark}.css},
 * {@code plaintext-layout/js/layout.js}) carry a documented, permanent Oblique-specific addition
 * on top of the root content (branding, a fixed horizontal menu, no colour picker, a growl styled
 * as an Oblique banner) — a hash equal to root's would be permanently wrong for THOSE, so they are
 * deliberately NOT in this list. {@code coreit-arch-inventar-design} pins its own expected hash
 * for those 7 in its own test (no cross-repository access from here — see Karte 1088, "was das
 * nicht abdeckt" below).</p>
 *
 * <p><b>How it is checked.</b> This class carries the SHA-256 of every purely-copied shared file
 * as root released it. Every copy in reach — in the sources of the reactor the test runs in, and
 * on the class path — has to match. Two consequences, both intended:</p>
 * <ul>
 *   <li><b>In root</b> the test goes red as soon as one of the files changes. The message carries
 *       the new hash; updating the constant is part of the change. That is the price for the
 *       guard working without a copy of the files and without a cross-repository dependency. The
 *       assertion message names the known consumer copy explicitly, so whoever updates the
 *       constant is pointed at the follow-up.</li>
 *   <li><b>In a consumer</b> that ships its own copy AND has both this jar and the copy on its
 *       test class path (Surefire {@code dependenciesToScan}), the test goes red as soon as the
 *       copy differs from the root version the consumer builds against — drift becomes visible in
 *       the build instead of staying silent in production.</li>
 * </ul>
 *
 * <p><b>Was das nicht abdeckt (Karte 1088):</b> {@code coreit-arch-inventar-design} (das Repo
 * hinter {@code plaintext-oblique-theme}) bindet {@code plaintext-root-archtests} heute NICHT ein
 * — das braucht Zugriff auf den BIT-Nexus fuer {@code ch.plaintext}-Koordinaten, siehe Karte 1089.
 * Diese Klasse laeuft dort also nicht automatisch und sieht die Oblique-Kopie nie. Der Ersatz dort
 * ist ein eigenstaendiger Test im Design-Repo selbst ({@code ObliqueTemplateDriftTest}), der ohne
 * diese Abhaengigkeit auskommt, aber root-seitige Aenderungen nur erkennt, wenn seine eigene
 * Hash-Tabelle von Hand nachgezogen wird — automatisch verkettet sind die beiden Tests nicht.</p>
 *
 * <p><b>Why a hash and not the files themselves.</b> A second copy inside this module would be
 * the very duplication the test is meant to expose; a build-time copy from the sibling module
 * would be one more mechanism to understand before the test can be trusted. A hash is a fact
 * that fits on one line and needs nothing but the file.</p>
 *
 * <p>Line endings are normalised before hashing, so a checkout with CRLF does not count as
 * drift. Content is compared byte for byte otherwise — a changed comment is a change. Binary
 * files (icons, favicon) go through the same UTF-8 round trip as text files; that is lossy for
 * them, but consistent on both sides of every comparison, so equality still means "same bytes".
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextRootTemplateDriftTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * The 35 purely-copied shared files (of 42 total; the other 7 carry a documented permanent
     * Oblique addition, see class Javadoc) and their SHA-256 as released by root. Regenerate
     * after a change with {@code tr -d '\r' < <datei> | sha256sum} — or take the value from the
     * failing assertion.
     */
    private static final Map<String, String> GETEILTE_DATEIEN = new LinkedHashMap<>();

    static {
        GETEILTE_DATEIEN.put("META-INF/tags/tableSettings.xhtml",
                "04634f4c473721fe15efc6a1de527f4e74b530cec8934b34eb37b4889bc77188");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/table-settings.js",
                "a62c5a8edba4a4a9919b0fae39630a23a5cfa2abb8fe2ec191b0e5b035af479b");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/table-settings.css",
                "cea9597337f3a24d3363336c6d26b19b50184b4ba003a865b5cf1011c0428d19");
        GETEILTE_DATEIEN.put("META-INF/resources/includes/app-template-extra.xhtml",
                "d16547aa69ae5e0aa45a6339ee93fa08a317e2b256b39890bd0e9cc165e8dfb0");
        GETEILTE_DATEIEN.put("META-INF/resources/includes/config.xhtml",
                "dfbe7b9f4243abfb4dda511748f51a7abfc909c2cf3491148c4d2f0e9ab972e3");
        GETEILTE_DATEIEN.put("META-INF/resources/includes/footer.xhtml",
                "576a659b0674cb4fd0a6c9efcc827d09136c39135792adad9c1d3819dda19845");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/button-spacing.css",
                "d4c2cdb58df54b3c7653ee25933648ac5f6564becbe77ff24af685c86bd3293a");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-avocado.css",
                "059c6f5be82cfdbf994fbfff0089026b6256f2d4dd764fe73d7ebe2080b1f260");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-blue.css",
                "4ed459b2e4538283a1d09a242b8fcfe38d5042cb1f3db9a5401fab7ef148635b");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-green.css",
                "1ce6ae95c2c9c7970f812cfa893974a8417f76696bf7bf711543c2252fbd5eda");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-orange.css",
                "b99f215d454f31a7b80fea638db45c1cc768c8d851abc651dad7020e9f63d332");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-purple.css",
                "42bd6b6ff11eb07335450c7a1bedd0be642dcce5b3bc2f15731ed6ef302cdf96");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-red.css",
                "da4c0be2c5356928cb229cae324989dbe254de981a64c9c363708e07fcb71f69");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-turquoise.css",
                "ddef80955ca2e4f98fbf92c78a892033ad0962fcf5996b45556b58cccf33e2fa");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/colors/color-yellow.css",
                "7653a6dac2f5dee1bea8abcc0b7169b50c06be6e0cb5e73e63c4987f31c06782");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/mobile-responsive.css",
                "bacff28259efda38ac10f281106dbdfc5c23e57588488d093d91deff5ea52ba6");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/primeflex.min.css",
                "eb6819b24b9489c43b9e86f5fb8c49baef36c85eb71377d6930387157cc180c9");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/css/primeicons.css",
                "7bf62eaa59ac915dbed62bc833e795cfefda94cfa1a3b414832ca81e117f756b");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/icons/primeicons.eot",
                "b126172a6d3687866735284d17d8df1a28ab039a75afb39f952461815a5cca31");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/icons/primeicons.svg",
                "2ab98f70e1f0b8ca2820d90f025f3900cdbc92b9f088baef77939d3e98876aff");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/icons/primeicons.ttf",
                "4af92916c6c0193fda8575232c68f53e9a4e54714018c98c0189379d90bb4756");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/icons/primeicons.woff",
                "f64855a136fd830ec8b7b38c046c101d7595e91cf4e008ad5e94b9529a5153d8");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/images/favicon.ico",
                "07836b4eebb662f33d1e889673dd9589b7f7ef77af73d59359c518b805c259b9");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/config.js",
                "7cb8a6d7fea237eee2d5ad0f7183a8e3fcb2829d4dd064cdd8962cc14f2192b3");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/global-search.js",
                "039fd4f0c3af9a29cf580a185ab2b0c6ec932c60c7ae9cf7df6d80b21baf3cef");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/menu.js",
                "bfe098faabdb33118a634910e59f4f13a0651f3befa99545f8015457bc9c9ef6");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/primefaces-fixes.js",
                "70a3b2db35424d4718db57fe8b6be33240714ec5063fdef00443d957b775ae35");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/pushstate.js",
                "ff84eff15a1bfa8ccec5234e3b965c9db65e5042241fe46915bd77a5b366b5d5");
        GETEILTE_DATEIEN.put("META-INF/resources/plaintext-layout/js/topbar.js",
                "2369f7e76dc883e5b42334a86281db088700673e3e5d79e4c0c3fb79cf7865cb");
        GETEILTE_DATEIEN.put("META-INF/resources/primefaces-plaintext/theme.css",
                "6ace021af81224c8c4036d2cccf718d3041b14511183c85fc78373d6c9f5d8b5");
        GETEILTE_DATEIEN.put("META-INF/tags/colAmount.xhtml",
                "ab2ef79b50c02ce2574b16df2e99e4d5d1fd54c54d1ba66d4150aa5bb601e480");
        GETEILTE_DATEIEN.put("META-INF/tags/colDate.xhtml",
                "d44083bd3c1c56c6827c52c0edfbd1c7dfe9257ad1da99ddb0f1a16841b65fd3");
        GETEILTE_DATEIEN.put("META-INF/tags/colText.xhtml",
                "c93a52fff9ae0a97e71dbf6da8771a256dc966331a8882ad662ef7347943f44e");
        GETEILTE_DATEIEN.put("META-INF/tags/deleteButton.xhtml",
                "b8d851d953ed15e3327eda2f6e44f119ce6b596c9956e97eea7bad8b1c572fd1");
        GETEILTE_DATEIEN.put("META-INF/tags/table.xhtml",
                "81adc0ddd9ab2c5c7a766ff884a88f90ce55720d9df2b2dbd189217cb138e2fc");
    }

    /** One copy of a shared file: where it was found and what it hashes to. */
    private record Fundstelle(String ort, String sha256) {
    }

    @Test
    @DisplayName("Jede Kopie der 35 unveraendert geteilten plaintext-root-template-Dateien entspricht dem Stand von root")
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
        assertTrue(fehler.isEmpty(), () -> "Geteilte Dateien von plaintext-root-template weichen ab (" + kopien
                + " Kopien geprueft):\n  " + String.join("\n  ", fehler)
                + "\n\nIn root: die Datei wurde geaendert — den Hash in PlaintextRootTemplateDriftTest "
                + "nachfuehren, das gehoert zur Aenderung. Bekannter Konsument mit eigener Kopie: "
                + "coreit-arch-inventar-design (Artefakt plaintext-oblique-theme) — dort ebenfalls "
                + "nachziehen (Karte 1088), auch wenn diese Klasse das Repo nicht selbst sieht.\n"
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
