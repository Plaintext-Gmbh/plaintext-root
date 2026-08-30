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
