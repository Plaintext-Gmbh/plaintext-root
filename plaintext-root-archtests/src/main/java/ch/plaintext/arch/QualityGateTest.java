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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Quality-Gate-Waechter der woechentlichen Voll-Analyse — EINE Fassung fuer alle sechs Repos.
 *
 * <p>Die Weekly-Pipeline (Sonar + OWASP-CVE, {@code .woodpecker/analyse.yml} bzw. {@code sonar.yml})
 * bewertet ihr Ergebnis und committet {@code quality/quality-gate.properties} ins Repo zurueck.
 * Dieser Test liest die Datei und <b>schlaegt mit ihrem Inhalt als Meldung fehl</b>, wenn dort
 * {@code status=BREACHED} steht oder wenn die Bewertung <b>zu alt</b> ist.
 *
 * <p><b>Karte 1298: warum er hier liegt und nicht mehr sechsmal kopiert.</b> Bis zum 22.09.2026 trug
 * jedes Repo seine eigene Kopie, und die Kopien liefen nachweislich auseinander: fwtool hatte bis
 * Karte 1268 83 Zeilen mit EINER Testmethode ohne Alterspruefung und ohne Positivkontrolle, root
 * bis Karte 1293 keine Positivkontrolle (die Bewertung stand im Testrumpf und war nicht aufrufbar),
 * waehrend app/guild/schuetu/iot 218–223 Zeilen mit beidem trugen. Jede Verbesserung musste sechsmal
 * von Hand nachgezogen werden, und zweimal unterblieb es. Die Consumer fuehren diese Klasse ueber
 * Surefire {@code <dependenciesToScan>} aus; ihre Kopien sind geloescht. Inhaltlich ist dies die
 * staerkste der sechs Fassungen (app/fwtool): Alterspruefung, Positivkontrolle mit fuenf Faellen,
 * Anzeigenamen an beiden Methoden.
 *
 * <p><b>Karte 1183: die Alterspruefung.</b> Ein veraltetes {@code BREACHED} heilt beim naechsten
 * Wochenlauf von selbst — ein veraltetes {@code OK} heilt <b>nie</b>. Es bleibt gruen, auch wenn
 * die Analyse seit Wochen nicht mehr durchlief. Gemessen am 08.09.2026: iot meldete
 * {@code status=OK} bei {@code cve.high.count=0} und trug dieselben sieben CVE-Funde wie schuetu —
 * sein „0" war zehn Tage alt (Karte 1140). Am 11.09.2026 stand fwtools Datei eine volle Woche still.
 *
 * <p><b>Wo er laeuft und wo nicht.</b> Er traegt {@code @Tag("quality-gate")}. Die Bauten, die den
 * PR-Code bewerten oder ausliefern, laufen mit {@code -DexcludedGroups=quality-gate} und
 * ueberspringen ihn — ein Hotfix muss trotz rotem Gate rausgehen koennen, und der Waechter bewertet
 * ohnehin nicht den PR-Code, sondern den statischen Wochenstand. Welche Pipeline den Tag
 * ausschliesst, steht in den Pipelines der Repos, nicht hier (Karte 1309). Der Tag darf deshalb
 * <b>nicht</b> umbenannt werden: die Pipelines nennen ihn woertlich.
 *
 * <p><b>Ohne Gate-Datei gruen.</b> Ein Repo ohne {@code quality/quality-gate.properties} hat keine
 * Voll-Analyse, die etwas behaupten koennte — dann gibt es nichts zu pruefen.
 *
 * <p><b>Warum zwei Testmethoden.</b> Ein Waechter, der nur deshalb gruen ist, weil die Repo-Datei
 * gerade {@code status=OK} traegt, ist von einem, der gar nichts prueft, nicht zu unterscheiden.
 * {@link #positivkontrolleWaechterWirdBeiVerletztemGateRot()} speist die roten Faelle fest ein.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Tag("quality-gate")
class QualityGateTest {

    /**
     * Sieben Tage Sollfrist, drei Tage Kulanz — siehe die Begruendung an der Alterspruefung in
     * {@link #befund(Properties, LocalDateTime)}.
     */
    static final long MAX_ALTER_TAGE = 10;

    @Test
    @DisplayName("quality/quality-gate.properties steht auf OK und ist nicht veraltet")
    void qualityGateNichtVerletzt() throws IOException {
        Path file = findGateFile();
        if (file == null) {
            return; // kein Statusfile -> nichts zu pruefen (gruen)
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
     * Positivkontrolle (Karten 1183, 1293). Sie speist die roten Faelle direkt in die Bewertung und
     * verlangt einen Befund — unabhaengig davon, was gerade in der Repo-Datei steht.
     */
    @Test
    @DisplayName("Positivkontrolle: verletztes und veraltetes Gate werden ROT, frisches OK bleibt gruen")
    void positivkontrolleWaechterWirdBeiVerletztemGateRot() {
        LocalDateTime jetzt = LocalDateTime.parse("2026-09-11T12:00:00");

        // 1. Frisches OK -> gruen. (Gegenprobe: ohne sie beweist ein Befund unten nichts,
        //    weil eine Bewertung, die IMMER rot ist, ebenfalls "bissig" aussaehe.)
        assertNull(befund(props("OK", "2026-09-10T04:08:32", "0"), jetzt),
                "Ein frisches status=OK darf keinen Befund erzeugen.");

        // 2. Verletztes Gate -> rot, mit den Einzelbefunden in der Meldung.
        Properties verletzt = props("BREACHED", "2026-09-10T04:08:32", "2");
        verletzt.setProperty("breach.1", "OWASP: 7 Abhaengigkeit(en) mit CVSS>=7.0");
        verletzt.setProperty("breach.2", "SonarQube Quality Gate = ERROR in NEUEM Code");
        String rot = befund(verletzt, jetzt);
        assertNotNull(rot, "status=BREACHED MUSS einen Befund erzeugen — sonst prueft der Waechter nichts.");
        assertTrue(rot.contains("QUALITY-GATE VERLETZT"), "Meldung nennt den Anlass nicht: " + rot);
        assertTrue(rot.contains("OWASP: 7 Abhaengigkeit(en) mit CVSS>=7.0"),
                "breach.1 fehlt in der Meldung: " + rot);
        assertTrue(rot.contains("SonarQube Quality Gate = ERROR in NEUEM Code"),
                "breach.2 fehlt in der Meldung: " + rot);

        // 3. Veraltetes OK -> rot. Ein OK von vor mehr als MAX_ALTER_TAGE Tagen ist keine
        //    Aussage ueber heute (Karte 1140).
        String veraltet = befund(props("OK", "2026-08-20T04:08:32", "0"), jetzt);
        assertNotNull(veraltet, "Ein " + MAX_ALTER_TAGE + " Tage altes status=OK muss auffallen.");
        assertTrue(veraltet.contains("VERALTET"), "Meldung nennt das Alter nicht: " + veraltet);

        // 4. Unlesbarer Zeitstempel -> rot. Dann sagt das Gate nicht, wie alt seine Aussage ist.
        assertNotNull(befund(props("OK", "gestern", "0"), jetzt),
                "Ein unlesbares checked= muss auffallen.");

        // 5. Genau an der Grenze (10 Tage) noch gruen — die Zahl ist eine Aussage, kein Zufall:
        //    ein ausgefallener Wochenlauf ist verzeihlich, der zweite nicht.
        assertNull(befund(props("OK", "2026-09-01T12:00:00", "0"), jetzt),
                "Bei genau " + MAX_ALTER_TAGE + " Tagen ist die Kulanz noch nicht aufgebraucht.");
    }

    private static Properties props(String status, String checked, String breachCount) {
        Properties p = new Properties();
        p.setProperty("status", status);
        p.setProperty("checked", checked);
        p.setProperty("breach.count", breachCount);
        p.setProperty("sonar.url", "https://sonarqube.plaintext.ch/dashboard?id=ch.plaintext:beispiel-parent");
        p.setProperty("dashboard.url", "https://plaintext-gmbh.github.io/plaintext-scripts/");
        return p;
    }

    /**
     * Bewertet den Inhalt von {@code quality/quality-gate.properties}. Bewusst ohne Dateizugriff und
     * ohne {@code fail()} — nur so laesst sie sich mit erfundenen Werten pruefen (Positivkontrolle).
     *
     * @return die Fehlermeldung, oder {@code null}, wenn nichts zu beanstanden ist.
     */
    static String befund(Properties p, LocalDateTime jetzt) {
        // ── Ein `status=OK` muss sagen, WIE ALT es ist (Karte 1140 -> 1178 -> 1183) ───────────
        // Der Zeitstempel stand seit dem 03.07.2026 im File — er wurde nur nirgends AUSGEWERTET.
        // Ein OK, das zwei Wochen alt sein kann, ohne sein Alter zu melden, ist schlimmer als kein
        // Gate: es beendet das Nachdenken.
        //
        // ZEHN TAGE, nicht acht: Die Voll-Analyse laeuft je Repo EINMAL pro Woche an einem festen
        // Wochentag (Stand 22.09.2026: app/guild Montag, root/schuetu Dienstag, iot Mittwoch, fwtool
        // Freitag — je an der Woodpecker-API nachmessen, `GET /api/repos/<id>/cron`, nicht aus einem
        // Nachbar-Repo uebernehmen). Sieben Tage waeren die Sollfrist; zehn lassen EINEN
        // ausgefallenen Lauf zu, ohne rot zu werden — der zweite faellt auf. Wer die Zahl aendert,
        // aendert damit die Aussage „ein Ausfall ist verzeihlich, zwei nicht". Wer das Gate veraltet
        // findet, prueft ZUERST, ob der Cron laeuft (fwtools stand vom 11. bis 19.09.2026 auf
        // enabled=false, Karte 1268).
        String geprueft = p.getProperty("checked", "").trim();
        if (!geprueft.isEmpty()) {
            LocalDateTime wann;
            try {
                wann = LocalDateTime.parse(geprueft);
            } catch (DateTimeParseException _) {
                return "Der Zeitstempel `checked=" + geprueft + "` ist nicht lesbar — dann sagt das "
                        + "Gate nicht, wie alt seine Aussage ist, und ein OK darin ist wertlos.";
            }
            long tage = Duration.between(wann, jetzt).toDays();
            if (tage > MAX_ALTER_TAGE) {
                return "\n\n=== QUALITY-GATE-MESSWERT IST VERALTET ===\n"
                        + "Das File sagt status=" + p.getProperty("status", "?")
                        + ", aber gemessen wurde am " + geprueft + " — vor " + tage + " Tagen.\n\n"
                        + "Ein OK von vor " + tage + " Tagen ist keine Aussage ueber heute: Am 08.09.2026\n"
                        + "meldete iot OK und trug vier CVEs mit CVSS 9.8, weil sein Messwert zehn Tage\n"
                        + "alt war (Karte 1140).\n\n"
                        + "Zu tun: die Voll-Analyse laufen lassen — von Hand mit der Variable\n"
                        + "`analyse = voll` im Run-Dialog. OHNE diese Variable meldet der Lauf gruen,\n"
                        + "ohne zu messen (.woodpecker/analyse-freigabe.sh).\n"
                        + "Faellt der Cron regelmaessig aus, ist DAS der Befund, nicht dieser Test.\n";
            }
        }

        if (!"BREACHED".equalsIgnoreCase(p.getProperty("status", "OK").trim())) {
            return null; // OK
        }

        StringBuilder msg = new StringBuilder("\n\n=== QUALITY-GATE VERLETZT (woechentliche Voll-Analyse) ===\n");
        msg.append("Geprueft: ").append(p.getProperty("checked", "?")).append("\n\n");
        int n = parseInt(p.getProperty("breach.count", "0"));
        for (int i = 1; i <= n; i++) {
            String b = p.getProperty("breach." + i);
            if (b != null && !b.isBlank()) {
                msg.append("  ! ").append(b).append("\n");
            }
        }
        msg.append("\nSonar:     ").append(p.getProperty("sonar.url", "")).append("\n");
        msg.append("Dashboard: ").append(p.getProperty("dashboard.url", "")).append("\n");
        msg.append("\nNach dem Fix setzt der naechste woechentliche Voll-Lauf das File wieder auf OK.\n")
           .append("Deploys sind NICHT blockiert (dieser Test laeuft im Deploy-Build nicht mit).\n");
        return msg.toString();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /** Sucht quality/quality-gate.properties ab dem Arbeitsverzeichnis nach oben (Modul -> Repo-Wurzel). */
    private static Path findGateFile() {
        Path dir = ReactorLayout.start();
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
