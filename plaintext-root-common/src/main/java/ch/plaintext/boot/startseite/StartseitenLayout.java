/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.startseite;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Karte 1351: wie ein Benutzer sich die Startseite eingerichtet hat — welche Kacheln, in welcher
 * Reihenfolge, halb oder ganz breit.
 *
 * <p>Reines Datenobjekt wie {@link ch.plaintext.boot.table.TableState}: abgelegt als JSON in
 * {@code UserPreference.startseiten}, je Benutzer und Mandant. Die Reihenfolge ist die Reihenfolge
 * der {@link #eintraege}; ein eigenes Feld "Position" gaebe es nur, damit zwei Stellen
 * auseinanderlaufen koennen.</p>
 *
 * <p><b>Was hier NICHT steht: Rechte.</b> Das Layout ist ein Wunsch, keine Erlaubnis. Welche
 * Kacheln der Benutzer sehen darf, entscheidet bei jedem Aufruf {@code TileItemImpl.isOn()};
 * {@link StartseitenAnordnung#anwenden} nimmt aus dem Layout nur, was in der erlaubten Liste
 * steht. Ein Eintrag fuer eine Kachel, deren Recht inzwischen entzogen ist, bleibt deshalb
 * wirkungslos liegen, bis der Benutzer das naechste Mal speichert.</p>
 *
 * <p>{@code ignoreUnknown} aus demselben Grund wie bei {@code UserPreference}: ein Rollback auf
 * eine aeltere root-Version darf den ganzen Datensatz nicht unlesbar machen.</p>
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StartseitenLayout implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Die eingerichteten Kacheln in Anzeigereihenfolge. */
    private List<Eintrag> eintraege = new ArrayList<>();

    public StartseitenLayout(List<Eintrag> eintraege) {
        this.eintraege = eintraege == null ? new ArrayList<>() : new ArrayList<>(eintraege);
    }

    /** Null-sicher: ein JSON ohne das Feld liefert {@code null}, der Initialisierer laeuft dort nicht. */
    public List<Eintrag> getEintraege() {
        if (eintraege == null) {
            eintraege = new ArrayList<>();
        }
        return eintraege;
    }

    /**
     * Eine Kachel im Layout.
     *
     * <p>{@link #halbeBreite} ist die Vorgabe {@code true}: zwei Kacheln je Zeile kommen der
     * bisherigen Startseite (mehrere Kacheln nebeneinander) am naechsten.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Eintrag implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Id der Kachel ({@code @DashboardTile(id)}). */
        private String id;

        /** Ausserhalb des Edit-Modus angezeigt? */
        private boolean sichtbar = true;

        /** Halbe Zeile ({@code true}) oder ganze Zeile ({@code false}). */
        private boolean halbeBreite = true;
    }
}
