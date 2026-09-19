/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1257: a browser credential is not an API credential.
 *
 * <h2>What this protects</h2>
 *
 * <p>The watch phone link is a signed token in a URL. Whoever gets the link holds it. Its
 * {@code scope} cannot express "watch pages only": the ladder knows READ/EINTRAGEN/ADMIN, and
 * an unknown value falls back to READ — which at {@code /mcp} is read access to the whole
 * application with the owner's roles (mail, contacts, records). The name prefix says it
 * instead, and {@link McpBearerTokenFilter} enforces it.</p>
 *
 * <p>The counter-checks matter as much as the check: a normal API token and a machine token
 * <b>without</b> a name must keep working. A rule that rejected those would take the clock, the
 * Juriwagen and {@code minten} off the air (card 305).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class McpUiTokenAbweisungTest {

    @Test
    @DisplayName("Ein Browser-Ausweis wird am API abgewiesen")
    void uiTokenAbgewiesen() {
        assertTrue(McpBearerTokenFilter.istNurFuerDieOberflaeche("ui:watch-handy-link"));
    }

    @Test
    @DisplayName("Der Praefix gilt unabhaengig von der Schreibweise und von Leerzeichen")
    void schreibweise() {
        assertTrue(McpBearerTokenFilter.istNurFuerDieOberflaeche("UI:Watch-Handy-Link"));
        assertTrue(McpBearerTokenFilter.istNurFuerDieOberflaeche("  ui:irgendwas"));
    }

    @Test
    @DisplayName("GEGENPROBE: ein normaler API-Token bleibt gueltig")
    void normalerTokenBleibt() {
        // Ohne diese Messung belegt der Test oben nichts — eine Pruefung, die IMMER true
        // liefert, haette jeden API-Token im Haus abgeschaltet.
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche("Mein API-Token"));
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche("Zeiterfassung-Uhr"));
    }

    @Test
    @DisplayName("GEGENPROBE: ein Token ohne Namen bleibt gueltig — Uhr, Juriwagen, minten")
    void tokenOhneNamen() {
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche(null));
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche(""));
    }

    @Test
    @DisplayName("Ein Name, der den Praefix nur enthaelt, ist kein Browser-Ausweis")
    void nurAmAnfang() {
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche("mein-ui:token"));
    }

    @Test
    @DisplayName("Der Praefix steht an einer Stelle, die beide Seiten lesen")
    void praefixIstGeteilt() {
        assertEquals("ui:", IApiTokenService.UI_TOKEN_NAME_PREFIX,
                "aendert er sich, muessen Aussteller und Filter gemeinsam umziehen");
    }
}
