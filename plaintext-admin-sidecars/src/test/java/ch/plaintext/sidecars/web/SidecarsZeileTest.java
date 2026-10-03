/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.web;

import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.service.SidecarBeschreibung;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1413: welche Zeilen beim Laden offen stehen und was die Spalte «Zugang» zeigt. Die Seite
 * verlässt sich darauf ({@code expandedRow="#{z.aufgeklappt}"}); ein Fehler hier hiesse, dass ein
 * Sidecar ohne Token zugeklappt und damit übersehen wird.
 */
class SidecarsZeileTest {

    private static Sidecar sidecar(boolean erreichbar, String status, AuthZustand auth, boolean token) {
        Sidecar s = new Sidecar();
        s.setName("fotos");
        s.setUrl("http://fotos:8080");
        s.setErreichbar(erreichbar);
        s.setStatus(status);
        s.setAuthZustand(auth);
        s.setTokenEncrypted(token ? "verschluesselt" : null);
        return s;
    }

    private static SidecarBeschreibung beschreibung(String authArt) {
        return new SidecarBeschreibung("plaintext-sidecar/1", "fotos", "Fotos", null, "1.0", "ok", null, authArt,
                List.of(), List.of(), null, List.of());
    }

    @Test
    @DisplayName("ok mit gültigem Token: zu, Zugang «Token ✓»")
    void allesGut() {
        var z = new SidecarsBackingBean.Zeile(sidecar(true, "ok", AuthZustand.GUELTIG, true), beschreibung("bearer"));
        assertThat(z.isAufgeklappt()).isFalse();
        assertThat(z.getZugang()).isEqualTo("Token ✓");
        assertThat(z.getZugangSchwere()).isEqualTo("success");
        assertThat(z.getAmpelText()).isEqualTo("in Ordnung");
    }

    @Test
    @DisplayName("Token verlangt, keiner hinterlegt: offen, «Token fehlt»")
    void tokenFehlt() {
        var z = new SidecarsBackingBean.Zeile(sidecar(true, "ok", AuthZustand.KEIN_TOKEN, false), beschreibung("bearer"));
        assertThat(z.isFehlerhaft()).isFalse();
        assertThat(z.isTokenFehlt()).isTrue();
        assertThat(z.isAufgeklappt()).isTrue();
        assertThat(z.getZugang()).isEqualTo("Token fehlt");
        assertThat(z.getZugangSchwere()).isEqualTo("danger");
    }

    @Test
    @DisplayName("Token abgelehnt: offen, «Token ungültig»")
    void tokenUngueltig() {
        var z = new SidecarsBackingBean.Zeile(sidecar(true, "ok", AuthZustand.UNGUELTIG, true), beschreibung("bearer"));
        assertThat(z.isAufgeklappt()).isTrue();
        assertThat(z.getZugang()).isEqualTo("Token ungültig");
    }

    @Test
    @DisplayName("Kein Token nötig: zu, «nicht nötig», auch ohne hinterlegten Token")
    void ohneAuth() {
        var z = new SidecarsBackingBean.Zeile(sidecar(true, "ok", AuthZustand.NICHT_NOETIG, false), beschreibung("keine"));
        assertThat(z.isTokenFehlt()).isFalse();
        assertThat(z.isAufgeklappt()).isFalse();
        assertThat(z.getZugang()).isEqualTo("nicht nötig");
        assertThat(z.getZugangSchwere()).isEqualTo("info");
    }

    @Test
    @DisplayName("Nicht erreichbar, Fehlerampel oder Hinweis: offen")
    void fehler() {
        var aus = new SidecarsBackingBean.Zeile(sidecar(false, null, AuthZustand.UNBEKANNT, true), null);
        assertThat(aus.getAmpel()).isEqualTo("aus");
        assertThat(aus.getAmpelText()).isEqualTo("nicht erreichbar");
        assertThat(aus.isAufgeklappt()).isTrue();

        var rot = new SidecarsBackingBean.Zeile(sidecar(true, "fehler", AuthZustand.NICHT_NOETIG, false), beschreibung("keine"));
        assertThat(rot.isAufgeklappt()).isTrue();

        Sidecar mitHinweis = sidecar(true, "ok", AuthZustand.NICHT_NOETIG, false);
        mitHinweis.setFehler("Meldet sich als «x» statt «fotos».");
        assertThat(new SidecarsBackingBean.Zeile(mitHinweis, beschreibung("keine")).isAufgeklappt()).isTrue();
    }

    @Test
    @DisplayName("Eingeschränkt mit gültigem Token: zu, aber Ampel gelb")
    void eingeschraenkt() {
        var z = new SidecarsBackingBean.Zeile(sidecar(true, "eingeschraenkt", AuthZustand.GUELTIG, true), beschreibung("bearer"));
        assertThat(z.isAufgeklappt()).isFalse();
        assertThat(z.getAmpelText()).isEqualTo("eingeschränkt");
        var unbekannt = new SidecarsBackingBean.Zeile(sidecar(true, null, AuthZustand.UNBEKANNT, true), beschreibung("bearer"));
        assertThat(unbekannt.getAmpelText()).isEqualTo("Status unbekannt");
        assertThat(unbekannt.getZugang()).isEqualTo("Token ungeprüft");
        assertThat(unbekannt.getZugangSchwere()).isEqualTo("warning");
    }

    @Test
    @DisplayName("Zeitformat und Dialogzustand der Ablage")
    void zeitUndDialog() {
        SidecarsBackingBean bean = new SidecarsBackingBean();
        assertThat(bean.zeit(null)).isEmpty();
        assertThat(bean.zeit(Instant.parse("2026-10-03T08:15:00Z"))).isEqualTo("03.10.2026 10:15");
        bean.setNeueUrl("http://x");
        bean.ergaenzenVorbereiten();
        assertThat(bean.getNeueUrl()).isNull();
        bean.setAbName("alt");
        bean.ablageNeu();
        assertThat(bean.isAblageBestehend()).isFalse();
        assertThat(bean.getAbName()).isNull();
        assertThat(bean.getAnzahlOk()).isZero();
        assertThat(bean.getAnzahlAblagenFehler()).isZero();
    }
}
