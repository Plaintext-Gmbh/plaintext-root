/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Quality gate guard of the weekly full analysis.
 *
 * <p>When a threshold is exceeded, the weekly pipeline (Sonar + OWASP CVE) writes
 * {@code quality/quality-gate.properties} (status=BREACHED) into the repository and sends a Pushover
 * notification. This test reads that file and <b>fails with its content as the message</b> — visible in
 * nightly, PR and local builds, so that the need for action stands out unmistakably.
 *
 * <p>It carries {@code @Tag("quality-gate")}: the deploy build runs with
 * {@code -DexcludedGroups=quality-gate} and skips it, so that a hotfix can go out despite an active
 * alert file. After the fix the next weekly run sets {@code status=OK} again.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Tag("quality-gate")
class QualityGateTest {

    /** Siehe die Begruendung an der Pruefung unten: sieben Tage Sollfrist, drei Tage Kulanz. */
    static final long MAX_ALTER_TAGE = 10;


    @Test
    void qualityGateNichtVerletzt() throws IOException {
        Path file = findGateFile();
        if (file == null) {
            return; // no status file -> nothing to check (green)
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String befund = befund(p, LocalDateTime.now());
        if (befund != null) {
            fail(befund);
        }
    }

    /**
     * Positivkontrolle (Karte 1293). Sie speist die roten Faelle direkt in die Bewertung, statt
     * sich auf die Datei im Repo zu verlassen — deren Inhalt wechselt woechentlich.
     *
     * <p>Warum root sie bis zum 20.09.2026 als einziges der sechs Repos nicht hatte: die
     * Bewertung stand vollstaendig im Testrumpf und rief {@code fail()} selbst auf. Sie war
     * damit nicht aufrufbar, ohne den ganzen Test zu fahren, und nicht pruefbar, ohne die echte
     * Datei zu veraendern. Erst die Aufteilung in {@link #befund(Properties, LocalDateTime)}
     * macht sie messbar; das ist der eigentliche Inhalt dieser Aenderung.</p>
     *
     * <p>Der erste Fall ist der wichtigste: eine Bewertung, die IMMER rot ist, saehe in allen
     * uebrigen Faellen genauso bissig aus.</p>
     */
    @Test
    @DisplayName("Positivkontrolle: verletztes und veraltetes Gate werden ROT, frisches OK bleibt gruen")
    void positivkontrolleWaechterWirdBeiVerletztemGateRot() {
        LocalDateTime jetzt = LocalDateTime.parse("2026-09-20T12:00:00");

        // 1. Frisches OK -> gruen. Die Gegenprobe: ohne sie belegt kein roter Fall unten etwas.
        assertNull(befund(props("OK", "2026-09-19T04:08:32", "0"), jetzt),
                "Ein frisches status=OK darf keinen Befund erzeugen.");

        // 2. Verletztes Gate -> rot, und die Einzelbefunde stehen in der Meldung. Ein Waechter,
        //    der nur \"rot\" sagt, zwingt zum Nachschlagen an anderer Stelle.
        Properties verletzt = props("BREACHED", "2026-09-19T04:08:32", "2");
        verletzt.setProperty("breach.1", "OWASP: 7 Abhaengigkeit(en) mit CVSS>=7.0");
        verletzt.setProperty("breach.2", "SonarQube Quality Gate = ERROR in NEUEM Code");
        String rot = befund(verletzt, jetzt);
        assertNotNull(rot, "status=BREACHED MUSS einen Befund erzeugen — sonst prueft der Waechter nichts.");
        assertTrue(rot.contains("QUALITY-GATE VERLETZT"), "Meldung nennt den Anlass nicht: " + rot);
        assertTrue(rot.contains("OWASP: 7 Abhaengigkeit(en) mit CVSS>=7.0"),
                "breach.1 fehlt in der Meldung: " + rot);
        assertTrue(rot.contains("SonarQube Quality Gate = ERROR in NEUEM Code"),
                "breach.2 fehlt in der Meldung: " + rot);

        // 3. Veraltetes OK -> rot. Genau der Fall aus Karte 1140: iot meldete OK und trug dabei
        //    vier CVEs mit CVSS 9.8, weil sein Messwert zehn Tage alt war.
        String veraltet = befund(props("OK", "2026-08-20T04:08:32", "0"), jetzt);
        assertNotNull(veraltet, "Ein " + MAX_ALTER_TAGE + " Tage altes status=OK muss auffallen.");
        assertTrue(veraltet.contains("VERALTET"), "Meldung nennt das Alter nicht: " + veraltet);

        // 4. Unlesbarer Zeitstempel -> rot. Dann sagt das Gate nicht, wie alt seine Aussage ist.
        assertNotNull(befund(props("OK", "gestern", "0"), jetzt),
                "Ein unlesbares checked= muss auffallen.");

        // 5. Genau an der Grenze noch gruen — die Zahl ist eine Aussage, kein Zufall: ein
        //    ausgefallener Wochenlauf ist verzeihlich, der zweite nicht.
        assertNull(befund(props("OK", "2026-09-10T12:00:00", "0"), jetzt),
                "Bei genau " + MAX_ALTER_TAGE + " Tagen ist die Kulanz noch nicht aufgebraucht.");
    }

    private static Properties props(String status, String checked, String breachCount) {
        Properties p = new Properties();
        p.setProperty("status", status);
        p.setProperty("checked", checked);
        p.setProperty("breach.count", breachCount);
        return p;
    }

    /**
     * Bewertet den Inhalt der Gate-Datei und gibt die Meldung zurueck, oder {@code null}, wenn
     * nichts zu melden ist. Bewusst ohne Dateizugriff und ohne {@code fail()} — nur so laesst
     * sie sich mit erfundenen Werten pruefen (siehe Positivkontrolle oben).
     */
    static String befund(Properties p, LocalDateTime jetzt) {
        // ── KARTE 1140: ein `status=OK` muss sagen, WIE ALT es ist ────────────────────────────
        // Am 08.09.2026 gemessen, alle fuenf Repos:
        //
        //   iot      status=OK        checked=2026-08-29   cve.high.count=0   <- bei VIER 9.8er-CVEs
        //   app      status=BREACHED  checked=2026-08-24   cve.high.count=0   <- wegen Sonar, nicht CVE
        //   guild    status=BREACHED  checked=2026-08-24   cve.high.count=0
        //   root     status=BREACHED  checked=2026-08-25   cve.high.count=0
        //   schuetu  status=BREACHED  checked=2026-09-08   cve.high.count=7   <- der einzige frische Wert
        //
        // iot meldete OK, waehrend es dieselben sieben Funde trug wie schuetu — sein „0" war zehn
        // Tage alt, also von VOR der NVD-Charge, die die Funde erst brachte. Kein Repo wurde rot,
        // obwohl alle betroffen waren.
        //
        // Der Zeitstempel stand die ganze Zeit im File. Er wurde nur nirgends AUSGEWERTET — dieser
        // Test hat ihn bloss in die Fehlermeldung geschrieben. Ein `status=OK`, das zwei Wochen alt
        // sein kann, ohne sein Alter zu melden, ist schlimmer als kein Gate: Es beendet das
        // Nachdenken.
        //
        // ZEHN TAGE, nicht acht: Die Voll-Analyse laeuft wochentlich je Repo an einem festen Tag
        // (app/guild Mo, root/schuetu Di, iot Mi). Sieben Tage waeren die Sollfrist; zehn lassen
        // EINEN ausgefallenen Lauf zu, ohne rot zu werden — der zweite faellt auf. Wer die Zahl
        // aendert, aendert damit die Aussage „ein Ausfall ist verzeihlich, zwei nicht".
        String geprueft = p.getProperty("checked", "").trim();
        if (!geprueft.isEmpty()) {
            try {
                LocalDateTime wann = LocalDateTime.parse(geprueft);
                long tage = Duration.between(wann, jetzt).toDays();
                if (tage > MAX_ALTER_TAGE) {
                    return ("\n\n=== QUALITY-GATE-MESSWERT IST VERALTET ===\n"
                            + "Das File sagt status=" + p.getProperty("status", "?")
                            + ", aber gemessen wurde am " + geprueft + " — vor " + tage + " Tagen.\n\n"
                            + "Ein OK von vor " + tage + " Tagen ist keine Aussage ueber heute: Am 08.09.2026\n"
                            + "meldete iot OK und trug vier CVEs mit CVSS 9.8, weil sein Messwert zehn Tage\n"
                            + "alt war (Karte 1140).\n\n"
                            + "Zu tun: die Voll-Analyse laufen lassen — von Hand mit der Variable\n"
                            + "`analyse = voll` im Run-Dialog. OHNE diese Variable meldet der Lauf gruen,\n"
                            + "ohne zu messen (.woodpecker/analyse-freigabe.sh).\n"
                            + "Faellt der Cron regelmaessig aus, ist DAS der Befund, nicht dieser Test.\n");
                }
            } catch (DateTimeParseException _) {
                return "Der Zeitstempel `checked=" + geprueft + "` ist nicht lesbar — dann sagt das "
                        + "Gate nicht, wie alt seine Aussage ist, und ein OK darin ist wertlos.";
            }
        }

        if (!"BREACHED".equalsIgnoreCase(p.getProperty("status", "OK").trim())) {
            return null; // OK
        }

        StringBuilder msg = new StringBuilder("\n\n=== QUALITY-GATE VERLETZT (wöchentliche Voll-Analyse) ===\n");
        msg.append("Geprüft: ").append(p.getProperty("checked", "?")).append("\n\n");
        int n = parseInt(p.getProperty("breach.count", "0"));
        for (int i = 1; i <= n; i++) {
            String b = p.getProperty("breach." + i);
            if (b != null && !b.isBlank()) {
                msg.append("  ! ").append(b).append("\n");
            }
        }
        msg.append("\nSonar:     ").append(p.getProperty("sonar.url", "")).append("\n");
        msg.append("Dashboard: ").append(p.getProperty("dashboard.url", "")).append("\n");
        msg.append("\nNach dem Fix setzt der nächste wöchentliche Voll-Lauf das File wieder auf OK.\n")
           .append("Deploys sind NICHT blockiert (dieser Test läuft im Deploy-Build nicht mit).\n");
        return msg.toString();
    }


    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /** Looks for quality/quality-gate.properties upwards from the working directory (module → repository root). */
    private Path findGateFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path f = dir.resolve("quality").resolve("quality-gate.properties");
            if (Files.isRegularFile(f)) {
                return f;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
