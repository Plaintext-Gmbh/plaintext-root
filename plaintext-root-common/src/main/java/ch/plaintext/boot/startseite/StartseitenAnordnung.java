/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.startseite;

import ch.plaintext.DashboardTileData;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Karte 1351: bringt die erlaubten Kacheln in die Anordnung des Benutzers — und liest die
 * Anordnung aus dem Formular zurueck.
 *
 * <p><b>Die Rechte bleiben beim Kachel-Modell.</b> Eingabe ist immer die Liste, die
 * {@code DashboardTileModelBuilder} fuer DIESEN Aufruf gebaut hat, also nur Kacheln, deren
 * {@code isOn()} wahr ist (Rollen, Modulrolle, Menue-Sichtbarkeit des Mandanten). Beide Richtungen
 * filtern dagegen:</p>
 * <ul>
 *   <li>{@link #anwenden}: ein gespeicherter Eintrag ohne erlaubte Kachel wird uebersprungen —
 *       wer das Modulrecht verliert, verliert die Kachel, egal was im Layout steht.</li>
 *   <li>{@link #ausFormular}: eine Id, die nicht in der erlaubten Liste steht, wird verworfen —
 *       ein von Hand gebautes Formular kann keine fremde Kachel in den Datensatz schreiben.</li>
 * </ul>
 *
 * <p><b>Neue Kacheln</b> (ein Modul kommt dazu, ein Recht wird erteilt) haengen hinten an,
 * sichtbar und halb breit. Waeren sie standardmaessig ausgeblendet, fiele eine neue Kachel
 * niemandem auf, der die Startseite einmal eingerichtet hat.</p>
 *
 * <p>Reine Funktionen ohne Spring und ohne Faces, damit die Regeln ohne Container pruefbar sind.</p>
 */
public final class StartseitenAnordnung {

    /** Trenner zwischen zwei Kacheln im Formularwert. */
    static final String TRENNER_KACHEL = ",";

    /** Trenner zwischen Id, Sichtbarkeit und Breite einer Kachel im Formularwert. */
    static final String TRENNER_FELD = ":";

    private StartseitenAnordnung() {
    }

    /**
     * Ordnet die erlaubten Kacheln nach dem Layout und setzt {@code hidden}/{@code halfWidth}.
     *
     * @param erlaubte die Kacheln, die der Benutzer jetzt sehen darf, in Standardreihenfolge
     * @param layout   das gespeicherte Layout oder {@code null} fuer "nie eingerichtet"
     * @return neue Liste (die Eingabe bleibt unveraendert in ihrer Reihenfolge); die Kachel-Objekte
     *         selbst werden mit den Flags versehen
     */
    public static List<DashboardTileData> anwenden(List<DashboardTileData> erlaubte, StartseitenLayout layout) {
        List<DashboardTileData> ergebnis = new ArrayList<>();
        if (erlaubte == null || erlaubte.isEmpty()) {
            return ergebnis;
        }
        Map<String, DashboardTileData> nachId = new LinkedHashMap<>();
        for (DashboardTileData kachel : erlaubte) {
            if (kachel != null && kachel.getId() != null) {
                nachId.putIfAbsent(kachel.getId(), kachel);
            }
        }

        Set<String> platziert = new HashSet<>();
        if (layout != null) {
            for (StartseitenLayout.Eintrag eintrag : layout.getEintraege()) {
                DashboardTileData kachel = eintrag == null ? null : nachId.get(eintrag.getId());
                if (kachel == null || !platziert.add(eintrag.getId())) {
                    continue; // nicht (mehr) erlaubt, oder doppelt im Datensatz
                }
                kachel.setHidden(!eintrag.isSichtbar());
                kachel.setHalfWidth(eintrag.isHalbeBreite());
                ergebnis.add(kachel);
            }
        }

        for (DashboardTileData kachel : erlaubte) {
            if (kachel == null || (kachel.getId() != null && platziert.contains(kachel.getId()))) {
                continue;
            }
            kachel.setHidden(false);
            kachel.setHalfWidth(true);
            ergebnis.add(kachel);
        }
        return ergebnis;
    }

    /**
     * Die Ids der Kacheln, die laut Layout ausgeblendet sind — sie muessen ausserhalb des
     * Edit-Modus nicht angereichert werden (jeder Provider fragt seine Datenbank ab).
     */
    public static Set<String> ausgeblendeteIds(StartseitenLayout layout) {
        Set<String> ids = new HashSet<>();
        if (layout == null) {
            return ids;
        }
        for (StartseitenLayout.Eintrag eintrag : layout.getEintraege()) {
            if (eintrag != null && eintrag.getId() != null && !eintrag.isSichtbar()) {
                ids.add(eintrag.getId());
            }
        }
        return ids;
    }

    /**
     * Der Formularwert fuer die aktuelle Anordnung: {@code id:1:1,id:0:1,…} — Sichtbarkeit und
     * halbe Breite als 1/0, die Id URL-kodiert (sie stammt aus einer Annotation, aber ein
     * Komma oder Doppelpunkt darin soll das Format nicht zerlegen). Das Skript der Seite schreibt
     * denselben Aufbau nach jedem Ziehen und Umschalten zurueck.
     */
    public static String zuFormular(List<DashboardTileData> kacheln) {
        if (kacheln == null) {
            return "";
        }
        List<String> teile = new ArrayList<>();
        for (DashboardTileData kachel : kacheln) {
            if (kachel == null || kachel.getId() == null) {
                continue;
            }
            teile.add(URLEncoder.encode(kachel.getId(), StandardCharsets.UTF_8)
                    + TRENNER_FELD + (kachel.isHidden() ? "0" : "1")
                    + TRENNER_FELD + (kachel.isHalfWidth() ? "1" : "0"));
        }
        return String.join(TRENNER_KACHEL, teile);
    }

    /**
     * Liest den Formularwert zurueck.
     *
     * @param wert        Wert des versteckten Feldes, Aufbau wie {@link #zuFormular}
     * @param erlaubteIds die Ids, die der Benutzer jetzt sehen darf
     * @return das Layout, oder {@code null} wenn der Wert leer ist (ohne Skript abgeschickt:
     *         dann gibt es nichts zu speichern, und der bestehende Stand bleibt)
     */
    public static StartseitenLayout ausFormular(String wert, Collection<String> erlaubteIds) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        Set<String> erlaubt = erlaubteIds == null ? Set.of() : new HashSet<>(erlaubteIds);
        Set<String> gesehen = new HashSet<>();
        List<StartseitenLayout.Eintrag> eintraege = new ArrayList<>();
        for (String teil : wert.split(TRENNER_KACHEL)) {
            String[] felder = teil.trim().split(TRENNER_FELD);
            if (felder.length != 3) {
                continue;
            }
            String id;
            try {
                id = URLDecoder.decode(felder[0], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!erlaubt.contains(id) || !gesehen.add(id)) {
                continue;
            }
            eintraege.add(new StartseitenLayout.Eintrag(id, "1".equals(felder[1]), "1".equals(felder[2])));
        }
        return new StartseitenLayout(eintraege);
    }
}
