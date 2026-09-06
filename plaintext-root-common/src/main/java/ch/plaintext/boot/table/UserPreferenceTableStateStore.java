/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Die mitgelieferte Ablage fuer {@link TableSettings}: der Tabellenstand liegt je Benutzer und
 * Mandant in {@code UserPreference.tabellenStaende} — als JSON in der Spalte, die es schon gibt
 * (Karte 1077).
 *
 * <p><b>Keine Entitaet, kein Repository, keine Migration.</b> {@code UserPreference} wird ueber
 * {@code SimpleStorableConverter} als JSON abgelegt; ein neues Feld ist fuer Bestandsdaten
 * unkritisch. Jede App, die root einbindet, hat diese Ablage damit ohne eine Zeile Code.</p>
 *
 * <p><b>Warum ueber die Session-Bean und nicht direkt ueber {@code UserPrefsSimpleStorage}.</b>
 * {@link UserPreferencesBackingBean} haelt das {@code UserPreference}-Objekt des Benutzers fuer
 * die Dauer der Session im Speicher und schreibt es bei jeder Aenderung (Theme, Sprache, ...) als
 * Ganzes weg. Wer daneben direkt in die Datenbank schriebe, verloere seinen Stand beim naechsten
 * Theme-Wechsel wieder — der Umweg ueber die Bean ist der einzige Weg ohne verlorene Updates.
 * Die Bean ist {@code @Scope("session")} mit {@code ScopedProxyMode.TARGET_CLASS} und laesst
 * sich deshalb in dieses Singleton injizieren; die Aufloesung auf die Session passiert beim
 * Aufruf.</p>
 *
 * <p><b>Schluessel: {@code mandat + "/" + seite}.</b> Derselbe Benutzer arbeitet in app und
 * guild in mehreren Mandanten; seine Spaltenwahl gilt je Mandant (Entscheid 3 der Karte 1077).
 * Nachtraeglich waere das ein Bruch gespeicherter Staende, jetzt kostet es nichts.</p>
 *
 * <p><b>Altbestand.</b> {@code UserPreference.tabellenSpalten} (Liste der <i>sichtbaren</i>
 * Spalten je Tabelle, ohne Mandant, seit 25.08.2026 produktiv fuer {@code useradmin}) wird
 * beim Lesen uebernommen, wenn unter dem neuen Schluessel noch nichts liegt: eine sichtbare
 * Spalte wird {@code true}, jede andere bekannte Spalte {@code false} — die leere Liste heisst
 * dort ausdruecklich "keine Spalte", nicht "Vorgabe". Die Uebernahme landet beim aktuellen
 * Mandanten und wird mit dem ersten Speichern dauerhaft; der alte Eintrag bleibt stehen, damit
 * eine aeltere root-Version ihn weiterhin lesen kann.</p>
 *
 * <p><b>Nach einer Session-Deserialisierung</b> ist das {@code prefs}-Feld der Bean leer:
 * {@link #load} liefert dann einen frischen Stand und {@link #save} tut nichts — der Benutzer
 * sieht bis zur naechsten Anmeldung die Vorgaben, verliert aber nichts. Dasselbe Verhalten hat
 * {@code tabellenSpalten} seit dem 28.08.2026.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
public class UserPreferenceTableStateStore implements TableStateStore {

    private final UserPreferencesBackingBean preferences;

    private final PlaintextSecurity security;

    public UserPreferenceTableStateStore(UserPreferencesBackingBean preferences, PlaintextSecurity security) {
        this.preferences = preferences;
        this.security = security;
    }

    @Override
    public TableState load(String page) {
        return load(page, List.of());
    }

    @Override
    public TableState load(String page, List<String> columnKeys) {
        String key = schluessel(page);
        TableState gespeichert = preferences.tabellenStand(key);
        if (gespeichert != null) {
            return gespeichert;
        }
        TableState state = new TableState();
        List<String> alt = preferences.tabellenSpalten(page);
        if (alt != null) {
            List<String> keys = columnKeys == null ? List.of() : columnKeys;
            for (String columnKey : keys) {
                state.getColumnVisible().put(columnKey, alt.contains(columnKey));
            }
            log.info("[TableStateStore] Altbestand uebernommen | key={} | sichtbar={} | spalten={}",
                    key, alt.size(), keys.size());
        }
        return state;
    }

    @Override
    public void save(String page, TableState state) {
        preferences.merkeTabellenStand(schluessel(page), state);
    }

    /**
     * Der vollstaendige Ablageschluessel. Ein fehlender Mandant (kein angemeldeter Benutzer,
     * Test ohne Security) ergibt {@code "/" + page} — ein gueltiger, eigener Schluessel statt
     * einer NPE.
     */
    String schluessel(String page) {
        String mandat = security == null ? null : security.getMandat();
        return (mandat == null ? "" : mandat) + "/" + page;
    }
}
