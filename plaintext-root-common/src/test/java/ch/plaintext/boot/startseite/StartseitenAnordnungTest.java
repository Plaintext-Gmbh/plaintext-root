/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.startseite;

import ch.plaintext.DashboardTileData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1351: die Anordnung der Startseite — Reihenfolge, Sichtbarkeit, Breite — und vor allem,
 * dass ein gespeichertes Layout nie mehr zeigt, als die Rechte erlauben.
 */
@DisplayName("Karte 1351: Anordnung der Startseite")
class StartseitenAnordnungTest {

    private static DashboardTileData kachel(String id) {
        DashboardTileData t = new DashboardTileData();
        t.setId(id);
        t.setTitle(id);
        return t;
    }

    private static List<DashboardTileData> erlaubt(String... ids) {
        List<DashboardTileData> liste = new ArrayList<>();
        for (String id : ids) {
            liste.add(kachel(id));
        }
        return liste;
    }

    private static List<String> ids(List<DashboardTileData> kacheln) {
        return kacheln.stream().map(DashboardTileData::getId).toList();
    }

    private static StartseitenLayout layout(StartseitenLayout.Eintrag... eintraege) {
        return new StartseitenLayout(List.of(eintraege));
    }

    private static StartseitenLayout.Eintrag e(String id, boolean sichtbar, boolean halb) {
        return new StartseitenLayout.Eintrag(id, sichtbar, halb);
    }

    @Nested
    @DisplayName("anwenden")
    class Anwenden {

        @Test
        @DisplayName("Ohne Layout: Standardreihenfolge, alles sichtbar, halb breit")
        void ohneLayout_Standard() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("a", "b", "c"), null);

            assertThat(ids(ergebnis)).containsExactly("a", "b", "c");
            assertThat(ergebnis).allMatch(t -> !t.isHidden() && t.isHalfWidth());
        }

        @Test
        @DisplayName("Reihenfolge, Sichtbarkeit und Breite kommen aus dem Layout")
        void layout_WirdAngewendet() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("a", "b", "c"),
                    layout(e("c", true, false), e("a", false, true), e("b", true, true)));

            assertThat(ids(ergebnis)).containsExactly("c", "a", "b");
            assertThat(ergebnis.get(0).isHalfWidth()).isFalse();
            assertThat(ergebnis.get(1).isHidden()).isTrue();
            assertThat(ergebnis.get(2).isHidden()).isFalse();
        }

        /**
         * Negativkontrolle der Rechte: "geheim" steht sichtbar im Layout, ist aber nicht in der
         * erlaubten Liste (Recht entzogen, Modul fuer den Mandanten aus). Es darf nicht erscheinen.
         */
        @Test
        @DisplayName("Rechte: eine Kachel aus dem Layout ohne Recht erscheint nicht")
        void layout_OhneRecht_Faellt_Weg() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("a", "b"),
                    layout(e("geheim", true, false), e("b", true, true), e("a", true, true)));

            assertThat(ids(ergebnis)).containsExactly("b", "a").doesNotContain("geheim");
        }

        /** Positivkontrolle zur vorigen: mit Recht erscheint dieselbe Kachel an ihrem Platz. */
        @Test
        @DisplayName("Rechte: dieselbe Kachel mit Recht erscheint an ihrem Platz")
        void layout_MitRecht_Erscheint() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("a", "b", "geheim"),
                    layout(e("geheim", true, false), e("b", true, true), e("a", true, true)));

            assertThat(ids(ergebnis)).containsExactly("geheim", "b", "a");
        }

        @Test
        @DisplayName("Neue Kacheln haengen hinten an, sichtbar und halb breit")
        void neueKachel_HintenSichtbar() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("neu", "a", "b"),
                    layout(e("b", true, false), e("a", false, false)));

            assertThat(ids(ergebnis)).containsExactly("b", "a", "neu");
            DashboardTileData neu = ergebnis.get(2);
            assertThat(neu.isHidden()).isFalse();
            assertThat(neu.isHalfWidth()).isTrue();
        }

        @Test
        @DisplayName("Doppelte Eintraege im Datensatz zeigen die Kachel nur einmal")
        void doppelterEintrag_Einmal() {
            List<DashboardTileData> ergebnis = StartseitenAnordnung.anwenden(erlaubt("a", "b"),
                    layout(e("a", true, true), e("a", false, false), e("b", true, true)));

            assertThat(ids(ergebnis)).containsExactly("a", "b");
            assertThat(ergebnis.get(0).isHidden()).isFalse();
        }

        @Test
        @DisplayName("Ausgeblendete Ids fuer das Anreichern")
        void ausgeblendeteIds() {
            assertThat(StartseitenAnordnung.ausgeblendeteIds(
                    layout(e("a", false, true), e("b", true, true), e("c", false, false))))
                    .containsExactlyInAnyOrder("a", "c");
            assertThat(StartseitenAnordnung.ausgeblendeteIds(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Formular")
    class Formular {

        @Test
        @DisplayName("Hin und zurueck ergibt dieselbe Anordnung")
        void rundlauf() {
            List<DashboardTileData> kacheln = StartseitenAnordnung.anwenden(erlaubt("a", "b,c", "d:e"),
                    layout(e("d:e", true, false), e("a", false, true), e("b,c", true, true)));

            String wert = StartseitenAnordnung.zuFormular(kacheln);
            assertThat(wert).isEqualTo("d%3Ae:1:0,a:0:1,b%2Cc:1:1");

            StartseitenLayout zurueck = StartseitenAnordnung.ausFormular(wert, List.of("a", "b,c", "d:e"));
            assertThat(zurueck.getEintraege()).containsExactly(
                    e("d:e", true, false), e("a", false, true), e("b,c", true, true));
        }

        /**
         * Ein von Hand gebautes Formular darf keine Kachel in den Datensatz schreiben, die der
         * Benutzer nicht sehen darf — sonst stuende sie beim naechsten Rechtewechsel schon da.
         */
        @Test
        @DisplayName("Rechte: eine fremde Id im Formular wird verworfen")
        void fremdeId_Verworfen() {
            StartseitenLayout layout = StartseitenAnordnung.ausFormular("geheim:1:0,a:1:1", List.of("a", "b"));

            assertThat(layout.getEintraege()).extracting(StartseitenLayout.Eintrag::getId).containsExactly("a");
        }

        @Test
        @DisplayName("Leerer Wert heisst: nichts zu speichern")
        void leer_Null() {
            assertThat(StartseitenAnordnung.ausFormular("", List.of("a"))).isNull();
            assertThat(StartseitenAnordnung.ausFormular(null, List.of("a"))).isNull();
        }

        @Test
        @DisplayName("Kaputte Teile werden uebersprungen, der Rest gilt")
        void kaputteTeile_Uebersprungen() {
            StartseitenLayout layout = StartseitenAnordnung.ausFormular(
                    "a:1,%ZZ:1:1,b:0:0,a:0:0,,c:1:1:1", List.of("a", "b", "c"));

            assertThat(layout.getEintraege()).containsExactly(e("b", false, false), e("a", false, false));
        }
    }
}
