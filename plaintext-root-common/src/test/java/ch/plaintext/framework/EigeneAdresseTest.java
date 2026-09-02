/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1046: die eigene Adresse, an einer Stelle aufgeloest.
 *
 * <p>Diese Klasse entscheidet ueber jeden Link, den eine Anwendung nach draussen gibt — in Mails,
 * in oeffentlichen JSON-Antworten, in Kalender-Abonnements. Ein Fehler faellt hier nicht auf,
 * sondern zeigt sich Tage spaeter als toter Link beim Empfaenger. Deshalb steht jede Stufe der
 * Rangfolge einzeln, und besonders die beiden Faelle, in denen etwas fehlt.
 */
class EigeneAdresseTest {

    private static final String VORGABE = "https://app.plaintext.ch";

    private final ISettingsService settings = mock(ISettingsService.class);
    private MockedStatic<PlaintextSecurityHolder> holder;

    @AfterEach
    void aufraeumen() {
        if (holder != null) {
            holder.close();
            holder = null;
        }
    }

    /** Baut die Bohne; {@code service == null} stellt „Settings-Modul fehlt" nach. */
    private EigeneAdresse bohne(ISettingsService service, String ausKonfiguration) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ISettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        EigeneAdresse a = new EigeneAdresse(provider);
        ReflectionTestUtils.setField(a, "ausKonfiguration", ausKonfiguration);
        return a;
    }

    private void mandat(String m) {
        holder = mockStatic(PlaintextSecurityHolder.class);
        if (m == null) {
            holder.when(PlaintextSecurityHolder::getMandat)
                    .thenThrow(new IllegalStateException("kein Security-Kontext"));
        } else {
            holder.when(PlaintextSecurityHolder::getMandat).thenReturn(m);
        }
    }

    // ---------------------------------------------------------------- Rangfolge

    @Test
    @DisplayName("Die mandantenspezifische Einstellung gewinnt vor allem anderen")
    void mandantSchlaegtAlles() {
        mandat("plaintext");
        when(settings.getString(SettingsKeys.APP_OWNHOST, "plaintext")).thenReturn("https://mandant.example");

        assertThat(bohne(settings, "https://konfig.example").basis(VORGABE))
                .isEqualTo("https://mandant.example");
    }

    @Test
    @DisplayName("Ohne mandantenspezifischen Wert gilt die globale Einstellung")
    void globalVorKonfiguration() {
        mandat("plaintext");
        when(settings.getString(SettingsKeys.APP_OWNHOST, "plaintext")).thenReturn(null);
        when(settings.getString(SettingsKeys.APP_OWNHOST)).thenReturn("https://global.example");

        assertThat(bohne(settings, "https://konfig.example").basis(VORGABE))
                .isEqualTo("https://global.example");
    }

    @Test
    @DisplayName("Ohne Einstellung gilt die Konfiguration, ohne beides die Vorgabe des Aufrufers")
    void konfigurationDannVorgabe() {
        mandat("plaintext");
        when(settings.getString(SettingsKeys.APP_OWNHOST, "plaintext")).thenReturn(null);
        when(settings.getString(SettingsKeys.APP_OWNHOST)).thenReturn(null);

        assertThat(bohne(settings, "https://konfig.example").basis(VORGABE))
                .isEqualTo("https://konfig.example");
        assertThat(bohne(settings, "").basis(VORGABE))
                .as("leere Property zaehlt als nicht gesetzt")
                .isEqualTo(VORGABE);
    }

    // ---------------------------------------------------------------- Wenn etwas fehlt

    @Test
    @DisplayName("Ohne Security-Kontext wird der GLOBALE Wert trotzdem gelesen")
    void ohneMandantWirdGlobalGelesen() {
        // Der Fehler, der am 01.09.2026 im PaperlessClient steckte: lag die Mandantenabfrage im
        // selben try wie die globale, verschluckte deren Ausnahme den globalen Wert — und in
        // jedem Hintergrund-Job galt stillschweigend die Vorgabe.
        mandat(null);
        when(settings.getString(SettingsKeys.APP_OWNHOST)).thenReturn("https://global.example");

        assertThat(bohne(settings, null).basis(VORGABE)).isEqualTo("https://global.example");
        verify(settings, never()).getString(SettingsKeys.APP_OWNHOST, null);
    }

    @Test
    @DisplayName("Ohne Settings-Modul faellt es still auf Konfiguration und Vorgabe zurueck")
    void ohneSettingsModul() {
        assertThat(bohne(null, "https://konfig.example").basis(VORGABE))
                .isEqualTo("https://konfig.example");
        assertThat(bohne(null, null).basis(VORGABE)).isEqualTo(VORGABE);
    }

    @Test
    @DisplayName("Eine werfende Settings-Abfrage darf keinen Link verhindern")
    void werfendeAbfrageWirdGeschluckt() {
        mandat("plaintext");
        when(settings.getString(SettingsKeys.APP_OWNHOST, "plaintext"))
                .thenThrow(new IllegalStateException("Settings-Tabelle fehlt"));

        assertThat(bohne(settings, "https://konfig.example").basis(VORGABE))
                .as("eine Ausnahme beim Mailversand waere schlimmer als eine alte Adresse")
                .isEqualTo("https://konfig.example");
    }

    // ---------------------------------------------------------------- Form

    @Test
    @DisplayName("Abschliessende Schraegstriche fallen weg — auch mehrere")
    void endSlashFaelltWeg() {
        mandat("plaintext");
        when(settings.getString(SettingsKeys.APP_OWNHOST, "plaintext"))
                .thenReturn("  https://mit.example///  ");

        assertThat(bohne(settings, null).basis(VORGABE)).isEqualTo("https://mit.example");
    }

    @Test
    @DisplayName("ohneEndSlash ist auch einzeln benutzbar und vertraegt null")
    void helferEinzeln() {
        assertThat(EigeneAdresse.ohneEndSlash("https://a.example/")).isEqualTo("https://a.example");
        assertThat(EigeneAdresse.ohneEndSlash("https://a.example")).isEqualTo("https://a.example");
        assertThat(EigeneAdresse.ohneEndSlash(null)).isNull();
    }
}
