/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.I18nSeedLinter;
import ch.plaintext.boot.utils.I18nSeedLinter.LabelReference;
import ch.plaintext.boot.utils.I18nSeedLinter.Seed;
import ch.plaintext.boot.utils.I18nSeedLinter.SeedRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Geteilter Vollstaendigkeits-Test der i18n-Seed-Dateien: jedes {@code #{i18n.t('…')}} in einem
 * Facelet des Reactors hat in einer Seed-CSV eine Zeile fuer die Leitsprache {@value #LEITSPRACHE};
 * Seed-Zeilen, die kein Facelet des Reactors verwendet, werden als Warnung ausgegeben (kein Fehler).
 *
 * <p><b>Ausloeser (Zustandsbericht 29.08.2026, §4):</b> Der Mechanismus war da, der Inhalt nicht.
 * root (287 Labels) und schuetu (129) rufen {@code i18n.t()}, aber familienweit gab es keine einzige
 * Seed-CSV — der Importer in {@code I18nService} lief bei jedem Start leer, Uebersetzungen lebten
 * nur in den Datenbanken der Instanzen (und dort standen ausschliesslich {@code X_}-Platzhalter).
 * Ohne Test faellt ein neues Label ohne Uebersetzung nie auf: {@code translate()} legt still einen
 * Platzhalter an, der Benutzer sieht {@code X_Speichern}.
 *
 * <p><b>Schluessel-Modell.</b> Es gibt keine Property-Keys; der Schluessel IST der deutsche
 * Vorgabetext ({@code i18n.t('Speichern')}), und {@code de} wird nie uebersetzt. Deshalb ist die
 * Leitsprache der Seed nicht {@code de}, sondern die erste Zielsprache {@code en}: eine
 * {@code de}-Zeile waere wirkungslos.
 *
 * <p><b>Woher die Seeds kommen.</b> Zwei Quellen, wie beim Importer ({@code classpath*:i18n/*.csv}):
 * <ol>
 *   <li><b>Reactor-Seeds</b> — jede {@code <modul>/src/main/resources/i18n/*.csv} des Reactors
 *       ({@link ReactorLayout}). Sie sind die Wahrheit fuer die eigenen Labels; nur fuer sie gibt es
 *       die Warnung „Zeile ohne Verwendung“, und Formfehler in ihnen (zu wenig Spalten, doppelte
 *       Zeile) sind Verstoesse.</li>
 *   <li><b>Fremd-Seeds</b> — {@code i18n/*.csv} aus Jars auf dem Test-Classpath, z. B.
 *       {@code plaintext-root.csv} aus plaintext-admin-i18n, wenn der Test in einem Consumer
 *       (app, guild, iot, schuetu) laeuft. Ihre Labels zaehlen als „von root geliefert“:
 *       {@code i18n.t('Speichern')} in schuetu braucht keine schuetu-Zeile. Gleichnamige Dateien
 *       eines Reactor-Seeds werden nicht als fremd gezaehlt — sonst wuerde in root ein veraltetes
 *       Jar aus {@code ~/.m2} die Quelle ueberdecken.</li>
 * </ol>
 *
 * <p><b>Ausnahmen:</b> {@code <!-- i18n-seed-ok -->} in derselben Zeile nimmt einen einzelnen
 * Aufruf aus; ganze Dateien nimmt die Allowlist des Reactors aus ({@code plaintext-arch-allowlist.txt},
 * Regel {@code i18n-seed}, Begruendung Pflicht — siehe {@link ArchAllowlist}). root fuehrt keine
 * Allowlist.
 *
 * <p>Lese-Logik (CSV-Parser, Facelet-Scan) liegt in {@link I18nSeedLinter} (plaintext-root-common)
 * und ist dieselbe, die der Importer verwendet. Dieser Test liegt in {@code src/main/java} von
 * {@code plaintext-root-archtests} und laeuft im Consumer via Surefire {@code <dependenciesToScan>}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextI18nSeedTest {

    static final String ALLOWLIST_REGEL = "i18n-seed";

    /** Sprache, fuer die jede Referenz eine Seed-Zeile braucht — die erste Zielsprache, nicht die Quellsprache de. */
    static final String LEITSPRACHE = "en";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    private static final String SEED_SUFFIX = "src/main/resources/i18n";

    /** Muster des Importers ({@code I18nService.SEED_PATTERN}); hier als Literal, damit archtests nicht an admin-i18n haengt. */
    private static final String SEED_PATTERN = "classpath*:i18n/*.csv";

    @Test
    void jedesI18nLabelHatEineSeedZeileInDerLeitsprache() {
        List<Path> resourceRoots = ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
        if (resourceRoots.isEmpty()) {
            return;
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);
        List<String> violations = new ArrayList<>(allowlist.fehler());

        // 1. Referenzen aus allen Facelets des Reactors
        List<LabelReference> references = new ArrayList<>();
        for (Path root : resourceRoots) {
            references.addAll(I18nSeedLinter.scanReferences(root));
        }

        // 2. Seeds: Reactor (Datei) und fremd (Classpath-Jar)
        Map<Path, Seed> reactorSeeds = reactorSeeds();
        Set<String> reactorSeedNames = new HashSet<>();
        reactorSeeds.keySet().forEach(p -> reactorSeedNames.add(p.getFileName().toString()));
        Map<String, Seed> foreignSeeds = foreignSeeds(reactorSeedNames);

        Set<String> seedLabels = new HashSet<>();
        Set<String> reactorLabels = new HashSet<>();
        for (Map.Entry<Path, Seed> e : reactorSeeds.entrySet()) {
            String rel = ReactorLayout.relativ(e.getKey());
            e.getValue().problems().forEach(p -> violations.add(rel + " -> " + p));
            Set<String> gesehen = new HashSet<>();
            for (SeedRow row : e.getValue().rows()) {
                if (!gesehen.add(row.defaultLabel() + "::" + row.languageCode())) {
                    violations.add(rel + ":" + row.line() + " -> doppelte Zeile fuer '" + row.defaultLabel()
                            + "' (" + row.languageCode() + ")");
                }
                reactorLabels.add(row.defaultLabel());
                if (LEITSPRACHE.equals(row.languageCode())) {
                    seedLabels.add(row.defaultLabel());
                }
            }
        }
        for (Seed seed : foreignSeeds.values()) {
            for (SeedRow row : seed.rows()) {
                if (LEITSPRACHE.equals(row.languageCode())) {
                    seedLabels.add(row.defaultLabel());
                }
            }
        }

        // 3. Fehlende Zeilen: Referenz ohne Leitsprachen-Zeile
        Set<String> referenced = new HashSet<>();
        Map<String, List<String>> fehlend = new LinkedHashMap<>();
        for (LabelReference ref : references) {
            referenced.add(ref.label());
            String rel = ReactorLayout.relativ(ref.file());
            if (!seedLabels.contains(ref.label()) && !allowlist.erlaubt(rel)) {
                fehlend.computeIfAbsent(ref.label(), k -> new ArrayList<>()).add(rel + ":" + ref.line());
            }
        }
        fehlend.forEach((label, orte) -> violations.add("'" + label + "' (" + LEITSPRACHE + ") fehlt — verwendet in "
                + String.join(", ", orte)));

        // 4. Warnung: Reactor-Seed-Zeilen ohne Verwendung
        Set<String> unbenutzt = new TreeSet<>(reactorLabels);
        unbenutzt.removeAll(referenced);
        if (!unbenutzt.isEmpty()) {
            StringBuilder warn = new StringBuilder("\nWARNUNG PlaintextI18nSeedTest: ")
                    .append(unbenutzt.size())
                    .append(" Seed-Label(s) ohne i18n.t()-Verwendung in diesem Reactor (kein Fehler — Zeile entfernen oder Label wieder verwenden):\n");
            unbenutzt.forEach(l -> warn.append("  ? '").append(l).append("'\n"));
            System.out.println(warn);
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("""
                    \n
                    === I18N-SEED UNVOLLSTAENDIG: i18n.t('…') ohne Seed-Zeile (Leitsprache %s) ===
                    (ohne Zeile legt I18nService.translate() zur Laufzeit still einen X_-Platzhalter an)
                    """.formatted(LEITSPRACHE));
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nSeeds gelesen: Reactor ").append(reactorSeeds.keySet().stream().map(ReactorLayout::relativ).toList())
               .append(", fremd ").append(foreignSeeds.keySet()).append("\n")
               .append("Fix: Zeile '<Label>;").append(LEITSPRACHE).append(";<Uebersetzung>' in die Seed-CSV des Moduls ")
               .append("(src/main/resources/i18n/*.csv) aufnehmen — root: plaintext-admin-i18n/src/main/resources/i18n/plaintext-root.csv.\n")
               .append("Begruendete Ausnahme: <!-- ").append(I18nSeedLinter.EXEMPT_COMMENT).append(" --> in derselben Zeile oder Eintrag '")
               .append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ").append(ArchAllowlist.DATEINAME).append(".\n");
            fail(msg.toString());
        }
    }

    /** Alle {@code <modul>/src/main/resources/i18n/*.csv} des Reactors, geparst. */
    private static Map<Path, Seed> reactorSeeds() {
        Map<Path, Seed> seeds = new LinkedHashMap<>();
        for (Path dir : ReactorLayout.sourceRoots(SEED_SUFFIX)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path csv : files.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
                    seeds.put(csv, I18nSeedLinter.parse(csv));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Seed-Verzeichnis nicht lesbar: " + dir, e);
            }
        }
        return seeds;
    }

    /**
     * {@code i18n/*.csv} von Jars auf dem Test-Classpath (Abhaengigkeiten wie plaintext-admin-i18n),
     * ohne Dateien, die unter der Reactor-Wurzel liegen oder gleich heissen wie ein Reactor-Seed.
     */
    private static Map<String, Seed> foreignSeeds(Set<String> reactorSeedNames) {
        Map<String, Seed> seeds = new LinkedHashMap<>();
        Path repoRoot = ReactorLayout.repoRoot();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN)) {
                String name = r.getFilename();
                if (name == null || reactorSeedNames.contains(name) || liegtImReactor(r, repoRoot)) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
                    seeds.put(herkunft(r), I18nSeedLinter.parse(reader));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Seed-Dateien auf dem Classpath nicht lesbar", e);
        }
        return seeds;
    }

    private static boolean liegtImReactor(Resource r, Path repoRoot) {
        if (repoRoot == null || !r.isFile()) {
            return false;
        }
        try {
            return r.getFile().toPath().toAbsolutePath().normalize().startsWith(repoRoot);
        } catch (IOException e) {
            return false;
        }
    }

    /** Kurzname der Fremdquelle fuer Meldungen: Jar-Name + Datei. */
    private static String herkunft(Resource r) {
        String d = r.getDescription();
        int bang = d.indexOf("!/");
        if (bang > 0) {
            String jar = d.substring(0, bang);
            d = jar.substring(jar.lastIndexOf('/') + 1) + "!/" + d.substring(bang + 2);
        }
        return d.replace("]", "");
    }

    // ------------------------------------------------------------------------------------------
    // Fixture-Tests der Scan-/Parse-Logik (laufen ueberall mit, brauchen keinen Reactor)
    // ------------------------------------------------------------------------------------------

    @Test
    void scannerFindetBeideQuoteFormenLoestEscapesUndEntitaetenUndRespektiertOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));
        Files.writeString(res.resolve("a.xhtml"), """
                <h:outputText value="#{i18n.t('Speichern')}"/>
                <p:commandButton value="#{i18n.t("Löschen")}" title="#{i18n.t('Wirklich löschen?', 'en')}"/>
                <small>#{i18n.t('Fragen &amp; Antworten')}</small>
                <small>#{i18n.t('Sprache / Language')}</small> <!-- i18n-seed-ok -->
                <small>#{i18n.t('It\\'s ok')}</small>
                """);
        Files.writeString(res.resolve("b.java"), "// i18n.t('nicht gescannt')");

        List<LabelReference> refs = I18nSeedLinter.scanReferences(res);

        List<String> labels = refs.stream().map(LabelReference::label).toList();
        assertEquals(List.of("Speichern", "Löschen", "Wirklich löschen?", "Fragen & Antworten", "It's ok"), labels,
                "Referenzen: " + refs);
        assertEquals(2, refs.get(1).line());
        assertTrue(refs.stream().allMatch(r -> r.file().getFileName().toString().equals("a.xhtml")));
        assertTrue(I18nSeedLinter.scanReferences(tmp.resolve("gibt/es/nicht")).isEmpty());
    }

    @Test
    void parserLiestKopfKommentareQuotesUndFormelSchutzUndMeldetKaputteZeilen(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("x.csv");
        Files.writeString(csv, """
                defaultLabel;languageCode;translatedText
                # Kommentar

                Speichern;en;Save
                "Gesamtgrösse";en;"Total ""Size""\"
                -- bitte wählen --;en;'-- please select --
                Mit;Semikolon;im;Text
                nur-zwei;en
                Leer;en;
                """);

        Seed seed = I18nSeedLinter.parse(csv);

        assertEquals(4, seed.rows().size(), "Zeilen: " + seed.rows());
        assertEquals(new SeedRow("Speichern", "en", "Save", 4), seed.rows().get(0));
        assertEquals(new SeedRow("Gesamtgrösse", "en", "Total \"Size\"", 5), seed.rows().get(1));
        assertEquals(new SeedRow("-- bitte wählen --", "en", "-- please select --", 6), seed.rows().get(2));
        assertEquals(new SeedRow("Mit", "Semikolon", "im;Text", 7), seed.rows().get(3));
        assertEquals(2, seed.problems().size(), "Probleme: " + seed.problems());
        assertTrue(seed.problems().get(0).startsWith("Zeile 8:"));
        assertTrue(seed.problems().get(1).startsWith("Zeile 9:"));
    }

    @Test
    void reactorSeedsUndFremdSeedsErgaenzenSich() {
        // Sichtbarkeit der Klassifikation: was in diesem Reactor liegt, ist nie "fremd".
        Map<Path, Seed> reactor = reactorSeeds();
        Set<String> names = new LinkedHashSet<>();
        reactor.keySet().forEach(p -> names.add(p.getFileName().toString()));
        Map<String, Seed> foreign = foreignSeeds(names);
        for (String f : foreign.keySet()) {
            assertTrue(names.stream().noneMatch(f::endsWith), "Reactor-Seed als fremd gezaehlt: " + f);
        }
    }
}
