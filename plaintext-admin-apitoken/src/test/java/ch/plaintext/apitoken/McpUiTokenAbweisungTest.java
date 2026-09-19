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
 * <b>without</b> a name must keep working. A rule that rejected those would take the Juriwagen
 * and {@code minten} off the air (card 305).</p>
 *
 * <p><b>Card 1279 (19.09.2026):</b> that sentence used to name "the clock" first. The old
 * time-tracking clock at {@code /nosec/uhr.html} is gone — its link generation with card 1277,
 * the page itself with this card. What replaced it is the watch page behind the sign-in, which
 * takes its identity from the security context and not from a token in a URL. The counter-check
 * below no longer uses that token as its example.</p>
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
        //
        // KARTE 1279 (19.09.2026): hier stand als zweites Beispiel "Zeiterfassung-Uhr". Die
        // Aussage war nie falsch — der Name traegt kein ui:-Praefix, wird also nicht gefangen.
        // Als BEISPIEL fuehrte er aber in die Irre: er zeigte einen Token, der weiterhin am API
        // gilt, so als waere das ein erwuenschter Zustand. Karte 1275 hat gemessen, dass genau
        // dieser Token mit scope WRITE am /mcp ein schreibender Vollzugang auf die ganze
        // Anwendung war, nicht nur auf die Uhr. Ausgestellt wird er seit Karte 1277 nicht mehr,
        // die zugehoerige Seite ist seit Karte 1279 weg.
        //
        // Ein Test, der einen stillgelegten Token als Muster fuer "so soll es sein" fuehrt, ist
        // eine Falle fuer den naechsten Leser. Deshalb zwei Namen, die es wirklich noch gibt.
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche("Mein API-Token"));
        assertFalse(McpBearerTokenFilter.istNurFuerDieOberflaeche("mcpZorin01"));
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
