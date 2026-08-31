/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.table;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Die Anzeige-Steuerung ohne Faces-Kontext: Sichtbarkeiten, Breiten und Profile rechnen allein
 * auf dem Stand. Das ist kein Zufall, sondern die Bedingung dafuer, dass sie hier ueberhaupt
 * pruefbar ist — alles, was eine Seite von {@link TableSettings} sieht, laeuft ueber genau diese
 * Methoden.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TableSettingsTest {

    private static final List<TableColumn> COLUMNS = List.of(
            new TableColumn("name", "Name", 160),
            new TableColumn("team", "TeamKey", 110),
            new TableColumn("extra", "Extra", 200, false));

    private TableStateStore store;

    private TableSettings anzeige;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(TableStateStore.class);
        Mockito.when(store.load(any())).thenReturn(new TableState());
        anzeige = new TableSettings("testpage", true);
        anzeige.init(store, COLUMNS);
    }

    @Test
    @DisplayName("Vorgaben greifen, solange nichts verstellt ist — und das Standardprofil entsteht sofort")
    void vorgabenUndStandardprofil() {
        assertThat(anzeige.isVisible("name")).isTrue();
        assertThat(anzeige.isVisible("extra")).isFalse();
        assertThat(anzeige.getVisibleColumns()).containsExactly("name", "team");
        assertThat(anzeige.getSelectedProfile()).isEqualTo(TableSettings.PROFILE_DEFAULT);
        assertThat(anzeige.getState().getProfiles()).containsKey(TableSettings.PROFILE_DEFAULT);
    }

    @Test
    @DisplayName("Ohne Ablage bleibt die Bedienung nutzbar, nur das Speichern setzt aus")
    void ohneAblageBedienbar() {
        TableSettings frei = new TableSettings("testpage", true);
        frei.init(null, COLUMNS);

        frei.alleSpaltenEin();

        assertThat(frei.getVisibleColumns()).containsExactly("name", "team", "extra");
    }

    @Test
    @DisplayName("Breitenstil kommt aus dem Stand, sonst aus der Vorgabe; die Summe folgt den sichtbaren Spalten")
    void breitenUndSumme() {
        assertThat(anzeige.widthStyle("name")).isEqualTo("width:160px;");
        assertThat(anzeige.spaltenSumme()).isEqualTo(270);   // name + team, extra ist aus

        anzeige.getState().getColumnWidths().put("name", "300px");
        assertThat(anzeige.widthStyle("name")).isEqualTo("width:300px;");
        assertThat(anzeige.spaltenSumme()).isEqualTo(410);
        assertThat(anzeige.getTabellenBreite()).isEqualTo("width: 410px;");

        anzeige.setVisibleColumns(List.of("name", "team", "extra"));
        assertThat(anzeige.spaltenSumme()).isEqualTo(610);
    }

    @Test
    @DisplayName("Eine Seite ohne Breiten liefert leere Stile und keine Tabellenbreite")
    void ohneBreitenKeineStile() {
        TableSettings ohne = new TableSettings("andereseite", false);
        ohne.init(store, COLUMNS);
        assertThat(ohne.widthStyle("name")).isEmpty();
        assertThat(ohne.getTabellenBreite()).isEmpty();
    }

    @Test
    @DisplayName("Profil anlegen, umstellen, zurueckwechseln: der Stand folgt dem Profil")
    void profileWechseln() {
        // Stand einrichten und als "Breit" sichern
        anzeige.getState().getColumnWidths().put("name", "500px");
        anzeige.setNewProfileName("Breit");
        anzeige.createProfile();
        assertThat(anzeige.getProfileNames()).containsExactly("Breit", TableSettings.PROFILE_DEFAULT);

        // Anders einrichten — landet im aktiven Profil "Breit"
        anzeige.getState().getColumnWidths().put("name", "80px");
        anzeige.persist();

        // Das Standardprofil hat noch die Vorgabenbreite (kein name-Eintrag)
        anzeige.setSelectedProfile(TableSettings.PROFILE_DEFAULT);
        anzeige.onProfileSelected();
        assertThat(anzeige.getState().getColumnWidths()).doesNotContainKey("name");

        // Zurueck zu "Breit": die 80px von der letzten Aenderung stehen drin
        anzeige.setSelectedProfile("Breit");
        anzeige.onProfileSelected();
        assertThat(anzeige.getState().getColumnWidths()).containsEntry("name", "80px");
    }

    @Test
    @DisplayName("Ein Profilname wird nicht zweimal vergeben")
    void profilnameNurEinmal() {
        anzeige.setNewProfileName("Breit");
        anzeige.createProfile();
        anzeige.setNewProfileName("Breit");
        anzeige.createProfile();

        assertThat(anzeige.getProfileNames()).containsExactly("Breit", TableSettings.PROFILE_DEFAULT);
        assertThat(anzeige.getMeldung()).contains("gibt es schon");
    }

    @Test
    @DisplayName("Profil loeschen behaelt den Arbeitsstand und waehlt wieder ein Profil")
    void profilLoeschen() {
        anzeige.setNewProfileName("Weg");
        anzeige.createProfile();
        anzeige.deleteProfile();
        assertThat(anzeige.getProfileNames()).containsExactly(TableSettings.PROFILE_DEFAULT);
        assertThat(anzeige.getSelectedProfile()).isEqualTo(TableSettings.PROFILE_DEFAULT);
    }

    @Test
    @DisplayName("Kopftext wird auf den Spaltenschluessel zurueckgerechnet, unbekannte Koepfe verwerfen")
    void kopftextZuordnung() {
        assertThat(anzeige.keyFromHeader("Name")).isEqualTo("name");
        assertThat(anzeige.keyFromHeader("  teamkey ")).isEqualTo("team");
        assertThat(anzeige.keyFromHeader("Gibtsnicht")).isNull();
        assertThat(anzeige.keyFromHeader(null)).isNull();
    }

    @Test
    @DisplayName("Gesamtbreite verteilen: proportional, aber keine Spalte unter dem Minimum")
    void breiteVerteilen() {
        anzeige.setGesamtBreite(540);
        assertThat(anzeige.isBreiteAbweichend()).isTrue();
        anzeige.breitenKnopfGedrueckt();
        // name 160/270*540 = 320, team 110/270*540 = 220
        assertThat(anzeige.getState().getColumnWidths())
                .containsEntry("name", "320px")
                .containsEntry("team", "220px");
        assertThat(anzeige.getMeldung()).contains("540");
    }

    @Test
    @DisplayName("Zu schmale Gesamtbreite laesst die Tabelle wieder den Spalten folgen")
    void zuSchmaleGesamtbreiteWirdVerworfen() {
        anzeige.setGesamtBreite(50);

        assertThat(anzeige.getState().getTotalWidth()).isNull();
        assertThat(anzeige.isBreiteAbweichend()).isFalse();
        assertThat(anzeige.getTabellenBreite()).isEqualTo("width: 270px;");
    }

    @Test
    @DisplayName("Jede Aenderung landet in der Ablage — unter dem Seitenschluessel")
    void persistiertUnterSeite() {
        anzeige.persist();
        Mockito.verify(store, Mockito.atLeastOnce()).save(eq("testpage"), any(TableState.class));
    }
}
