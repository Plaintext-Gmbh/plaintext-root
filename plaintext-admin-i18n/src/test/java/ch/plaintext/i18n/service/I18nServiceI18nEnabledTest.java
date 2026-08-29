/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.service;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.i18n.repository.I18nTranslationRepository;
import ch.plaintext.settings.ISettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Auftrag Daniel, 29.08.2026: Der Setup-Schalter „Sprachwechsel anzeigen" ({@code branding.i18n.enabled}
 * je Mandant) muss den Sprachwechsel wirklich abschalten. Vorher las {@code isI18nEnabled()} nur
 * den alten globalen Schluessel {@code i18n.enabled} — das Topbar-Symbol blieb trotz „aus".
 */
@DisplayName("I18nService.isI18nEnabled: Setup-Schalter des Mandanten gilt")
class I18nServiceI18nEnabledTest {

    private ISettingsService settings;
    private I18nService service;
    private MockedStatic<PlaintextSecurityHolder> holder;

    @BeforeEach
    void setUp() {
        settings = mock(ISettingsService.class);
        service = new I18nService(mock(I18nTranslationRepository.class));
        ReflectionTestUtils.setField(service, "settingsService", settings);
        holder = mockStatic(PlaintextSecurityHolder.class);
        holder.when(PlaintextSecurityHolder::getMandat).thenReturn("guild42");
    }

    @AfterEach
    void tearDown() {
        holder.close();
    }

    @Test
    @DisplayName("Im Setup ausgeschaltet: aus — der alte globale Schluessel wird gar nicht gefragt")
    void setupAusGewinnt() {
        when(settings.getBoolean("branding.i18n.enabled", "guild42")).thenReturn(false);

        assertFalse(service.isI18nEnabled());
        verify(settings, never()).getBoolean("i18n.enabled");
    }

    @Test
    @DisplayName("Im Setup eingeschaltet: an, auch wenn der alte Schluessel aus sagt")
    void setupAnGewinnt() {
        when(settings.getBoolean("branding.i18n.enabled", "guild42")).thenReturn(true);
        when(settings.getBoolean("i18n.enabled")).thenReturn(false);

        assertTrue(service.isI18nEnabled());
    }

    @Test
    @DisplayName("Ohne Setup-Wert zaehlt der alte Schluessel")
    void ohneSetupWertAlterSchluessel() {
        when(settings.getBoolean("branding.i18n.enabled", "guild42")).thenReturn(null);
        when(settings.getBoolean("i18n.enabled")).thenReturn(false);

        assertFalse(service.isI18nEnabled());
    }

    @Test
    @DisplayName("Ohne jeden Wert: an (Standard)")
    void ohneWerteAn() {
        // Ausdruecklich null: ein Mockito-Mock liefert fuer Boolean sonst false, nicht "kein Wert".
        when(settings.getBoolean("branding.i18n.enabled", "guild42")).thenReturn(null);
        when(settings.getBoolean("i18n.enabled")).thenReturn(null);

        assertTrue(service.isI18nEnabled());
    }

    @Test
    @DisplayName("Ohne Mandant (z.B. Login-Seite): nur der alte Schluessel, sonst an")
    void ohneMandant() {
        holder.when(PlaintextSecurityHolder::getMandat).thenReturn(null);
        when(settings.getBoolean("i18n.enabled")).thenReturn(false);

        assertFalse(service.isI18nEnabled());
        verify(settings, never()).getBoolean("branding.i18n.enabled", null);
    }

    @Test
    @DisplayName("Ohne SettingsService: an")
    void ohneSettingsService() {
        ReflectionTestUtils.setField(service, "settingsService", null);
        assertTrue(service.isI18nEnabled());
    }
}
