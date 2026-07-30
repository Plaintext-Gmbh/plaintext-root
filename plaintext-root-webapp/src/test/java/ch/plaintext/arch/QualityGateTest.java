/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Quality-Gate-Wächter der wöchentlichen Voll-Analyse.
 *
 * <p>Die Weekly-Pipeline (Sonar + OWASP-CVE) schreibt bei Schwellenüberschreitung
 * {@code quality/quality-gate.properties} (status=BREACHED) ins Repo und schickt Pushover.
 * Dieser Test liest das File und <b>schlägt mit dem Inhalt als Meldung fehl</b> — sichtbar in
 * nightly-, PR- und lokalen Builds, sodass der Handlungsbedarf hart auffällt.
 *
 * <p>Er trägt {@code @Tag("quality-gate")}: der Deploy-Build läuft mit
 * {@code -DexcludedGroups=quality-gate} und überspringt ihn, damit ein Hotfix trotz aktivem
 * Alert-File rausgehen kann. Nach dem Fix setzt der nächste Weekly-Lauf wieder {@code status=OK}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Tag("quality-gate")
class QualityGateTest {

    @Test
    void qualityGateNichtVerletzt() throws IOException {
        Path file = findGateFile();
        if (file == null) {
            return; // kein Statusfile -> nichts zu prüfen (grün)
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
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

    /** Sucht quality/quality-gate.properties ab dem Arbeitsverzeichnis nach oben (Modul → Repo-Wurzel). */
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
