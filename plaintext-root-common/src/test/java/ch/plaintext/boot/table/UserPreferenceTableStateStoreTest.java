/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Die mitgelieferte Ablage (Karte 1077): Schluessel je Mandant, Uebernahme des Altbestands aus
 * {@code tabellenSpalten}, Durchreichen ans Session-Bean.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferenceTableStateStoreTest {

    private static final List<String> SPALTEN = List.of("id", "username", "vorname", "mandat");

    @Mock
    private UserPreferencesBackingBean preferences;

    @Mock
    private PlaintextSecurity security;

    private UserPreferenceTableStateStore store;

    @BeforeEach
    void setUp() {
        when(security.getMandat()).thenReturn("guild42");
        store = new UserPreferenceTableStateStore(preferences, security);
    }

    @Test
    @DisplayName("Der Schluessel traegt den Mandanten: derselbe Benutzer hat je Mandant eigene Spalten")
    void schluesselJeMandant() {
        assertThat(store.schluessel("useradmin")).isEqualTo("guild42/useradmin");

        when(security.getMandat()).thenReturn("plaintext");
        assertThat(store.schluessel("useradmin")).isEqualTo("plaintext/useradmin");
    }

    @Test
    @DisplayName("Ohne Mandant (kein Benutzer, Test ohne Security) bleibt der Schluessel gueltig")
    void ohneMandantKeineNpe() {
        when(security.getMandat()).thenReturn(null);
        assertThat(store.schluessel("useradmin")).isEqualTo("/useradmin");

        UserPreferenceTableStateStore ohneSecurity = new UserPreferenceTableStateStore(preferences, null);
        assertThat(ohneSecurity.schluessel("useradmin")).isEqualTo("/useradmin");
    }

    @Test
    @DisplayName("Ein gespeicherter Stand kommt unveraendert und als dieselbe Instanz zurueck")
    void gespeicherterStandWirdGeliefert() {
        TableState gespeichert = new TableState();
        gespeichert.getColumnWidths().put("id", "70px");
        when(preferences.tabellenStand("guild42/useradmin")).thenReturn(gespeichert);

        assertThat(store.load("useradmin", SPALTEN)).isSameAs(gespeichert);
        assertThat(store.load("useradmin")).isSameAs(gespeichert);
        // Der Altbestand wird dann gar nicht erst angefasst.
        verify(preferences, never()).tabellenSpalten(anyString());
    }

    @Test
    @DisplayName("Ohne Stand und ohne Altbestand: ein frischer Stand — die Seite startet mit ihren Vorgaben")
    void frischerStand() {
        when(preferences.tabellenStand(anyString())).thenReturn(null);
        when(preferences.tabellenSpalten(anyString())).thenReturn(null);

        TableState state = store.load("useradmin", SPALTEN);

        assertThat(state).isNotNull();
        assertThat(state.getColumnVisible()).isEmpty();
        assertThat(state.getProfiles()).isEmpty();
    }

    /**
     * Der Kern der Zusammenfuehrung: die alte Liste kennt nur die sichtbaren Spalten. Erst mit
     * dem Spaltensatz der Seite wird daraus "diese an, jene aus" — ohne ihn kaeme jede nicht
     * genannte Spalte auf ihre Vorgabe zurueck, und die Abwahl des Benutzers waere verloren.
     */
    @Test
    @DisplayName("Altbestand aus tabellenSpalten: genannte Spalten an, alle anderen bekannten aus")
    void altbestandWirdUebernommen() {
        when(preferences.tabellenStand("guild42/useradmin")).thenReturn(null);
        when(preferences.tabellenSpalten("useradmin"))
                .thenReturn(new ArrayList<>(List.of("username", "mandat", "gibtsnichtmehr")));

        TableState state = store.load("useradmin", SPALTEN);

        assertThat(state.getColumnVisible())
                .containsEntry("id", false)
                .containsEntry("username", true)
                .containsEntry("vorname", false)
                .containsEntry("mandat", true)
                .doesNotContainKey("gibtsnichtmehr");
    }

    @Test
    @DisplayName("Die ausdruecklich leere Altauswahl bleibt leer: alle bekannten Spalten aus")
    void leereAltauswahlBleibtLeer() {
        when(preferences.tabellenStand(anyString())).thenReturn(null);
        when(preferences.tabellenSpalten("useradmin")).thenReturn(new ArrayList<>());

        TableState state = store.load("useradmin", SPALTEN);

        assertThat(state.getColumnVisible()).hasSize(SPALTEN.size()).containsValue(false).doesNotContainValue(true);
    }

    @Test
    @DisplayName("Ohne Spaltenschluessel laesst sich der Altbestand nicht deuten — dann gilt die Vorgabe")
    void altbestandOhneSpaltenBleibtVorgabe() {
        when(preferences.tabellenStand(anyString())).thenReturn(null);
        when(preferences.tabellenSpalten("useradmin")).thenReturn(new ArrayList<>(List.of("username")));

        assertThat(store.load("useradmin").getColumnVisible()).isEmpty();
        assertThat(store.load("useradmin", null).getColumnVisible()).isEmpty();
    }

    @Test
    @DisplayName("Speichern reicht den Stand unter dem Mandantenschluessel an die Session-Bean weiter")
    void speichernUnterSchluessel() {
        TableState state = new TableState();

        store.save("useradmin", state);

        verify(preferences).merkeTabellenStand("guild42/useradmin", state);
        verify(preferences, never()).merkeTabellenSpalten(anyString(), any());
    }

    @Test
    @DisplayName("Zusammenspiel mit TableSettings: Altbestand laden, Aenderung speichern, Altbestand bleibt")
    void mitTableSettings() {
        when(preferences.tabellenStand(anyString())).thenReturn(null);
        when(preferences.tabellenSpalten("useradmin")).thenReturn(new ArrayList<>(List.of("username")));

        TableSettings anzeige = new TableSettings("useradmin", false);
        anzeige.init(store, List.of(
                new TableColumn("id", "ID", 0),
                new TableColumn("username", "Benutzername", 0),
                new TableColumn("vorname", "Vorname", 0),
                new TableColumn("mandat", "Mandat", 0)));

        assertThat(anzeige.getVisibleColumns()).containsExactly("username");
        // init() legt das Standardprofil an und speichert damit sofort unter dem neuen Schluessel.
        verify(preferences).merkeTabellenStand(anyString(), any(TableState.class));
        verify(preferences, never()).merkeTabellenSpalten(anyString(), any());
    }
}
