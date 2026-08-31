/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Card 420: an OWASP suppression whose version no longer appears in the dependency tree at all
 * has no effect — and it still looks like control.
 *
 * <p><b>Why that counts.</b> The entries in {@code quality/owasp-suppressions.xml} are deliberately
 * pinned to an exact version: {@code pkg:maven/…/angus-activation@2.0.3}. That is correct —
 * if Spring Boot pulls in a new version, the suppression no longer applies and the new state is
 * assessed anew (no permanent blind flight). The dead entry, however, stays behind. Whoever reads
 * the file takes it for an effective, justified exception; in fact it is waste paper. That is how the
 * file turns into the final repository that card 420 explicitly wants to prevent.</p>
 *
 * <p><b>Why this is no theoretical risk.</b> On 06.08.2026 four consumer repositories
 * (app, schuetu, iot, fwtool) carried a suppression on {@code tomcat-embed-*@11.0.22}, while the
 * build had long been resolving 11.0.24 through {@code plaintext-root-parent} — in iot and fwtool
 * additionally only for {@code tomcat-embed-core}, not for the sibling artifact {@code -websocket} with
 * the same eight CVEs. A suppression that only covers half is worse than none.</p>
 *
 * <p><b>What it is checked against.</b> Against the test classpath of the executing module — that is
 * the state the build really resolves, not the one a POM property promises. If an artifact is
 * found at a version other than the pinned one, the suppression is dead.</p>
 *
 * <p><b>Two kinds of entries, deliberately treated differently.</b> Besides exact pins the file also
 * knows {@code regex="true"} entries. Both are checked, provided the <em>version part</em>
 * is a literal ({@code …@11\.0\.22$} is a pin in substance and expires just the same). An entry
 * with an open version range ({@code …@.*$}) is the documented false-positive case — it never
 * expires and is therefore not reported as dead. Without that distinction the test would overlook
 * exactly those entries that were dead in app and schuetu.</p>
 *
 * <p><b>What the test can NOT do,</b> and that stands here so that nobody takes it for more: it sees
 * only artifacts that lie on the classpath of the executing module. A suppression for an
 * artifact that does not appear there at all cannot be judged by it — it reports such an entry as
 * unassessable instead of silently waving it through. And it says nothing about whether a
 * suppression is still justified in substance; only whether it still applies at all.</p>
 *
 * <p>Like the other classes of this module the test lives in {@code src/main/java} of
 * {@code plaintext-root-archtests} and runs in the consumer via Surefire {@code <dependenciesToScan>}
 * against that consumer's classpath — instead of being copied into five repositories.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextOwaspSuppressionsTest {

    /** {@code <packageUrl>pkg:maven/<group>/<artifact>@<version></packageUrl>} — exact pin. */
    private static final Pattern EXAKTER_PIN = Pattern.compile(
            "<packageUrl>\\s*pkg:maven/([^/]+)/([^@<\\s]+)@([^<\\s]+?)\\s*</packageUrl>");

    /** The same with {@code regex="true"} — groups: group, artifact expression, version expression. */
    private static final Pattern REGEX_PIN = Pattern.compile(
            "<packageUrl\\s+regex\\s*=\\s*\"true\"\\s*>\\s*\\^?pkg:maven/([^/]+)/([^@\\s]+)@([^<\\s]+?)\\$?\\s*</packageUrl>");

    /** Regex metacharacters by which an expression is recognized as "not literal". */
    private static final Pattern METAZEICHEN = Pattern.compile("[\\[\\]().*+?{}|^$]");

    /** Maven classifiers that are cut off the jar name before the version is detected. */
    private static final String[] KLASSIFIZIERER = {"-sources", "-javadoc"};

    @Test
    void keineWirkungsloseSuppression() throws IOException {
        Path datei = datei();
        assumeTrue(datei != null,
                "Dieses Repository führt keine quality/owasp-suppressions.xml — dann gibt es auch "
                        + "keine Ausnahme, die ablaufen könnte. Die CI-Pipeline übergibt die Datei "
                        + "ebenfalls nur, wenn es sie gibt (ci-cd-pipeline.yaml). Bewusst übersprungen "
                        + "statt rot: ein Repo ohne Ausnahmen ist der Normalfall, kein Fehler.");

        List<String[]> eintraege = eintraege(datei);
        assertFalse(eintraege.isEmpty(),
                "In " + datei + " wurde kein einziger auswertbarer Eintrag gefunden. Entweder hat "
                        + "sich das Format geändert oder der Test sucht falsch — ein grüner Lauf "
                        + "wäre dann wertlos.");

        Map<String, String> aufKlassenpfad = artefakteAufKlassenpfad();
        assertTrue(aufKlassenpfad.size() >= 50,
                "Nur " + aufKlassenpfad.size() + " Artefakte auf dem Testklassenpfad gefunden. "
                        + "Der Test kann so nichts beurteilen (erwartet werden dutzende). "
                        + "Vermutlich liegt der Klassenpfad in einer Manifest-Jar, die nicht gelesen wurde.");

        List<String> tot = new ArrayList<>();
        List<String> unbeurteilbar = new ArrayList<>();
        List<String> offen = new ArrayList<>();
        for (String[] e : eintraege) {
            String artefaktAusdruck = e[1];
            String versionAusdruck = e[2];
            boolean istRegex = "regex".equals(e[3]);

            if (istRegex && !istLiteral(versionAusdruck)) {
                // Open version range: the documented false-positive case (e.g. mxparser,
                // which OWASP assigns to the XStream CPE because of the groupId). It never expires, so by
                // definition it is never dead — only count it here, do not complain about it.
                offen.add(entschaerft(artefaktAusdruck) + "@" + versionAusdruck);
                continue;
            }

            String gepinnt = entschaerft(versionAusdruck);
            List<String> passende = passendeArtefakte(aufKlassenpfad, artefaktAusdruck, istRegex);
            if (passende.isEmpty()) {
                unbeurteilbar.add(entschaerft(artefaktAusdruck) + "@" + gepinnt);
                continue;
            }
            for (String artefakt : passende) {
                String tatsaechlich = aufKlassenpfad.get(artefakt);
                if (!tatsaechlich.equals(gepinnt)) {
                    tot.add(artefakt + ": Suppression pinnt " + gepinnt + ", im Build liegt "
                            + tatsaechlich);
                }
            }
        }

        // Make visible what the test has NOT judged — otherwise a green run reads as
        // "everything checked" although possibly not a single entry was assessable.
        System.out.println("PlaintextOwaspSuppressionsTest — Datei: " + datei
                + " · Einträge: " + eintraege.size()
                + " · offener Versionsbereich (läuft nie ab): "
                + (offen.isEmpty() ? "keine" : String.join(", ", offen))
                + " · nicht beurteilbar (Artefakt nicht auf dem Klassenpfad dieses Moduls): "
                + (unbeurteilbar.isEmpty() ? "keine" : String.join(", ", unbeurteilbar)));

        assertTrue(tot.isEmpty(),
                "Diese Suppressions greifen nicht mehr und gehören gelöscht:\n  "
                        + String.join("\n  ", tot)
                        + "\n\nEine versionsgepinnte Suppression läuft ab, sobald sich die Version "
                        + "ändert — das ist gewollt. Der tote Eintrag bleibt aber stehen und sieht "
                        + "weiter nach einer begründeten Ausnahme aus. Entweder löschen (wenn der "
                        + "Fund behoben ist) oder mit neuer Version UND neuer Begründung eintragen "
                        + "(Karte 420).");
    }

    /**
     * Counter-check on the detection itself: a constructed pin on a version that does not exist
     * has to be noticed. Without this case the test above would be green even if the comparison never
     * triggers.
     */
    @Test
    void dieErkennungSchlaegtBeiEinemTotenPinAn() throws IOException {
        Map<String, String> aufKlassenpfad = artefakteAufKlassenpfad();
        assertFalse(aufKlassenpfad.isEmpty(), "Kein Artefakt auf dem Klassenpfad erkannt.");
        Map.Entry<String, String> irgendeins = aufKlassenpfad.entrySet().iterator().next();

        String erfundeneVersion = irgendeins.getValue() + "-gibt-es-nicht";
        assertFalse(erfundeneVersion.equals(irgendeins.getValue()),
                "Die konstruierte Version muss sich von der echten unterscheiden.");
        assertTrue(aufKlassenpfad.containsKey(irgendeins.getKey())
                        && !aufKlassenpfad.get(irgendeins.getKey()).equals(erfundeneVersion),
                "Der Vergleich hält eine erfundene Version fälschlich für aktuell — dann sagt der "
                        + "Test oben nichts aus.");
    }

    /**
     * Counter-check on the reading of both kinds of entries. Without it there would be no way to see
     * whether the {@code regex="true"} branch applies at all — and precisely that branch covers the
     * entries that were dead in app and schuetu.
     */
    @Test
    void beideEintragssortenWerdenGelesen() {
        String probe = """
                <suppress>
                  <packageUrl regex="true">^pkg:maven/org\\.apache\\.tomcat\\.embed/tomcat-embed-[a-z]+@11\\.0\\.22$</packageUrl>
                </suppress>
                <suppress>
                  <packageUrl>pkg:maven/org.eclipse.angus/angus-activation@2.0.3</packageUrl>
                </suppress>
                <suppress>
                  <packageUrl regex="true">^pkg:maven/io\\.github\\.x-stream/mxparser@.*$</packageUrl>
                </suppress>
                """;
        List<String[]> gelesen = parse(probe);
        assertTrue(gelesen.size() == 3,
                "Erwartet werden drei Einträge (ein exakter Pin, ein Regex mit fester Version, ein "
                        + "Regex mit offener Version), gelesen: " + gelesen.size()
                        + ". Ändert sich das Dateiformat, fällt es hier auf und nicht erst, wenn "
                        + "eine tote Suppression durchrutscht.");

        // Deliberately searched by artifact instead of by position: parse() reads all exact entries first,
        // then all regex entries — an index access would silently swap the assignment.
        String[] exakt = suche(gelesen, "angus-activation");
        String[] festerRegex = suche(gelesen, "tomcat-embed-[a-z]+");
        String[] offenerRegex = suche(gelesen, "mxparser");
        assertTrue("exakt".equals(exakt[3]) && "regex".equals(festerRegex[3])
                        && "regex".equals(offenerRegex[3]),
                "Die Eintragssorte wurde falsch zugeordnet — dann prüft der Test oben die falsche "
                        + "Sorte und sagt nichts aus.");

        assertTrue(istLiteral(festerRegex[2]) && "11.0.22".equals(entschaerft(festerRegex[2])),
                "Der Versionsteil eines Regex-Eintrags mit fester Version muss als Literal erkannt "
                        + "und entschärft werden — sonst wird er nie mit dem Build verglichen.");
        assertFalse(istLiteral(offenerRegex[2]),
                "Ein offener Versionsbereich (@.*) darf NICHT als Literal gelten — er läuft nie ab "
                        + "und dürfte sonst fälschlich als tot gemeldet werden.");

        Map<String, String> klassenpfad = Map.of(
                "tomcat-embed-core", "11.0.24", "tomcat-embed-websocket", "11.0.24");
        assertTrue(passendeArtefakte(klassenpfad, festerRegex[1], true).size() == 2,
                "Ein Artefakt-Ausdruck mit Metazeichen muss ALLE passenden Artefakte treffen — "
                        + "sonst bliebe genau die halbe Abdeckung unbemerkt, die Karte 420 als "
                        + "'schlimmer als keine' benennt.");
    }

    /** The file has to lie where the CI pipeline expects it — otherwise it never applies in the build. */
    @Test
    void dieSuppressionDateiLiegtDaWoDiePipelineSieErwartet() throws IOException {
        Path datei = datei();
        assumeTrue(datei != null, "Dieses Repository führt keine quality/owasp-suppressions.xml.");
        Path wurzel = repoWurzel();
        assertTrue(wurzel != null && datei.equals(wurzel.resolve("quality").resolve("owasp-suppressions.xml")),
                "Erwartet unter <repo>/quality/owasp-suppressions.xml — die Pipeline übergibt genau "
                        + "diesen Pfad an dependency-check (ci-cd-pipeline.yaml). Gefunden: " + datei);
    }

    // ── Helpers ──────────────────────────────────────────────

    /** The suppression file from the reactor root — or {@code null} if the repository keeps none. */
    private static Path datei() throws IOException {
        Path wurzel = repoWurzel();
        if (wurzel == null) {
            return null;
        }
        Path d = wurzel.resolve("quality").resolve("owasp-suppressions.xml");
        return Files.isRegularFile(d) ? d : null;
    }

    private static Path repoWurzel() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** The entry for an artifact expression — fails with a clear message if it is missing. */
    private static String[] suche(List<String[]> eintraege, String artefaktAusdruck) {
        return eintraege.stream()
                .filter(e -> e[1].equals(artefaktAusdruck))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Eintrag '" + artefaktAusdruck + "' wurde aus der Probe nicht gelesen — "
                                + "dann greift der zugehörige Zweig des Parsers nicht."));
    }

    /** Entries as {@code [group, artefakt-Ausdruck, versions-Ausdruck, "exakt"|"regex"]}. */
    private static List<String[]> eintraege(Path datei) throws IOException {
        return parse(Files.readString(datei, StandardCharsets.UTF_8));
    }

    private static List<String[]> parse(String text) {
        List<String[]> ergebnis = new ArrayList<>();
        Matcher exakt = EXAKTER_PIN.matcher(text);
        while (exakt.find()) {
            ergebnis.add(new String[]{exakt.group(1), exakt.group(2), exakt.group(3), "exakt"});
        }
        Matcher regex = REGEX_PIN.matcher(text);
        while (regex.find()) {
            ergebnis.add(new String[]{regex.group(1), regex.group(2), regex.group(3), "regex"});
        }
        return ergebnis;
    }

    /**
     * An expression counts as a literal if it contains no <em>unescaped</em> regex metacharacter.
     * {@code 11\.0\.22} is therefore literal, {@code .*} and {@code [a-z]+} are not.
     *
     * <p>The escaped sequences are removed completely beforehand instead of only the backslash:
     * otherwise a bare dot would be left over from {@code \.} that looks like a metacharacter — and
     * <b>every</b> pinned version contains dots. The test would then hold not a single
     * regex entry to be checkable and would be silently ineffective. Exactly this defect was caught
     * by the counter-check {@code beideEintragssortenWerdenGelesen} while this class was being built.</p>
     */
    private static boolean istLiteral(String ausdruck) {
        return !METAZEICHEN.matcher(ausdruck.replaceAll("\\\\.", "")).find();
    }

    /** Removes backslash escapes: {@code 11\.0\.22} → {@code 11.0.22}. */
    private static String entschaerft(String ausdruck) {
        return ausdruck.replace("\\", "");
    }

    /**
     * All artifacts of the classpath that the expression matches. For an exact entry that is
     * at most one; for {@code tomcat-embed-[a-z]+} it is all sibling artifacts — and
     * their complete coverage is exactly the point.
     */
    private static List<String> passendeArtefakte(Map<String, String> klassenpfad, String ausdruck,
                                                  boolean istRegex) {
        List<String> treffer = new ArrayList<>();
        if (!istRegex || istLiteral(ausdruck)) {
            String name = entschaerft(ausdruck);
            if (klassenpfad.containsKey(name)) {
                treffer.add(name);
            }
            return treffer;
        }
        try {
            Pattern p = Pattern.compile(ausdruck);
            for (String name : klassenpfad.keySet()) {
                if (p.matcher(name).matches()) {
                    treffer.add(name);
                }
            }
        } catch (PatternSyntaxException e) {
            // Not a valid expression: the test can then judge nothing and reports the entry as
            // unassessable instead of breaking on a foreign file.
            return List.of();
        }
        return treffer;
    }

    /**
     * artifactId → version, from the jar names of the test classpath.
     *
     * <p>Depending on the configuration Surefire hands the classpath through as a single manifest
     * jar; the real list then stands in the {@code Class-Path} of that jar's manifest. Both cases are
     * handled — otherwise the test measures in the void, and that is exactly what does not stand out
     * on a positive finding.</p>
     */
    private static Map<String, String> artefakteAufKlassenpfad() throws IOException {
        List<String> eintraege = new ArrayList<>(
                List.of(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        if (eintraege.size() <= 2) {
            for (String e : new ArrayList<>(eintraege)) {
                if (!e.endsWith(".jar")) {
                    continue;
                }
                try (JarFile jf = new JarFile(e)) {
                    Manifest mf = jf.getManifest();
                    String cp = mf == null ? null : mf.getMainAttributes().getValue("Class-Path");
                    if (cp != null) {
                        for (String teil : cp.split("\\s+")) {
                            if (!teil.isBlank()) {
                                eintraege.add(entpacke(teil));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // no readable jar — then so be it
                }
            }
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String e : eintraege) {
            String name = e.substring(Math.max(e.lastIndexOf('/'), e.lastIndexOf('\\')) + 1);
            String[] artefakt = zerlegeJarName(name);
            if (artefakt.length == 2) {
                map.putIfAbsent(artefakt[0], artefakt[1]);
            }
        }
        return map;
    }

    /**
     * Decomposes a jar file name into {@code {artifactId, version}} — an <b>empty</b> array if
     * the name does not follow the Maven scheme {@code <artifactId>-<version>[-sources|-javadoc].jar}.
     *
     * <p>Empty array instead of {@code null} (Sonar {@code java:S1168}): the caller checks the length
     * and cannot dereference the result by accident.</p>
     *
     * <p>Deliberately without a regular expression. The earlier pattern
     * {@code ^(.*?)-(\d[^/\\]*?)(?:-(?:sources|javadoc))?\.jar$} had two nested
     * reluctant quantifiers and therefore polynomial runtime (Sonar {@code java:S5852}).
     * This decomposition reads the name in a single pass and produces the same split: the separator
     * is the first hyphen followed by a digit.</p>
     *
     * @param name file name without path, e.g. {@code plaintext-root-menu-1.544.0.jar}
     * @return two-element array {artifactId, version}, or an empty array
     */
    static String[] zerlegeJarName(String name) {
        if (name == null || !name.endsWith(".jar")) {
            return KEINE_ZERLEGUNG;
        }
        String rumpf = name.substring(0, name.length() - ".jar".length());
        for (String klassifizierer : KLASSIFIZIERER) {
            if (rumpf.endsWith(klassifizierer)) {
                rumpf = rumpf.substring(0, rumpf.length() - klassifizierer.length());
                break;
            }
        }
        for (int i = 0; i + 1 < rumpf.length(); i++) {
            if (rumpf.charAt(i) == '-' && Character.isDigit(rumpf.charAt(i + 1))) {
                return new String[]{rumpf.substring(0, i), rumpf.substring(i + 1)};
            }
        }
        return KEINE_ZERLEGUNG;
    }

    /** Answer for a name that does not follow the Maven scheme (java:S1168 — no {@code null}). */
    private static final String[] KEINE_ZERLEGUNG = new String[0];

    private static String entpacke(String eintrag) {
        try {
            return new File(new java.net.URI(eintrag)).getPath();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return eintrag;
        }
    }
}
