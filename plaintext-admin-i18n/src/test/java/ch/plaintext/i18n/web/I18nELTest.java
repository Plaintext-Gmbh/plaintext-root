/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.I18nProvider;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026 (R2): {@link I18nEL} obtains the user language type-safely from the
 * scoped proxy of {@link UserPreferencesBackingBean} instead of by reflection on every call.
 * The tests cover the three paths: language present, proxy without a session (throws), no bean.
 */
class I18nELTest {

    private final I18nProvider provider = mock(I18nProvider.class);
    private final UserPreferencesBackingBean prefs = mock(UserPreferencesBackingBean.class);
    private final I18nEL el = new I18nEL();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(el, "i18nProvider", provider);
        ReflectionTestUtils.setField(el, "userPreferences", prefs);
        when(provider.isI18nEnabled()).thenReturn(true);
        when(provider.translate(anyString(), anyString())).thenAnswer(a -> a.getArgument(0) + "/" + a.getArgument(1));
    }

    @Test
    void uebersetztInDieSpracheAusDenBenutzereinstellungen() {
        when(prefs.getLanguage()).thenReturn("en");

        assertEquals("Speichern/en", el.t("Speichern"));
    }

    @Test
    void deutschAlsBenutzerspracheLiefertDenVorgabetextOhneUebersetzung() {
        when(prefs.getLanguage()).thenReturn("de");

        assertEquals("Speichern", el.t("Speichern"));
        verify(provider, never()).translate(any(), any());
    }

    @Test
    void ohneAktiveSessionWirftDerScopedProxyUndEsBleibtBeiDeutsch() {
        // This is how the TARGET_CLASS proxy behaves outside an HTTP session (cron, REST).
        when(prefs.getLanguage()).thenThrow(new IllegalStateException("No thread-bound request found"));

        assertEquals("Speichern", el.t("Speichern"));
        verify(provider, never()).translate(any(), any());
    }

    @Test
    void ohneBeanUndOhneSpracheBleibtEsBeiDeutsch() {
        ReflectionTestUtils.setField(el, "userPreferences", null);
        assertEquals("de", el.resolveUserLanguage());

        ReflectionTestUtils.setField(el, "userPreferences", prefs);
        when(prefs.getLanguage()).thenReturn("  ");
        assertEquals("de", el.resolveUserLanguage());
    }

    @Test
    void explizitesZielIgnoriertDieBenutzereinstellungen() {
        when(prefs.getLanguage()).thenReturn("en");

        assertEquals("Speichern/fr", el.t("Speichern", "fr"));
        verify(prefs, never()).getLanguage();
    }
}
