/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.mcp;

import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.service.SidecarBeschreibung;
import ch.plaintext.sidecars.service.SidecarProtokollClient;
import ch.plaintext.sidecars.service.SidecarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Karte 1400: Rechte und Grenzen der MCP-Werkzeuge. */
class SidecarsMcpToolsTest {

    static final String BESCHREIBUNG = """
            {"protokoll":"plaintext-sidecar/1","name":"test","titel":"Test","version":"1","status":"ok","auth":{"art":"bearer"},
             "faehigkeiten":[
              {"id":"bild.info","titel":"Info","methode":"GET","pfad":"/info","auth":true,"mcp":true,"seiteneffekt":"keiner"},
              {"id":"bild.intern","titel":"Intern","methode":"POST","pfad":"/intern","auth":true,"mcp":false,"seiteneffekt":"keiner"},
              {"id":"nachricht.senden","titel":"Senden","methode":"POST","pfad":"/senden","auth":true,"mcp":true,"seiteneffekt":"aussen"}]}""";

    SidecarService service;
    SidecarProtokollClient client;
    SidecarsMcpTools tools;

    @BeforeEach
    void aufbau() throws Exception {
        service = mock(SidecarService.class);
        client = mock(SidecarProtokollClient.class);
        Sidecar s = new Sidecar();
        s.setName("test");
        s.setUrl("http://test:1");
        s.setErreichbar(true);
        s.setStatus("ok");
        s.setBeschreibungJson(BESCHREIBUNG);
        s.setTokenEncrypted("verschluesselt");
        when(service.liste()).thenReturn(List.of(s));
        when(service.sidecar("test")).thenReturn(s);
        when(service.beschreibung(s)).thenReturn(SidecarBeschreibung.lies(BESCHREIBUNG));
        when(service.verbindung("test")).thenReturn(Optional.of(new SidecarVerbindung("test", "http://test:1", "tok")));
        when(client.rufe(any(), any(), any(), any())).thenReturn(new SidecarProtokollClient.Antwort(200, "application/json", "{}", 2, false));
        tools = new SidecarsMcpTools(service, client);
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    static void als(String... rechte) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("x@example.invalid", null,
                Arrays.stream(rechte).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    @DisplayName("Ohne ROOT nichts, auch nicht lesen")
    void ohneRoot() {
        als("ROLE_ADMIN", "SCOPE_ADMIN");
        assertThat(tools.listSidecars()).startsWith("FEHLER").contains("ROOT");
        assertThat(tools.getSidecar("test")).startsWith("FEHLER");
    }

    @Test
    @DisplayName("Lesen mit ROOT ohne ADMIN-Scope; Beschreibung ohne Token, mit perMcpAufrufbar")
    void lesen() {
        als("ROLE_ROOT", "SCOPE_READ");
        assertThat(tools.listSidecars()).contains("\"bild.info\"", "\"nachricht.senden\"");
        String g = tools.getSidecar("test");
        assertThat(g).contains("\"perMcpAufrufbar\" : [ \"bild.info\" ]").doesNotContain("verschluesselt").doesNotContain("tok\"");
        assertThat(tools.findSidecarFaehigkeit("senden")).contains("nachricht.senden").doesNotContain("bild.info");
        assertThat(tools.registriereSidecar("http://x")).as("schreibend ohne ADMIN-Scope").startsWith("FEHLER").contains("scope=ADMIN");
    }

    @Test
    @DisplayName("Allgemeiner Aufruf nur für freigegebene Fähigkeiten ohne Wirkung nach aussen")
    void aufrufen() throws Exception {
        als("ROLE_ROOT", "SCOPE_ADMIN");
        assertThat(tools.rufeSidecarFaehigkeit("test", "bild.info", "{\"a\":\"1\"}", null)).contains("\"status\" : 200");
        verify(client).rufe(any(), any(), any(Map.class), any());
        assertThat(tools.rufeSidecarFaehigkeit("test", "bild.intern", null, null)).startsWith("FEHLER").contains("nicht für MCP");
        assertThat(tools.rufeSidecarFaehigkeit("test", "nachricht.senden", null, "{}")).startsWith("FEHLER").contains("aussen");
        assertThat(tools.rufeSidecarFaehigkeit("test", "gibt.es.nicht", null, null)).startsWith("FEHLER");
        assertThat(tools.rufeSidecarFaehigkeit("test", "bild.info", "kein json", null)).startsWith("FEHLER");
    }

    @Test
    @DisplayName("Gegenprobe: ohne ADMIN-Scope ruft nichts den Sidecar auf")
    void ohneScopeKeinAufruf() throws Exception {
        als("ROLE_ROOT");
        assertThat(tools.rufeSidecarFaehigkeit("test", "bild.info", null, null)).startsWith("FEHLER");
        assertThat(tools.setSidecarToken("test", "x")).startsWith("FEHLER");
        verify(client, never()).rufe(any(), any(), any(), any());
        verify(service, never()).setzeToken(any(), any());
    }
}
