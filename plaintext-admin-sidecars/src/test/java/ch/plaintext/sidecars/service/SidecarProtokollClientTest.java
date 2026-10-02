/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.AuthZustand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Karte 1400: Abfrage und Aufruf gegen einen echten HTTP-Sidecar. */
class SidecarProtokollClientTest {

    private final SidecarProtokollClient client = new SidecarProtokollClient();

    @Test
    @DisplayName("Beschreibung und Token-Prüfung: gültig, ungültig, keiner")
    void abfrage() throws Exception {
        try (TestSidecar s = new TestSidecar("test", "ok")) {
            SidecarProtokollClient.Abfrage a = client.frage(s.url(), TestSidecar.TOKEN);
            assertThat(a.erreichbar()).isTrue();
            assertThat(a.beschreibung().name()).isEqualTo("test");
            assertThat(a.auth()).isEqualTo(AuthZustand.GUELTIG);
            assertThat(client.frage(s.url(), "falsch").auth()).isEqualTo(AuthZustand.UNGUELTIG);
            assertThat(client.frage(s.url(), null).auth()).isEqualTo(AuthZustand.KEIN_TOKEN);
        }
    }

    @Test
    @DisplayName("Nicht erreichbar, falscher Status und kein Protokoll werden als Fehler gemeldet")
    void fehler() throws Exception {
        int frei;
        try (ServerSocket so = new ServerSocket(0)) {
            frei = so.getLocalPort();
        }
        assertThat(client.frage("http://127.0.0.1:" + frei, null).fehler()).startsWith("Nicht erreichbar");
        try (TestSidecar s = new TestSidecar("test", "ok")) {
            s.antwort = "{\"hallo\":1}";
            assertThat(client.frage(s.url(), null).fehler()).contains("Kein gültiges Sidecar-Protokoll");
            assertThat(client.frage(s.url() + "/umleitung", null).fehler()).as("Weiterleitung wird nicht verfolgt").contains("302");
        }
    }

    @Test
    @DisplayName("Aufruf einer Fähigkeit: Pfad aus der Beschreibung, Token mitgeschickt")
    void aufruf() throws Exception {
        try (TestSidecar s = new TestSidecar("test", "ok")) {
            SidecarBeschreibung b = client.frage(s.url(), null).beschreibung();
            SidecarProtokollClient.Antwort a = client.rufe(new SidecarVerbindung("test", s.url(), TestSidecar.TOKEN),
                    b.faehigkeit("bild.info"), Map.of("datei", "a b.jpg"), null);
            assertThat(a.status()).isEqualTo(200);
            assertThat(a.text()).isEqualTo("{\"breite\":640}");
            assertThat(s.aufrufe).contains("GET /info?datei=a+b.jpg auth=true");
            SidecarProtokollClient.Antwort ohne = client.rufe(new SidecarVerbindung("test", s.url(), null), b.faehigkeit("bild.info"), Map.of(), null);
            assertThat(ohne.status()).as("Gegenprobe ohne Token").isEqualTo(401);
        }
    }
}
