/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    private static final long MAX_ALTER_TAGE = 10;


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
                long tage = java.time.Duration.between(wann, LocalDateTime.now()).toDays();
                if (tage > MAX_ALTER_TAGE) {
                    fail("\n\n=== QUALITY-GATE-MESSWERT IST VERALTET ===\n"
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
            } catch (java.time.format.DateTimeParseException e) {
                fail("Der Zeitstempel `checked=" + geprueft + "` ist nicht lesbar — dann sagt das "
                        + "Gate nicht, wie alt seine Aussage ist, und ein OK darin ist wertlos.");
            }
        }

        if (!"BREACHED".equalsIgnoreCase(p.getProperty("status", "OK").trim())) {
            return; // OK
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
        fail(msg.toString());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
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
