/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import ch.plaintext.boot.plugins.objstore.SimpleStorable;
import ch.plaintext.boot.plugins.objstore.SimpleStorableConverter;
import ch.plaintext.boot.table.TableColumnProfile;
import ch.plaintext.boot.table.TableState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1077: {@code tabellenStaende} ueberlebt den Weg durch die Spalte — und ein Rollback.
 *
 * <p>Der Konverter ist absichtlich der echte ({@link SimpleStorableConverter}), nicht ein
 * Mapper aus dem Test: entscheidend ist, was <i>seine</i> Einstellungen (Default-Typing,
 * Typ-Allowlist, Fehlerverhalten) mit einem verschachtelten {@link TableState} machen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class UserPreferenceTabellenStaendeTest {

    private final SimpleStorableConverter converter = new SimpleStorableConverter();

    @Test
    @DisplayName("Der Getter liefert nie null — auch bei Altbestand ohne das Feld")
    void getterNieNull() throws Exception {
        UserPreference pref = new UserPreference();
        var feld = UserPreference.class.getDeclaredField("tabellenStaende");
        feld.setAccessible(true);
        feld.set(pref, null); // XStream-/Alt-JSON-Bestand nachgestellt

        assertThat(pref.getTabellenStaende()).isNotNull().isEmpty();
        pref.getTabellenStaende().put("guild42/member", new TableState());
        assertThat(pref.getTabellenStaende()).containsKey("guild42/member");
    }

    @Test
    @DisplayName("Ein Stand mit Breiten, Sichtbarkeit und Profil kommt aus der Spalte zurueck, wie er hineinging")
    void rundreiseDurchDenKonverter() {
        TableState stand = new TableState();
        stand.getColumnVisible().put("nr", true);
        stand.getColumnVisible().put("typ", false);
        stand.getColumnWidths().put("nr", "88px");
        stand.setTotalWidth(1200);
        stand.setTargetColumnWidth(140);
        stand.setColsExpanded(true);
        stand.setActiveProfile("Breit");
        TableColumnProfile profil = new TableColumnProfile();
        profil.getColumnWidths().put("nr", "88px");
        profil.getColumnVisible().put("typ", false);
        profil.setTotalWidth(1200);
        stand.getProfiles().put("Breit", profil);

        UserPreference pref = new UserPreference();
        pref.setUniqueId("daniel@plaintext.ch");
        pref.getTabellenSpalten().put("useradmin", List.of("username"));
        pref.getTabellenStaende().put("guild42/guild-member", stand);

        String json = converter.convertToDatabaseColumn(pref);
        assertThat(json).isNotNull().startsWith("{").contains("tabellenStaende").contains("guild42/guild-member");

        SimpleStorable gelesen = converter.convertToEntityAttribute(json);
        assertThat(gelesen).isInstanceOf(UserPreference.class);
        UserPreference zurueck = (UserPreference) gelesen;

        // Das Alte bleibt lesbar ...
        assertThat(zurueck.getTabellenSpalten()).containsEntry("useradmin", List.of("username"));
        // ... und das Neue kommt vollstaendig zurueck.
        TableState geladen = zurueck.getTabellenStaende().get("guild42/guild-member");
        assertThat(geladen).isNotNull();
        assertThat(geladen.getColumnVisible()).containsEntry("nr", true).containsEntry("typ", false);
        assertThat(geladen.getColumnWidths()).containsEntry("nr", "88px");
        assertThat(geladen.getTotalWidth()).isEqualTo(1200);
        assertThat(geladen.getTargetColumnWidth()).isEqualTo(140);
        assertThat(geladen.isColsExpanded()).isTrue();
        assertThat(geladen.getActiveProfile()).isEqualTo("Breit");
        assertThat(geladen.getProfiles()).containsKey("Breit");
        assertThat(geladen.getProfiles().get("Breit").getColumnWidths()).containsEntry("nr", "88px");
        assertThat(geladen.getProfiles().get("Breit").getTotalWidth()).isEqualTo(1200);
    }

    /**
     * Der Mapper des Konverters bricht bei unbekannten Feldern ab und liefert dann {@code null}
     * fuer den ganzen Datensatz. Ein Feld, das eine spaetere root-Version anhaengt, darf diese
     * Version deshalb nicht aus der Bahn werfen — sonst steht der Benutzer nach einem Rollback
     * mit leeren Einstellungen da, und das naechste Speichern ueberschreibt den alten Datensatz.
     */
    @Test
    @DisplayName("Unbekannte Felder (aus einer spaeteren Version) werden ueberlesen statt den Datensatz zu verwerfen")
    void unbekannteFelderWerdenUeberlesen() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("daniel@plaintext.ch");
        TableState stand = new TableState();
        stand.getColumnWidths().put("nr", "88px");
        pref.getTabellenStaende().put("guild42/guild-member", stand);
        String json = converter.convertToDatabaseColumn(pref);

        // Ein Feld am Datensatz und eines am Stand, die es heute nicht gibt.
        String zukunft = json
                .replace("\"tabellenStaende\"", "\"zukunftsFeld\":\"x\",\"tabellenStaende\"")
                .replace("\"columnWidths\"", "\"columnOrder\":[\"nr\"],\"columnWidths\"");
        assertThat(zukunft).isNotEqualTo(json);

        SimpleStorable gelesen = converter.convertToEntityAttribute(zukunft);

        assertThat(gelesen).as("Datensatz mit unbekannten Feldern muss lesbar bleiben").isInstanceOf(UserPreference.class);
        TableState geladen = ((UserPreference) gelesen).getTabellenStaende().get("guild42/guild-member");
        assertThat(geladen).isNotNull();
        assertThat(geladen.getColumnWidths()).containsEntry("nr", "88px");
    }
}
