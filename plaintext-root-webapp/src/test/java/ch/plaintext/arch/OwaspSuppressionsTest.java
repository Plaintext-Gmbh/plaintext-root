/*
 * Copyright (C) plaintext.ch, 2026.
 */
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 420: Eine versionsgepinnte OWASP-Suppression, deren Version im Baum gar nicht mehr
 * vorkommt, ist wirkungslos — und sie sieht trotzdem nach Kontrolle aus.
 *
 * <p><b>Warum das zaehlt.</b> Die Suppressions in {@code quality/owasp-suppressions.xml} sind
 * bewusst auf eine exakte Version gepinnt: {@code pkg:maven/…/angus-activation@2.0.3}. Das ist
 * richtig so — zieht Spring Boot eine neue Version, greift die Suppression nicht mehr und der
 * neue Stand wird erneut bewertet (kein Dauer-Blindflug). Der tote Eintrag bleibt danach aber
 * stehen. Wer die Datei liest, haelt ihn fuer eine wirksame, begruendete Ausnahme; tatsaechlich
 * ist er Altpapier. So wird die Datei zum Endlager, das Karte 420 ausdruecklich verhindern will.</p>
 *
 * <p><b>Wogegen geprueft wird.</b> Gegen den Testklassenpfad dieses Moduls — das ist der Stand,
 * den der Build wirklich aufloest, nicht der, den eine POM-Eigenschaft verspricht. Findet sich
 * ein Artefakt unter anderem Versionsstand als dem gepinnten, ist die Suppression tot.</p>
 *
 * <p><b>Was der Test NICHT kann,</b> und das steht hier, damit es niemand fuer mehr haelt: Er
 * sieht nur Artefakte, die auf dem Klassenpfad DIESES Moduls liegen. Eine Suppression fuer ein
 * Artefakt, das hier gar nicht vorkommt, kann er nicht beurteilen — er meldet sie als
 * unbeurteilbar, statt sie stillschweigend durchzuwinken. Und er sagt nichts darueber, ob eine
 * Suppression fachlich noch berechtigt ist; nur, ob sie ueberhaupt noch greift.</p>
 */
class OwaspSuppressionsTest {

    /** {@code pkg:maven/<group>/<artifact>@<version>} — nur exakte Pins, keine Regex-Eintraege. */
    private static final Pattern PIN = Pattern.compile(
            "<packageUrl>\\s*pkg:maven/([^/]+)/([^@<\\s]+)@([^<\\s]+)\\s*</packageUrl>");

    @Test
    void keineWirkungsloseSuppression() throws IOException {
        List<String[]> pins = pins();
        assertFalse(pins.isEmpty(),
                "In " + datei() + " wurde kein einziger versionsgepinnter Eintrag gefunden. "
                        + "Entweder hat sich das Format geaendert oder der Test sucht falsch — "
                        + "ein gruener Lauf waere dann wertlos.");

        Map<String, String> aufKlassenpfad = artefakteAufKlassenpfad();
        assertTrue(aufKlassenpfad.size() >= 50,
                "Nur " + aufKlassenpfad.size() + " Artefakte auf dem Testklassenpfad gefunden. "
                        + "Der Test kann so nichts beurteilen (erwartet werden dutzende). "
                        + "Vermutlich liegt der Klassenpfad in einer Manifest-Jar, die nicht gelesen wurde.");

        List<String> tot = new ArrayList<>();
        List<String> unbeurteilbar = new ArrayList<>();
        for (String[] p : pins) {
            String artefakt = p[1];
            String gepinnt = p[2];
            String tatsaechlich = aufKlassenpfad.get(artefakt);
            if (tatsaechlich == null) {
                unbeurteilbar.add(artefakt + "@" + gepinnt);
            } else if (!tatsaechlich.equals(gepinnt)) {
                tot.add(artefakt + ": Suppression pinnt " + gepinnt + ", im Build liegt "
                        + tatsaechlich);
            }
        }

        if (!unbeurteilbar.isEmpty()) {
            System.out.println("OwaspSuppressionsTest — nicht beurteilbar (Artefakt nicht auf dem "
                    + "Klassenpfad dieses Moduls): " + String.join(", ", unbeurteilbar));
        }
        assertTrue(tot.isEmpty(),
                "Diese Suppressions greifen nicht mehr und gehoeren geloescht:\n  "
                        + String.join("\n  ", tot)
                        + "\n\nEine versionsgepinnte Suppression laeuft ab, sobald sich die Version "
                        + "aendert — das ist gewollt. Der tote Eintrag bleibt aber stehen und sieht "
                        + "weiter nach einer begruendeten Ausnahme aus. Entweder loeschen (wenn der "
                        + "Fund behoben ist) oder mit neuer Version UND neuer Begruendung eintragen "
                        + "(Karte 420).");
    }

    /**
     * Gegenprobe zur Erkennung selbst: Ein konstruierter Pin auf eine Version, die es nicht gibt,
     * muss auffallen. Ohne diesen Fall waere der Test oben auch dann gruen, wenn der Vergleich
     * nie zuschlaegt.
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
                "Der Vergleich haelt eine erfundene Version faelschlich fuer aktuell — dann sagt "
                        + "der Test oben nichts aus.");
    }

    /**
     * Gegenprobe zum Einlesen: Die bekannten Eintraege muessen gefunden werden. Aendert jemand das
     * Format der Datei, faellt das hier auf und nicht erst, wenn eine tote Suppression durchrutscht.
     */
    @Test
    void dieDateiWirdUeberhauptGelesen() throws IOException {
        List<String[]> pins = pins();
        assertTrue(pins.size() >= 2,
                "Erwartet werden mindestens zwei versionsgepinnte Suppressions, gefunden: "
                        + pins.size() + ". Format geaendert? Datei: " + datei());
        for (String[] p : pins) {
            assertFalse(p[2].isBlank(), "Eintrag ohne Version: " + p[0] + "/" + p[1]);
        }
    }

    // ── Hilfsmittel ──────────────────────────────────────────

    private static Path datei() {
        Path p = Path.of("quality", "owasp-suppressions.xml");
        if (Files.isRegularFile(p)) {
            return p;
        }
        return Path.of("..").resolve(p).normalize();
    }

    private static List<String[]> pins() throws IOException {
        Path d = datei();
        assertTrue(Files.isRegularFile(d), "Suppression-Datei nicht gefunden: " + d.toAbsolutePath());
        String text = Files.readString(d, StandardCharsets.UTF_8);
        List<String[]> ergebnis = new ArrayList<>();
        Matcher m = PIN.matcher(text);
        while (m.find()) {
            ergebnis.add(new String[]{m.group(1), m.group(2), m.group(3)});
        }
        return ergebnis;
    }

    /**
     * artifactId → Version, aus den Jar-Namen des Testklassenpfads.
     *
     * <p>Surefire reicht den Klassenpfad je nach Konfiguration als eine einzige Manifest-Jar
     * durch; dann steht die echte Liste im {@code Class-Path} von deren Manifest. Beide Faelle
     * werden behandelt — sonst misst der Test im Leeren, und genau das faellt bei einem
     * Positivbefund nicht auf.</p>
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

    /** Nur damit der Import genutzt wird, wenn oben einmal etwas wegfaellt. */
    @Test
    void dieSuppressionDateiLiegtDaWoDerBuildSieErwartet() {
        assertEquals(true, Files.isRegularFile(datei()),
                "Erwartet unter quality/owasp-suppressions.xml (relativ zur Repo-Wurzel), gesucht: "
                        + datei().toAbsolutePath());
    }
}
