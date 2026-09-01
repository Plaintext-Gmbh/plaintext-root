/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Haelt die Schluesselnamen fest. Ein Tippfehler oder eine stille Umbenennung faellt sonst erst
 * auf, wenn eine Instanz die gepflegte Einstellung nicht mehr findet und kommentarlos auf die
 * Vorgabe zurueckfaellt.
 */
class SettingsKeysTest {

    @Test
    void paperlessSchluesselIstStabil() {
        assertEquals("paperless.url", SettingsKeys.PAPERLESS_URL);
    }

    @Test
    void paperlessVorgabeZeigtAufDieErreichbareInstanz() {
        assertEquals("https://paperless.plaintext.ch", SettingsKeys.PAPERLESS_URL_DEFAULT,
                "paper.plaintext.ch war die alte, nicht mehr erreichbare Adresse");
        assertFalse(SettingsKeys.PAPERLESS_URL_DEFAULT.endsWith("/"),
                "ohne abschliessenden Schraegstrich, die Verbraucher haengen den Pfad direkt an");
    }

    @Test
    void i18nSchluesselBleibenUnveraendert() {
        assertEquals("branding.i18n.enabled", SettingsKeys.I18N_ENABLED);
        assertEquals("i18n.enabled", SettingsKeys.I18N_ENABLED_LEGACY);
    }

    @Test
    void klasseIstNichtInstanziierbar() throws Exception {
        Constructor<SettingsKeys> c = SettingsKeys.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(c.getModifiers()));
    }
}
