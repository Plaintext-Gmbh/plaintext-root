/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.SettingsKeys;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.repository.SettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1063: der Geltungsbereich <b>global</b> — „ein scope global, gleich wie bei Cron, welcher
 * fuer alle mandate gelten kann" (Daniel, 05.09.2026).
 *
 * <p>Die Regel in einem Satz: <b>gelesen</b> wird mit Rueckfall auf {@code global},
 * <b>geschrieben, geloescht und geprueft</b> wird ausschliesslich beim genannten Mandanten.</p>
 */
@ExtendWith(MockitoExtension.class)
class SettingsGlobalScopeTest {

    @Mock private SettingRepository repository;
    @Mock private PlaintextSecurity security;
    @InjectMocks private SettingsServiceImpl service;

    private static Setting mitWert(String wert) {
        Setting s = new Setting();
        s.setValue(wert);
        return s;
    }

    @Test
    @DisplayName("ohne eigenen Eintrag gilt der globale")
    void globalerRueckfall() {
        when(repository.findByKeyAndMandat("app.ownhost", "guild42")).thenReturn(Optional.empty());
        when(repository.findByKeyAndMandat("app.ownhost", "global"))
                .thenReturn(Optional.of(mitWert("https://alle.example")));

        assertThat(service.getString("app.ownhost", "guild42")).isEqualTo("https://alle.example");
    }

    @Test
    @DisplayName("der eigene Eintrag schlaegt den globalen — global ist Vorgabe, nicht Uebersteuerung")
    void eigenerEintragGewinnt() {
        when(repository.findByKeyAndMandat("app.ownhost", "guild42"))
                .thenReturn(Optional.of(mitWert("https://guild42.example")));

        assertThat(service.getString("app.ownhost", "guild42")).isEqualTo("https://guild42.example");
        // Die globale Zeile wird gar nicht erst gesucht.
        verify(repository, never()).findByKeyAndMandat("app.ownhost", "global");
    }

    @Test
    @DisplayName("gibt es beides nicht, bleibt es null — kein Rueckfall auf sich selbst")
    void nichtsGefunden() {
        when(repository.findByKeyAndMandat("app.ownhost", "global")).thenReturn(Optional.empty());

        assertThat(service.getString("app.ownhost", SettingsKeys.MANDAT_GLOBAL)).isNull();
        // Genau EIN Zugriff: eine Abfrage auf "global", die auf "global" zurueckfaellt, waere
        // eine doppelte Abfrage ohne jeden Nutzen.
        verify(repository).findByKeyAndMandat("app.ownhost", "global");
    }

    @Test
    @DisplayName("ohne angemeldeten Benutzer wird global gelesen statt geworfen")
    void ohneMandantWirdGlobalGelesen() {
        // Der Fall, fuer den es den Geltungsbereich ueberhaupt braucht: Cron-Laeufe und
        // Mailversand haben keinen angemeldeten Benutzer. Vorher endete das in einer
        // IllegalStateException, die EigeneAdresse stillschweigend schluckte.
        when(security.getMandat()).thenReturn("NO_AUTH");
        when(repository.findByKeyAndMandat("app.ownhost", "global"))
                .thenReturn(Optional.of(mitWert("https://alle.example")));

        assertThat(service.getString("app.ownhost")).isEqualTo("https://alle.example");
    }

    @Test
    @DisplayName("ein kaputter Mandant bleibt ein Fehler")
    void fehlerhafterMandantWirftWeiterhin() {
        // ERROR heisst: die Ermittlung ist schiefgegangen. Das still als "global" zu lesen
        // wuerde einen Defekt in eine plausible Antwort verwandeln.
        when(security.getMandat()).thenReturn("ERROR");

        assertThatThrownBy(() -> service.getString("app.ownhost"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("die typisierten Getter erben den Rueckfall")
    void typisierteGetterErbenDenRueckfall() {
        when(repository.findByKeyAndMandat("x.zahl", "guild42")).thenReturn(Optional.empty());
        when(repository.findByKeyAndMandat("x.zahl", "global")).thenReturn(Optional.of(mitWert("42")));
        when(repository.findByKeyAndMandat("x.ja", "guild42")).thenReturn(Optional.empty());
        when(repository.findByKeyAndMandat("x.ja", "global")).thenReturn(Optional.of(mitWert("true")));
        when(repository.findByKeyAndMandat("x.liste", "guild42")).thenReturn(Optional.empty());
        when(repository.findByKeyAndMandat("x.liste", "global")).thenReturn(Optional.of(mitWert("a, b")));

        assertThat(service.getInt("x.zahl", "guild42")).isEqualTo(42);
        assertThat(service.getBoolean("x.ja", "guild42")).isTrue();
        assertThat(service.getList("x.liste", "guild42")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Schreiben, Loeschen und Pruefen fassen den globalen Eintrag NICHT an")
    void schreibwegeBleibenMandantengebunden() {
        when(repository.findByKeyAndMandat("app.ownhost", "guild42")).thenReturn(Optional.empty());
        service.setSetting("app.ownhost", "guild42", "https://guild42.example", "STRING", null);
        service.deleteSetting("app.ownhost", "guild42");
        when(repository.existsByKeyAndMandat("app.ownhost", "guild42")).thenReturn(false);
        boolean vorhanden = service.exists("app.ownhost", "guild42");

        // Sonst loeschte das Entfernen einer mandantenspezifischen Einstellung den gemeinsamen
        // Eintrag mit, und exists() meldete true fuer etwas, das dieser Mandant gar nicht hat.
        assertThat(vorhanden).isFalse();
        verify(repository).deleteByKeyAndMandat("app.ownhost", "guild42");
        verify(repository, never()).deleteByKeyAndMandat(eq("app.ownhost"), eq("global"));
        verify(repository, never()).existsByKeyAndMandat("app.ownhost", "global");
    }
}
