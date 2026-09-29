/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import jakarta.faces.component.UIData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.primefaces.component.api.UIColumn;
import org.primefaces.component.datatable.DataTable;
import org.primefaces.event.data.SortEvent;
import org.primefaces.model.SortMeta;
import org.primefaces.model.SortOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Karte 1346: die Sortierung im gespeicherten Stand.
 *
 * <p>Die {@link SortMeta} hier sind echte PrimeFaces-Objekte; nur die Spalten-Id
 * ({@code columnKey}), die PrimeFaces selbst aus der Client-Id setzt und fuer die es keinen
 * oeffentlichen Setter gibt, kommt per Reflection hinein. Die Tabelle ist ein Mock, der zu jeder
 * Spalten-Id eine Spalte mit Kopftext liefert — genau das, was {@code DataTable.findColumn} im
 * Betrieb tut.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TableSettingsSortierungTest {

    private static final List<TableColumn> COLUMNS = List.of(
            new TableColumn("nr", "Nr", 80),
            new TableColumn("name", "Name", 200),
            new TableColumn("typ", "Typ", 120),
            new TableColumn("notiz", "Notiz", 300, false));

    private TableStateStore store;

    private TableSettings anzeige;

    private DataTable tabelle;

    @BeforeEach
    void setUp() {
        store = mock(TableStateStore.class);
        when(store.load(any())).thenReturn(new TableState());
        when(store.load(any(), any())).thenReturn(new TableState());
        anzeige = new TableSettings("guild-member", true, 200);
        anzeige.init(store, COLUMNS);
        clearInvocations(store);

        tabelle = mock(DataTable.class);
        spalte("fm:tbl:c-nr", "Nr");
        spalte("fm:tbl:c-name", "Name");
        spalte("fm:tbl:c-typ", "Typ");
        spalte("fm:tbl:c-notiz", "Notiz");
        spalte("fm:tbl:c-fremd", "Gibt es nicht");
    }

    @Test
    @DisplayName("Ohne Sortieren: sortBy null, die Tabelle bekommt keine Vorgabe (Verhalten vor Karte 1346)")
    void vorgabe() {
        assertThat(anzeige.getState().getSortBy()).isNull();
        assertThat(anzeige.getSortMeta()).isNull();
    }

    @Test
    @DisplayName("Einspaltig: Spalte ueber den Kopftext, Feld und Richtung gespeichert")
    void einspaltig() {
        anzeige.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.DESCENDING, 0),
                meta("fm:tbl:c-nr", "mitgliedsnummer", SortOrder.UNSORTED, SortMeta.MIN_PRIORITY)));

        assertThat(anzeige.getState().getSortBy())
                .containsExactly(new TableSort("name", "displayName", true));
        verify(store).save(eq("guild-member"), any());

        List<SortMeta> metas = anzeige.getSortMeta();
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).getField()).isEqualTo("displayName");
        assertThat(metas.get(0).getOrder()).isEqualTo(SortOrder.DESCENDING);
        assertThat(metas.get(0).getPriority()).isZero();
    }

    @Test
    @DisplayName("Mehrspaltig (sortMode=multiple): Reihenfolge nach Prioritaet, nicht nach Spaltenreihenfolge")
    void mehrspaltig() {
        // Prioritaet 0 = Typ, 1 = Nr — die Map kommt in Spaltenreihenfolge, wie bei PrimeFaces.
        anzeige.onSort(sortiert(meta("fm:tbl:c-nr", "mitgliedsnummer", SortOrder.ASCENDING, 1),
                meta("fm:tbl:c-name", "displayName", SortOrder.UNSORTED, SortMeta.MIN_PRIORITY),
                meta("fm:tbl:c-typ", "typName", SortOrder.DESCENDING, 0)));

        assertThat(anzeige.getState().getSortBy()).containsExactly(
                new TableSort("typ", "typName", true),
                new TableSort("nr", "mitgliedsnummer", false));

        List<SortMeta> metas = anzeige.getSortMeta();
        assertThat(metas).extracting(SortMeta::getField).containsExactly("typName", "mitgliedsnummer");
        assertThat(metas).extracting(SortMeta::getPriority).containsExactly(0, 1);
        assertThat(metas).extracting(SortMeta::getOrder)
                .containsExactly(SortOrder.DESCENDING, SortOrder.ASCENDING);
    }

    @Test
    @DisplayName("Kopftext unbekannt: Rueckfall auf das Feld, wenn es ein Spaltenschluessel ist, sonst nicht gespeichert")
    void zuordnung() {
        // useradmin: Kopftext uebersetzt ("Username"), Feld = Schluessel
        spalte("fm:tbl:c-en", "Type");
        anzeige.onSort(sortiert(meta("fm:tbl:c-en", "typ", SortOrder.ASCENDING, 0),
                meta("fm:tbl:c-fremd", "irgendwas", SortOrder.ASCENDING, 1)));

        assertThat(anzeige.getState().getSortBy()).containsExactly(new TableSort("typ", "typ", false));
    }

    @Test
    @DisplayName("Ohne Spalten-Id (fremde Tabelle) greift ebenfalls der Rueckfall aufs Feld")
    void ohneSpaltenId() {
        SortMeta meta = SortMeta.builder().field("name").order(SortOrder.ASCENDING).priority(0).build();
        SortEvent event = mock(SortEvent.class);
        Map<String, SortMeta> map = new LinkedHashMap<>();
        map.put("x", meta);
        when(event.getSortBy()).thenReturn(map);
        when(event.getComponent()).thenReturn(null);

        anzeige.onSort(event);

        assertThat(anzeige.getState().getSortBy()).containsExactly(new TableSort("name", "name", false));
    }

    @Test
    @DisplayName("Unveraendertes Sortieren schreibt nicht erneut; ausdruecklich unsortiert wird als leere Liste gespeichert")
    void unveraendertUndUnsortiert() {
        anzeige.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.ASCENDING, 0)));
        clearInvocations(store);

        anzeige.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.ASCENDING, 0)));
        verify(store, never()).save(any(), any());

        // allowUnsorting: der dritte Klick hebt die Sortierung auf
        anzeige.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.UNSORTED, SortMeta.MIN_PRIORITY)));
        verify(store).save(eq("guild-member"), any());
        assertThat(anzeige.getState().getSortBy()).isEmpty();
        assertThat(anzeige.getSortMeta()).isEmpty();
    }

    @Test
    @DisplayName("Ausgeblendete, unbekannte oder unvollstaendige Eintraege gehen nie an die Tabelle (sonst HTTP 500)")
    void nurAnwendbareEintraege() {
        anzeige.getState().setSortBy(List.of(
                new TableSort("notiz", "notiz", false),          // Vorgabe: ausgeblendet
                new TableSort("weg", "weg", false),              // Spalte gibt es nicht mehr
                new TableSort("typ", " ", false),                // ohne Feld
                new TableSort(null, "displayName", false),       // ohne Spalte
                new TableSort("name", "displayName", true)));

        assertThat(anzeige.getSortMeta()).extracting(SortMeta::getField).containsExactly("displayName");
        assertThat(anzeige.getSortMeta().get(0).getPriority()).isZero();   // Prioritaet ohne Luecken

        // Positivkontrolle: dieselbe Spalte eingeblendet geht mit.
        anzeige.setVisible("notiz", true);
        assertThat(anzeige.getSortMeta()).extracting(SortMeta::getField).containsExactly("notiz", "displayName");
    }

    @Test
    @DisplayName("Kennt die Tabelle das Feld nicht (Seite umgebaut), faellt der Eintrag weg")
    void feldDerTabelle() {
        anzeige.getState().setSortBy(List.of(
                new TableSort("name", "alterName", false),
                new TableSort("typ", "typName", true)));

        assertThat(anzeige.sortMetaFuer(Set.of("displayName", "typName")))
                .extracting(SortMeta::getField).containsExactly("typName");
        // ohne greifbare Tabelle (null) nur die Pruefung auf Spalte und Sichtbarkeit
        assertThat(anzeige.sortMetaFuer(null))
                .extracting(SortMeta::getField).containsExactly("alterName", "typName");
    }

    @Test
    @DisplayName("Profil nimmt die Sortierung mit und bringt sie beim Anwenden zurueck")
    void profil() {
        anzeige.onSort(sortiert(meta("fm:tbl:c-nr", "mitgliedsnummer", SortOrder.DESCENDING, 0)));
        anzeige.setNewProfileName("Nach Nummer");
        anzeige.createProfile();
        assertThat(anzeige.getState().getProfiles().get("Nach Nummer").getSortBy())
                .containsExactly(new TableSort("nr", "mitgliedsnummer", true));

        anzeige.setNewProfileName("Nach Name");
        anzeige.createProfile();
        anzeige.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.ASCENDING, 0)));
        assertThat(anzeige.getState().getProfiles().get("Nach Name").getSortBy())
                .containsExactly(new TableSort("name", "displayName", false));

        anzeige.setSelectedProfile("Nach Nummer");
        anzeige.profilAnwenden(":fm:tbl");   // ohne Faces-Kontext: nur anwenden
        assertThat(anzeige.getState().getSortBy()).containsExactly(new TableSort("nr", "mitgliedsnummer", true));

        // Die Liste im Profil ist eine Kopie, kein gemeinsames Objekt.
        assertThat(anzeige.getState().getSortBy())
                .isNotSameAs(anzeige.getState().getProfiles().get("Nach Nummer").getSortBy());
    }

    @Test
    @DisplayName("Profil aus der Zeit vor Karte 1346 (sortBy null) ergibt wieder die Vorgabe der Seite")
    void altesProfil() {
        anzeige.onSort(sortiert(meta("fm:tbl:c-nr", "mitgliedsnummer", SortOrder.DESCENDING, 0)));
        TableColumnProfile alt = new TableColumnProfile();
        anzeige.getState().getProfiles().put("Alt", alt);

        anzeige.setSelectedProfile("Alt");
        anzeige.onProfileSelected();

        assertThat(anzeige.getState().getSortBy()).isNull();
        assertThat(anzeige.getSortMeta()).isNull();
    }

    @Test
    @DisplayName("Profilwechsel verwirft die Sortierzuordnung der Tabelle, damit PrimeFaces sie neu aufbaut")
    void sortierungAngleichen() {
        anzeige.sortierungAngleichen(tabelle);
        verify(tabelle).setSortByAsMap(null);

        // Eine Tabelle ohne Sortierung (reines UIData) ist kein Fehler.
        assertThatCode(() -> anzeige.sortierungAngleichen(mock(UIData.class))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Ohne Ablage und ohne Ereignisinhalt kein Fehler")
    void robust() {
        TableSettings ohne = new TableSettings("x", false);
        ohne.init(null, COLUMNS);
        assertThatCode(() -> ohne.onSort(null)).doesNotThrowAnyException();
        assertThatCode(() -> ohne.onSort(mock(SortEvent.class))).doesNotThrowAnyException();
        ohne.onSort(sortiert(meta("fm:tbl:c-name", "displayName", SortOrder.ASCENDING, 0)));
        assertThat(ohne.getState().getSortBy()).containsExactly(new TableSort("name", "displayName", false));

        // findColumn wirft bei unbekannter Id (DataTable.findColumn) — dann Rueckfall aufs Feld
        when(tabelle.findColumn("kaputt")).thenThrow(new jakarta.faces.FacesException("Cannot find column"));
        ohne.onSort(sortiert(meta("kaputt", "nr", SortOrder.ASCENDING, 0)));
        assertThat(ohne.getState().getSortBy()).containsExactly(new TableSort("nr", "nr", false));
    }

    // ── Helfer ─────────────────────────────────────────────────────────────

    private void spalte(String columnKey, String kopftext) {
        UIColumn spalte = mock(UIColumn.class);
        when(spalte.getHeaderText()).thenReturn(kopftext);
        when(tabelle.findColumn(columnKey)).thenReturn(spalte);
    }

    /** Eine echte SortMeta, wie PrimeFaces sie nach dem Klick fuehrt. */
    private static SortMeta meta(String columnKey, String field, SortOrder order, int priority) {
        SortMeta meta = SortMeta.builder().field(field).order(order).priority(priority).build();
        ReflectionTestUtils.setField(meta, "columnKey", columnKey);
        return meta;
    }

    /** Das Ereignis, das {@code <p:ajax event="sort">} liefert: alle sortierbaren Spalten nach Spalten-Id. */
    private SortEvent sortiert(SortMeta... metas) {
        Map<String, SortMeta> map = new LinkedHashMap<>();
        for (SortMeta meta : metas) {
            map.put(meta.getColumnKey(), meta);
        }
        SortEvent event = mock(SortEvent.class);
        when(event.getSortBy()).thenReturn(map);
        when(event.getComponent()).thenReturn(tabelle);
        return event;
    }
}
