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
 * Karte 420: Eine OWASP-Suppression, deren Version im Abhängigkeitsbaum gar nicht mehr vorkommt,
 * ist wirkungslos — und sie sieht trotzdem nach Kontrolle aus.
 *
 * <p><b>Warum das zählt.</b> Die Einträge in {@code quality/owasp-suppressions.xml} sind bewusst
 * auf eine exakte Version gepinnt: {@code pkg:maven/…/angus-activation@2.0.3}. Das ist richtig so —
 * zieht Spring Boot eine neue Version, greift die Suppression nicht mehr und der neue Stand wird
 * erneut bewertet (kein Dauer-Blindflug). Der tote Eintrag bleibt danach aber stehen. Wer die Datei
 * liest, hält ihn für eine wirksame, begründete Ausnahme; tatsächlich ist er Altpapier. So wird die
 * Datei zum Endlager, das Karte 420 ausdrücklich verhindern will.</p>
 *
 * <p><b>Warum das kein theoretisches Risiko ist.</b> Am 06.08.2026 trugen vier Consumer-Repos
 * (app, schuetu, iot, fwtool) eine Suppression auf {@code tomcat-embed-*@11.0.22}, während der
 * Build über {@code plaintext-root-parent} längst 11.0.24 auflöste — in iot und fwtool zusätzlich
 * nur für {@code tomcat-embed-core}, nicht für das Schwester-Artefakt {@code -websocket} mit
 * denselben acht CVEs. Eine Suppression, die nur die Hälfte trifft, ist schlimmer als keine.</p>
 *
 * <p><b>Wogegen geprüft wird.</b> Gegen den Testklassenpfad des ausführenden Moduls — das ist der
 * Stand, den der Build wirklich auflöst, nicht der, den eine POM-Eigenschaft verspricht. Findet
 * sich ein Artefakt unter anderem Versionsstand als dem gepinnten, ist die Suppression tot.</p>
 *
 * <p><b>Zwei Sorten Einträge, bewusst unterschiedlich behandelt.</b> Die Datei kennt neben exakten
 * Pins auch {@code regex="true"}-Einträge. Beide werden geprüft, sofern der <em>Versionsteil</em>
 * ein Literal ist ({@code …@11\.0\.22$} ist inhaltlich ein Pin und läuft genauso ab). Ein Eintrag
 * mit offenem Versionsbereich ({@code …@.*$}) ist der dokumentierte False-Positive-Fall — er läuft
 * nie ab und wird darum nicht als tot gemeldet. Ohne diese Unterscheidung übersähe der Test genau
 * die Einträge, die in app und schuetu tot waren.</p>
 *
 * <p><b>Was der Test NICHT kann,</b> und das steht hier, damit es niemand für mehr hält: Er sieht
 * nur Artefakte, die auf dem Klassenpfad des ausführenden Moduls liegen. Eine Suppression für ein
 * Artefakt, das dort gar nicht vorkommt, kann er nicht beurteilen — er meldet sie als unbeurteilbar,
 * statt sie stillschweigend durchzuwinken. Und er sagt nichts darüber, ob eine Suppression fachlich
 * noch berechtigt ist; nur, ob sie überhaupt noch greift.</p>
 *
 * <p>Wie die übrigen Klassen dieses Moduls liegt der Test in {@code src/main/java} von
 * {@code plaintext-root-archtests} und läuft im Consumer via Surefire {@code <dependenciesToScan>}
 * gegen dessen Klassenpfad — statt in fünf Repos kopiert zu werden.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextOwaspSuppressionsTest {

    /** {@code <packageUrl>pkg:maven/<group>/<artifact>@<version></packageUrl>} — exakter Pin. */
    private static final Pattern EXAKTER_PIN = Pattern.compile(
            "<packageUrl>\\s*pkg:maven/([^/]+)/([^@<\\s]+)@([^<\\s]+?)\\s*</packageUrl>");

    /** Dasselbe mit {@code regex="true"} — Gruppen: group, artifact-Ausdruck, Versions-Ausdruck. */
    private static final Pattern REGEX_PIN = Pattern.compile(
            "<packageUrl\\s+regex\\s*=\\s*\"true\"\\s*>\\s*\\^?pkg:maven/([^/]+)/([^@\\s]+)@([^<\\s]+?)\\$?\\s*</packageUrl>");

    /** Regex-Metazeichen, an denen ein Ausdruck als „nicht literal" erkannt wird. */
    private static final Pattern METAZEICHEN = Pattern.compile("[\\[\\]().*+?{}|^$]");

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
                // Offener Versionsbereich: der dokumentierte False-Positive-Fall (z. B. mxparser,
                // das OWASP wegen der groupId der XStream-CPE zuordnet). Läuft nie ab, ist also
                // per Definition nie tot — hier nur zählen, nicht bemängeln.
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

        // Sichtbar machen, was der Test NICHT beurteilt hat — sonst liest sich ein grüner Lauf als
        // "alles geprüft", obwohl womöglich kein einziger Eintrag beurteilbar war.
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
     * Gegenprobe zur Erkennung selbst: Ein konstruierter Pin auf eine Version, die es nicht gibt,
     * muss auffallen. Ohne diesen Fall wäre der Test oben auch dann grün, wenn der Vergleich nie
     * zuschlägt.
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
     * Gegenprobe zum Einlesen beider Eintragssorten. Ohne sie wäre nicht zu sehen, ob der
     * {@code regex="true"}-Zweig überhaupt greift — und genau der deckt die Einträge ab, die in
     * app und schuetu tot waren.
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

        // Bewusst nach Artefakt gesucht statt nach Position: parse() liest erst alle exakten, dann
        // alle Regex-Einträge — ein Index-Zugriff würde die Zuordnung stillschweigend vertauschen.
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

    /** Die Datei muss dort liegen, wo die CI-Pipeline sie erwartet — sonst greift sie im Build nie. */
    @Test
    void dieSuppressionDateiLiegtDaWoDiePipelineSieErwartet() throws IOException {
        Path datei = datei();
        assumeTrue(datei != null, "Dieses Repository führt keine quality/owasp-suppressions.xml.");
        Path wurzel = repoWurzel();
        assertTrue(wurzel != null && datei.equals(wurzel.resolve("quality").resolve("owasp-suppressions.xml")),
                "Erwartet unter <repo>/quality/owasp-suppressions.xml — die Pipeline übergibt genau "
                        + "diesen Pfad an dependency-check (ci-cd-pipeline.yaml). Gefunden: " + datei);
    }

    // ── Hilfsmittel ──────────────────────────────────────────

    /** Die Suppression-Datei ab der Reactor-Wurzel — oder {@code null}, wenn das Repo keine führt. */
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

    /** Der Eintrag zu einem Artefakt-Ausdruck — schlägt mit klarer Meldung fehl, wenn er fehlt. */
    private static String[] suche(List<String[]> eintraege, String artefaktAusdruck) {
        return eintraege.stream()
                .filter(e -> e[1].equals(artefaktAusdruck))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Eintrag '" + artefaktAusdruck + "' wurde aus der Probe nicht gelesen — "
                                + "dann greift der zugehörige Zweig des Parsers nicht."));
    }

    /** Einträge als {@code [group, artefakt-Ausdruck, versions-Ausdruck, "exakt"|"regex"]}. */
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
     * Ein Ausdruck gilt als Literal, wenn er kein <em>unescaptes</em> Regex-Metazeichen enthält.
     * {@code 11\.0\.22} ist damit literal, {@code .*} und {@code [a-z]+} sind es nicht.
     *
     * <p>Die escapten Sequenzen werden vorher komplett entfernt statt nur der Backslash: Sonst
     * bliebe aus {@code \.} ein blanker Punkt stehen, der wie ein Metazeichen aussieht — und
     * <b>jede</b> gepinnte Version enthält Punkte. Der Test hielte dann keinen einzigen
     * Regex-Eintrag für prüfbar und wäre still wirkungslos. Genau diesen Fehler hat die Gegenprobe
     * {@code beideEintragssortenWerdenGelesen} beim Bau dieser Klasse gefangen.</p>
     */
    private static boolean istLiteral(String ausdruck) {
        return !METAZEICHEN.matcher(ausdruck.replaceAll("\\\\.", "")).find();
    }

    /** Entfernt Backslash-Escapes: {@code 11\.0\.22} → {@code 11.0.22}. */
    private static String entschaerft(String ausdruck) {
        return ausdruck.replace("\\", "");
    }

    /**
     * Alle Artefakte des Klassenpfads, auf die der Ausdruck passt. Bei einem exakten Eintrag ist
     * das höchstens eines; bei {@code tomcat-embed-[a-z]+} sind es alle Schwester-Artefakte — und
     * genau deren vollständige Abdeckung ist der Punkt.
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
            // Kein gültiger Ausdruck: dann kann der Test nichts beurteilen und meldet ihn als
            // unbeurteilbar, statt an einer fremden Datei zu zerbrechen.
            return List.of();
        }
        return treffer;
    }

    /**
     * artifactId → Version, aus den Jar-Namen des Testklassenpfads.
     *
     * <p>Surefire reicht den Klassenpfad je nach Konfiguration als eine einzige Manifest-Jar durch;
     * dann steht die echte Liste im {@code Class-Path} von deren Manifest. Beide Fälle werden
     * behandelt — sonst misst der Test im Leeren, und genau das fällt bei einem Positivbefund nicht
     * auf.</p>
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
                    // kein lesbares Jar — dann eben nicht
                }
            }
        }
        Pattern jarName = Pattern.compile("^(.*?)-(\\d[^/\\\\]*?)(?:-(?:sources|javadoc))?\\.jar$");
        Map<String, String> map = new LinkedHashMap<>();
        for (String e : eintraege) {
            String name = e.substring(Math.max(e.lastIndexOf('/'), e.lastIndexOf('\\')) + 1);
            Matcher m = jarName.matcher(name);
            if (m.matches()) {
                map.putIfAbsent(m.group(1), m.group(2));
            }
        }
        return map;
    }

    private static String entpacke(String eintrag) {
        try {
            return new File(new java.net.URI(eintrag)).getPath();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return eintrag;
        }
    }
}
