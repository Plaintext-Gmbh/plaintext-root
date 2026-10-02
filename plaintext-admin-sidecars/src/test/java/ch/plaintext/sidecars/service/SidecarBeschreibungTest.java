/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Karte 1400: Prüfung der Beschreibung nach docs/SIDECAR_PROTOKOLL.md. */
class SidecarBeschreibungTest {

    @Test
    @DisplayName("Positivkontrolle: gültige Beschreibung mit Teilen und Fähigkeiten")
    void gueltig() throws Exception {
        SidecarBeschreibung b = SidecarBeschreibung.lies(TestSidecar.beschreibung("fotos", "ok",
                String.join(",", TestSidecar.INFO, TestSidecar.SENDEN, TestSidecar.INTERN)));
        assertThat(b.name()).isEqualTo("fotos");
        assertThat(b.ohneAuth()).isFalse();
        assertThat(b.teile()).hasSize(1);
        assertThat(b.faehigkeiten()).extracting(SidecarBeschreibung.Faehigkeit::id)
                .containsExactly("bild.info", "nachricht.senden.test", "bild.intern");
        assertThat(b.faehigkeit("bild.info").perMcpAufrufbar()).isTrue();
        assertThat(b.faehigkeit("bild.intern").perMcpAufrufbar()).as("mcp=false").isFalse();
        assertThat(b.faehigkeit("nachricht.senden.test").perMcpAufrufbar()).as("aussen nie per MCP, auch mit mcp=true").isFalse();
        assertThat(b.hinweise()).isEmpty();
    }

    @Test
    @DisplayName("Unbekanntes Protokoll, fehlender Name oder Status machen die Beschreibung ungültig")
    void ungueltig() {
        assertThatThrownBy(() -> SidecarBeschreibung.lies("kein json")).hasMessageContaining("kein JSON");
        assertThatThrownBy(() -> SidecarBeschreibung.lies("{\"protokoll\":\"plaintext-sidecar/2\"}")).hasMessageContaining("Unbekanntes Protokoll");
        assertThatThrownBy(() -> SidecarBeschreibung.lies(TestSidecar.beschreibung("Gross Name", "ok", ""))).hasMessageContaining("name");
        assertThatThrownBy(() -> SidecarBeschreibung.lies(TestSidecar.beschreibung("x", "gut", ""))).hasMessageContaining("status");
    }

    @Test
    @DisplayName("Eine fehlerhafte Fähigkeit wird übergangen und gemeldet, der Dienst bleibt sichtbar")
    void fehlerhafteFaehigkeit() throws Exception {
        String boese = """
                {"id":"bild.raus","titel":"Raus","methode":"GET","pfad":"/../admin","auth":true,"seiteneffekt":"keiner"}""";
        String ohneId = """
                {"titel":"ohne","methode":"GET","pfad":"/x","seiteneffekt":"keiner"}""";
        SidecarBeschreibung b = SidecarBeschreibung.lies(TestSidecar.beschreibung("x", "ok", String.join(",", TestSidecar.INFO, boese, ohneId)));
        assertThat(b.faehigkeiten()).extracting(SidecarBeschreibung.Faehigkeit::id).containsExactly("bild.info");
        assertThat(b.hinweise()).hasSize(2);
    }

    @Test
    @DisplayName("Pfade: nur relative ohne Ausbruch, Schema, Query oder Kodierung")
    void pfade() {
        assertThat(SidecarBeschreibung.pfadGueltig("/bild/vorschau")).isTrue();
        assertThat(SidecarBeschreibung.pfadGueltig("bild")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("//andere.example/x")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("/a/../b")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("/a?x=1")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("/a%2e%2e/b")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("/http://x")).isFalse();
        assertThat(SidecarBeschreibung.pfadGueltig("/a b")).isFalse();
    }
}
