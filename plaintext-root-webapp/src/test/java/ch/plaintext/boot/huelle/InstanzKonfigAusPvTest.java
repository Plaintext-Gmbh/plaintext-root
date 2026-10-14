/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.huelle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1512: Die Huelle liest ihre Instanz-Konfig aus dem PV ({@code spring.config.import} in der
 * application.yml von root-webapp). Laeuft gegen die echte application.yml aus target/classes, ohne
 * Auto-Konfiguration (keine DB, kein Web) - geprueft wird nur, was im Environment ankommt.
 */
class InstanzKonfigAusPvTest {

    @Configuration(proxyBeanMethods = false)
    static class Leer {
    }

    private static ConfigurableApplicationContext starte(Path verzeichnis) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Leer.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false);
        if (verzeichnis != null) {
            builder.properties("PLAINTEXT_KONFIG_VERZEICHNIS=" + verzeichnis);
        }
        return builder.run();
    }

    @Test
    void standardOrtIstDataConfig() {
        try (ConfigurableApplicationContext ctx = starte(null)) {
            assertThat(ctx.getEnvironment().getProperty("spring.config.import[1]"))
                    .isEqualTo("optional:file:/data/config/");
        }
    }

    @Test
    void ohnePvVerzeichnisGiltDieKlassenpfadKonfig(@TempDir Path tmp) {
        try (ConfigurableApplicationContext ctx = starte(tmp.resolve("gibt-es-nicht"))) {
            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.application.name")).isEqualTo("plaintext-root");
            assertThat(env.getProperty("spring.ai.mcp.server.name")).isEqualTo("plaintext-root");
            assertThat(env.getProperty("plaintext.huelle.probe")).isNull();
        }
    }

    @Test
    void pvKonfigWirdGelesenUndUeberschreibtDenKlassenpfad(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("application.yml"), """
                spring:
                  ai:
                    mcp:
                      server:
                        name: instanz-aus-pv
                plaintext:
                  huelle:
                    probe: aus-dem-pv
                """);
        try (ConfigurableApplicationContext ctx = starte(tmp)) {
            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("plaintext.huelle.probe")).isEqualTo("aus-dem-pv");
            assertThat(env.getProperty("spring.ai.mcp.server.name")).isEqualTo("instanz-aus-pv");
            // Nicht ueberschriebene Werte bleiben aus der Klassenpfad-Konfig.
            assertThat(env.getProperty("spring.application.name")).isEqualTo("plaintext-root");
        }
    }

    @Test
    void kompatibelAbIstGesetztUndNichtNeuerAlsDieVersion() {
        try (ConfigurableApplicationContext ctx = starte(null)) {
            Environment env = ctx.getEnvironment();
            String ab = env.getProperty("plaintext.root.kompatibel-ab");
            String version = env.getProperty("plaintext.root.version").replace("-SNAPSHOT", "");
            assertThat(ab).matches("\\d+\\.\\d+\\.\\d+");
            assertThat(vergleiche(ab, version)).isLessThanOrEqualTo(0);
        }
    }

    private static int vergleiche(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
