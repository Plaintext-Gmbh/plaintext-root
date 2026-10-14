/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.huelle;

import ch.plaintext.testsupport.EmbeddedPg;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1512: root als Huelle startet ohne Instanz-Konfig im PV (/data/config fehlt) wie bisher und
 * bringt den MCP-Server fest mit. /mcp bleibt ohne Anmeldung zu, solange die Instanz-Konfig den
 * Bearer-Filter nicht einschaltet.
 *
 * <p>{@code @DirtiesContext} wie in StartseitenSchleifeChainTest: ein weiterer gecachter Kontext
 * haelt seinen Pool offen, und der CI-Postgres lief damit in "too many clients" (CI-Lauf 562).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HuelleKontextTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "huellekontexttest");
    }

    @Autowired
    private ApplicationContext ctx;

    @LocalServerPort
    private int port;

    @Test
    void startetOhnePvKonfigMitMcpServer() {
        assertThat(ctx.getBeansOfType(McpSyncServer.class)).hasSize(1);
        assertThat(ctx.getEnvironment().getProperty("spring.ai.mcp.server.name")).isEqualTo("plaintext-root");
    }

    @Test
    void mcpOhneAnmeldungNichtErreichbar() throws Exception {
        HttpResponse<String> antwort = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                        + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                        + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(antwort.statusCode()).isNotEqualTo(200);
        assertThat(antwort.body()).doesNotContain("serverInfo");
    }
}
