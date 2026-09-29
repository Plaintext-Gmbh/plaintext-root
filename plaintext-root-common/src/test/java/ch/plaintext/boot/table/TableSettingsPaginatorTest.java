/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import jakarta.faces.component.UIData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.primefaces.event.data.PageEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1336: Paginator oben/unten und Zeilen pro Seite im gespeicherten Stand.
 *
 * <p>Der Massstab fuer "rueckwaertskompatibel" ist hier ein Stand, in dem die neuen Felder
 * fehlen ({@code null}): die Tabelle muss sich dann genau so verhalten wie vor der Karte —
 * Paginator oben und unten, die Zeilen pro Seite der Seite.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TableSettingsPaginatorTest {

    private static final List<TableColumn> COLUMNS = List.of(
            new TableColumn("nr", "Nr", 80),
            new TableColumn("name", "Name", 200));

    private TableStateStore store;

    private TableSettings anzeige;

    @BeforeEach
    void setUp() {
        store = mock(TableStateStore.class);
        when(store.load(any())).thenReturn(new TableState());
        when(store.load(any(), any())).thenReturn(new TableState());
        anzeige = new TableSettings("guild-member", true, 200);
        anzeige.init(store, COLUMNS);
        clearInvocations(store);
    }

    @Test
    @DisplayName("Ohne Einstellung: Paginator oben und unten, Vorgabe der Seite - das Verhalten vor Karte 1336")
    void vorgabeWieBisher() {
        assertThat(anzeige.isPaginatorOben()).isTrue();
        assertThat(anzeige.isPaginatorUnten()).isTrue();
        assertThat(anzeige.isPaginator()).isTrue();
        assertThat(anzeige.getPaginatorPosition()).isEqualTo("both");
        assertThat(anzeige.getRows()).isEqualTo(200);
        assertThat(anzeige.getSeitengroesse()).isEqualTo(200);
        // Nichts davon steht im Stand, solange niemand etwas verstellt.
        assertThat(anzeige.getState().getPaginatorTop()).isNull();
        assertThat(anzeige.getState().getPaginatorBottom()).isNull();
        assertThat(anzeige.getState().getRowsPerPage()).isNull();
    }

    @Test
    @DisplayName("Ohne Vorgabe im Konstruktor gilt 20, ein unbrauchbarer Wert faellt ebenfalls darauf")
    void vorgabeOhneAngabe() {
        TableSettings ohne = new TableSettings("x", false);
        ohne.init(null, COLUMNS);
        assertThat(ohne.getRows()).isEqualTo(TableSettings.VORGABE_ZEILEN).isEqualTo(20);

        TableSettings kaputt = new TableSettings("x", false, 0);
        kaputt.init(null, COLUMNS);
        assertThat(kaputt.getVorgabeZeilen()).isEqualTo(20);
    }

    @Test
    @DisplayName("Oben oder unten abgewaehlt: die Position folgt, der Paginator bleibt")
    void einSeitigerPaginator() {
        anzeige.setPaginatorOben(false);
        assertThat(anzeige.getPaginatorPosition()).isEqualTo("bottom");
        assertThat(anzeige.isPaginator()).isTrue();
        assertThat(anzeige.getRows()).isEqualTo(200);

        anzeige.setPaginatorOben(true);
        anzeige.setPaginatorUnten(false);
        assertThat(anzeige.getPaginatorPosition()).isEqualTo("top");
        assertThat(anzeige.isPaginator()).isTrue();
    }

    @Test
    @DisplayName("Beide abgewaehlt: kein Paginator und rows=0, also alle Zeilen statt stumm der ersten 200")
    void ohnePaginatorAlleZeilen() {
        anzeige.setPaginatorOben(false);
        anzeige.setPaginatorUnten(false);

        assertThat(anzeige.isPaginator()).isFalse();
        assertThat(anzeige.getRows()).isZero();
        // Die gewaehlte Seitengroesse bleibt erhalten und gilt wieder, sobald ein Paginator da ist.
        assertThat(anzeige.getSeitengroesse()).isEqualTo(200);
        anzeige.setPaginatorUnten(true);
        assertThat(anzeige.getRows()).isEqualTo(200);
    }

    @Test
    @DisplayName("Karte 1336 Punkt 4: 250, 500, 1000, 2000 und 3000 stehen zur Auswahl, die bisherigen Werte bleiben")
    void seitengroessen() {
        assertThat(anzeige.getSeitengroessen())
                .contains(250, 500, 1000, 2000, 3000)
                .contains(20, 50, 100, 200, 300)
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(anzeige.getRowsPerPageTemplate()).isEqualTo("20,50,100,200,250,300,500,1000,2000,3000");
    }

    @Test
    @DisplayName("Eine Vorgabe ausserhalb der Liste wird aufgenommen, damit das Feld sie anzeigen kann")
    void fremdeVorgabeWirdAufgenommen() {
        TableSettings sonder = new TableSettings("x", false, 75);
        sonder.init(null, COLUMNS);
        assertThat(sonder.getSeitengroessen()).contains(75).isSorted();
        assertThat(sonder.getRowsPerPageTemplate()).startsWith("20,50,75,100");
    }

    @Test
    @DisplayName("Seitengroesse aus dem Bedienbereich: gespeichert, unbrauchbare Werte fallen auf die Vorgabe")
    void seitengroesseSetzen() {
        anzeige.setSeitengroesse(1000);
        anzeige.onSeitengroesseChange(":fm:tbl");   // ohne Faces-Kontext: nur speichern

        assertThat(anzeige.getRows()).isEqualTo(1000);
        assertThat(anzeige.getState().getRowsPerPage()).isEqualTo(1000);
        verify(store).save(eq("guild-member"), any(TableState.class));

        anzeige.setSeitengroesse(0);
        assertThat(anzeige.getState().getRowsPerPage()).isNull();
        assertThat(anzeige.getRows()).isEqualTo(200);
        anzeige.setSeitengroesse(null);
        assertThat(anzeige.getRows()).isEqualTo(200);
    }

    @Test
    @DisplayName("Seitengroesse aus dem Paginator selbst (event=page) wird gespeichert, reines Blaettern nicht")
    void seitengroesseAusDemPaginator() {
        anzeige.onPage(blaettern(200));
        anzeige.onPage(blaettern(null));
        anzeige.onPage(null);
        verify(store, never()).save(any(), any());

        anzeige.onPage(blaettern(3000));
        assertThat(anzeige.getRows()).isEqualTo(3000);
        verify(store).save(eq("guild-member"), any(TableState.class));
    }

    @Test
    @DisplayName("Checkbox geaendert: gespeichert - auch ohne Faces-Kontext kein Fehler")
    void paginatorAenderungWirdGespeichert() {
        anzeige.setPaginatorOben(false);
        assertThatCode(() -> anzeige.onPaginatorChange(":fm:tbl")).doesNotThrowAnyException();

        assertThat(anzeige.getState().getPaginatorTop()).isFalse();
        verify(store).save(eq("guild-member"), any(TableState.class));
    }

    @Test
    @DisplayName("Die Tabelle wird angeglichen: Startzeile 0 und die Zeilen des Stands, auch gegen einen lokalen Wert")
    void tabelleAngleichen() {
        UIData tabelle = mock(UIData.class);
        anzeige.setSeitengroesse(500);
        anzeige.angleichen(tabelle);
        verify(tabelle).setFirst(0);
        verify(tabelle).setRows(500);

        UIData ohnePaginator = mock(UIData.class);
        anzeige.setPaginatorOben(false);
        anzeige.setPaginatorUnten(false);
        anzeige.angleichen(ohnePaginator);
        verify(ohnePaginator).setFirst(0);
        verify(ohnePaginator).setRows(0);
    }

    @Test
    @DisplayName("Profile tragen Paginator und Seitengroesse mit - ein Profil von vor Karte 1336 ergibt die Vorgabe")
    void profileTragenPaginator() {
        // "Standard" entsteht beim init mit leerem Stand: seine Felder sind null.
        anzeige.setNewProfileName("Lang");
        anzeige.createProfile();
        anzeige.setPaginatorOben(false);
        anzeige.setSeitengroesse(2000);
        anzeige.onPaginatorChange(null);

        TableColumnProfile lang = anzeige.getState().getProfiles().get("Lang");
        assertThat(lang.getPaginatorTop()).isFalse();
        assertThat(lang.getRowsPerPage()).isEqualTo(2000);

        anzeige.setSelectedProfile(TableSettings.PROFILE_DEFAULT);
        anzeige.profilAnwenden(":fm:tbl");
        assertThat(anzeige.getPaginatorPosition()).isEqualTo("both");
        assertThat(anzeige.getRows()).isEqualTo(200);

        anzeige.setSelectedProfile("Lang");
        anzeige.profilAnwenden(":fm:tbl");
        assertThat(anzeige.getPaginatorPosition()).isEqualTo("bottom");
        assertThat(anzeige.getRows()).isEqualTo(2000);
    }

    @Test
    @DisplayName("Eine Aenderung an Spalten laesst Paginator und Seitengroesse unangetastet")
    void spaltenAenderungLaesstPaginatorStehen() {
        anzeige.setPaginatorUnten(false);
        anzeige.setSeitengroesse(250);
        anzeige.standardSpalten();
        anzeige.alleSpaltenEin();

        assertThat(anzeige.getPaginatorPosition()).isEqualTo("top");
        assertThat(anzeige.getRows()).isEqualTo(250);
    }

    /** Das Ereignis des Paginators; gemockt, weil BehaviorEvent Komponente und Behavior verlangt. */
    private static PageEvent blaettern(Integer zeilen) {
        PageEvent event = mock(PageEvent.class);
        when(event.getRowsPerPage()).thenReturn(zeilen);
        return event;
    }
}
