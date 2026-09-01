/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Karte 995: {@code bao:}-Referenzen durch die ECHTE Boot-Verdrahtung, nicht durch einen direkt
 * gebauten Resolver.
 *
 * <p><b>Warum es diesen Test gibt.</b> {@link OpenBaoReferenzTest} baut den Resolver selbst und
 * reicht ihm den OpenBao-Client als Konstruktor-Argument hinein. Damit prueft er den Resolver —
 * aber nicht die Frage, ob im Betrieb ueberhaupt jemand einen Client hineinreicht. Genau dort lag
 * der Fehler: {@link VaultwardenEnvironmentPostProcessor} baute den Resolver fuer die
 * <em>Eager</em>-Aufloesung mit der Ein-Argument-Fassung, deren bao-Supplier {@code () -> null}
 * ist. Und die Eager-Aufloesung ist die, die im Betrieb wirkt — die traege
 * {@link VaultwardenPropertySource} wird von Spring Boots eigener
 * {@code configurationProperties}-Source umgangen.</p>
 *
 * <p>Gemessen am 01.09.2026 an guild-INT: jede {@code bao:}-Referenz scheiterte beim Start mit
 * „OpenBao ist nicht konfiguriert", obwohl alle vier {@code PLAINTEXT_BAO_*}-Variablen im Container
 * standen und die Token-Datei durch uid 1000 lesbar war.</p>
 *
 * <p><b>Der Test bildet den Boot-Aufbau nach</b> — {@code ConfigurationPropertySources.attach} und
 * eine {@link SystemEnvironmentPropertySource} statt einer nackten Map. Ohne das prueft er eine
 * Welt, die es im Betrieb nicht gibt (dieselbe Lehre wie PR 86 / Karte 868).</p>
 */
@DisplayName("bao: durch die echte Boot-Verdrahtung")
class OpenBaoBootVerdrahtungTest {

    private HttpServer server;
    private String basisUrl;
    private Path tokenDatei;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "{\"data\":{\"data\":{\"value\":\"aufgeloest\"}}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        basisUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        tokenDatei = Files.createTempFile("bao-token", ".txt");
        Files.writeString(tokenDatei, "test-token");
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(0);
        Files.deleteIfExists(tokenDatei);
    }

    /**
     * Baut eine Umgebung wie im Boot: Werte in einer {@code SystemEnvironmentPropertySource}
     * (die bildet {@code plaintext.bao.token-file} auf {@code PLAINTEXT_BAO_TOKEN_FILE} ab —
     * eine gewoehnliche MapPropertySource tut das NICHT).
     */
    private StandardEnvironment umgebung() {
        Map<String, Object> env = new HashMap<>();
        env.put("PLAINTEXT_BAO_ENABLED", "true");
        env.put("PLAINTEXT_BAO_URL", basisUrl);
        env.put("PLAINTEXT_BAO_MOUNT", "secret");
        env.put("PLAINTEXT_BAO_TOKEN_FILE", tokenDatei.toString());
        env.put("PLAINTEXT_EVENTS_PRINTER_TOKEN", "bao:guild-printer-int");

        StandardEnvironment umgebung = new StandardEnvironment();
        umgebung.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        ConfigurationPropertySources.attach(umgebung);
        return umgebung;
    }

    @Test
    @DisplayName("eine bao:-Referenz wird beim Start wirklich aufgeloest")
    void baoWirdBeimStartAufgeloest() {
        StandardEnvironment umgebung = umgebung();

        new VaultwardenEnvironmentPostProcessor().postProcessEnvironment(umgebung, null);

        String wert = umgebung.getProperty("PLAINTEXT_EVENTS_PRINTER_TOKEN");
        // Der Fehler, den dieser Test faengt: hier stand vorher die unaufgeloeste Referenz
        // bzw. der Start brach mit "OpenBao ist nicht konfiguriert" ab.
        assertNotEquals("bao:guild-printer-int", wert,
                "die Referenz kam unaufgeloest durch — der bao-Client war nicht verdrahtet");
        assertEquals("aufgeloest", wert);
    }

    @Test
    @DisplayName("gewoehnliche Werte bleiben unberuehrt (Negativkontrolle)")
    void gewoehnlicheWerteBleiben() {
        StandardEnvironment umgebung = umgebung();

        new VaultwardenEnvironmentPostProcessor().postProcessEnvironment(umgebung, null);

        assertEquals("secret", umgebung.getProperty("PLAINTEXT_BAO_MOUNT"));
    }
}
