/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.mcp;

import ch.plaintext.settings.ISettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1050 — the settings MCP interface.
 *
 * <p>The point of these tests is not that the happy path works; it is that <b>nothing else does</b>.
 * Settings steer the public address, mail accounts and feature switches of the whole installation,
 * so every gate is checked from the outside: no scope, wrong scope, right scope but wrong role, and
 * no tenant. Each of them must refuse <b>and</b> leave the store untouched — a refusal that still
 * writes would be worse than no gate at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsMcpToolsTest {

    @Mock private ISettingsService settingsService;

    private SettingsMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new SettingsMcpTools(settingsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void anmelden(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anna@example.ch", null,
                        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private void alsAdmin() {
        anmelden("SCOPE_ADMIN", "ROLE_ADMIN", "TOKEN_MANDAT_plaintext");
    }

    // ── Der erlaubte Weg ────────────────────────────────────────────────────

    @Test
    @DisplayName("Mit Admin-Token und Admin-Rolle laesst sich eine Einstellung setzen")
    void adminDarfSetzen() {
        alsAdmin();
        when(settingsService.getString("app.ownhost", "plaintext")).thenReturn(null);

        String antwort = tools.setSetting("app.ownhost", "https://app.plaintext.ch", "STRING", "Eigene Adresse");

        assertThat(antwort).contains("angelegt");
        verify(settingsService).setSetting("app.ownhost", "plaintext",
                "https://app.plaintext.ch", "STRING", "Eigene Adresse");
    }

    @Test
    @DisplayName("Ein vorhandener Wert wird als Aenderung gemeldet, nicht als Neuanlage")
    void aenderungWirdBenannt() {
        alsAdmin();
        // Karte 1063: gemessen wird am EIGENEN Eintrag (exists), nicht am gelesenen Wert —
        // getString faellt seit dem Geltungsbereich "global" auf den gemeinsamen Eintrag zurueck.
        when(settingsService.exists("app.ownhost", "plaintext")).thenReturn(true);

        assertThat(tools.setSetting("app.ownhost", "https://app.plaintext.ch", null, null))
                .contains("geaendert");
    }

    @Test
    @DisplayName("Ein bloss GLOBAL gesetzter Schluessel ist fuer diesen Mandanten eine Neuanlage")
    void globalerEintragIstKeineAenderung() {
        alsAdmin();
        // Der Rueckfall liefert einen Wert, aber der Mandant hat noch keinen eigenen. Mit
        // getString haette hier "geaendert" gestanden, obwohl gerade der erste eigene entsteht.
        when(settingsService.getString("app.ownhost", "plaintext")).thenReturn("https://global.example");
        when(settingsService.exists("app.ownhost", "plaintext")).thenReturn(false);

        assertThat(tools.setSetting("app.ownhost", "https://eigen.example", null, null))
                .contains("angelegt");
    }

    @Test
    @DisplayName("Loeschen fasst einen bloss globalen Eintrag nicht an — und meldet es")
    void loeschenTastetGlobalenEintragNichtAn() {
        alsAdmin();
        // Der Kollateralschaden, den der Rueckfall sonst erzeugt haette: getString liefert den
        // globalen Wert, deleteSetting(key, mandat) findet nichts, und der Aufrufer bekaeme ein
        // "OK: geloescht" fuer einen Eintrag, der unveraendert weitergilt.
        when(settingsService.getString("app.ownhost", "plaintext")).thenReturn("https://global.example");
        when(settingsService.exists("app.ownhost", "plaintext")).thenReturn(false);

        assertThat(tools.deleteSetting("app.ownhost"))
                .contains("nichts geloescht")
                .contains("global");
        verify(settingsService, never()).deleteSetting("app.ownhost", "plaintext");
    }

    @Test
    @DisplayName("Ein globaler Wert wird beim Lesen als solcher ausgewiesen")
    void globalerWertWirdBenannt() {
        alsAdmin();
        when(settingsService.getString("app.ownhost", "plaintext")).thenReturn("https://global.example");
        when(settingsService.exists("app.ownhost", "plaintext")).thenReturn(false);

        assertThat(tools.getSetting("app.ownhost"))
                .contains("https://global.example")
                .contains("gilt fuer alle Mandanten");
    }

    @Test
    @DisplayName("Ohne Typangabe gilt STRING")
    void ohneTypIstString() {
        alsAdmin();

        tools.setSetting("a.b", "wert", null, null);

        verify(settingsService).setSetting("a.b", "plaintext", "wert", "STRING", null);
    }

    // ── Die Gates ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GEGENPROBE: ohne Anmeldung wird nichts gelesen und nichts geschrieben")
    void ohneAnmeldungNichts() {
        assertThat(tools.setSetting("a.b", "x", null, null)).contains("nicht authentisiert");
        assertThat(String.valueOf(tools.listSettings())).contains("nicht authentisiert");
        assertThat(tools.getSetting("a.b")).contains("nicht authentisiert");
        assertThat(tools.deleteSetting("a.b")).contains("nicht authentisiert");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
        verify(settingsService, never()).deleteSetting(anyString(), anyString());
    }

    @Test
    @DisplayName("GEGENPROBE: ein Token mit SCOPE_WRITE genuegt nicht — auch nicht zum Lesen")
    void schreibtokenGenuegtNicht() {
        anmelden("SCOPE_WRITE", "ROLE_ADMIN", "TOKEN_MANDAT_plaintext");

        assertThat(tools.setSetting("a.b", "x", null, null)).contains("scope=ADMIN");
        assertThat(String.valueOf(tools.listSettings())).contains("scope=ADMIN");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("GEGENPROBE: Admin-Scope OHNE Admin-Rolle genuegt nicht")
    void scopeAlleinGenuegtNicht() {
        anmelden("SCOPE_ADMIN", "ROLE_USER", "TOKEN_MANDAT_plaintext");

        assertThat(tools.setSetting("a.b", "x", null, null)).contains("Rolle ADMIN oder ROOT");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("GEGENPROBE: ohne bestimmbaren Mandanten wird nichts geschrieben")
    void ohneMandantNichts() {
        anmelden("SCOPE_ADMIN", "ROLE_ADMIN");

        assertThat(tools.setSetting("a.b", "x", null, null)).contains("Mandant");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Die ROOT-Rolle darf ebenfalls")
    void rootDarfAuch() {
        anmelden("SCOPE_ADMIN", "ROLE_ROOT", "TOKEN_MANDAT_plaintext");

        assertThat(tools.setSetting("a.b", "x", null, null)).startsWith("OK");
    }

    // ── Eingaben ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ein unbekannter Wertetyp wird abgelehnt, statt spaeter beim Lesen zu scheitern")
    void unbekannterTypAbgelehnt() {
        alsAdmin();

        assertThat(tools.setSetting("a.b", "x", "ZAHL", null)).contains("Unbekannter Typ");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Ein leerer Schluessel wird abgelehnt")
    void leererSchluesselAbgelehnt() {
        alsAdmin();

        assertThat(tools.setSetting("  ", "x", null, null)).contains("Schluessel");
        verify(settingsService, never()).setSetting(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Loeschen meldet ehrlich, wenn es nichts zu loeschen gab")
    void loeschenOhneTreffer() {
        alsAdmin();
        when(settingsService.getString("a.b", "plaintext")).thenReturn(null);

        assertThat(tools.deleteSetting("a.b")).contains("nichts geloescht");
        verify(settingsService, never()).deleteSetting(anyString(), anyString());
    }

    @Test
    @DisplayName("list_settings liefert Schluessel und Werte des eigenen Mandanten")
    void listeLiefertEigenenMandanten() {
        alsAdmin();
        when(settingsService.getAllKeys("plaintext")).thenReturn(List.of("app.ownhost", "i18n.enabled"));
        when(settingsService.getString("app.ownhost", "plaintext")).thenReturn("https://app.plaintext.ch");
        when(settingsService.getString("i18n.enabled", "plaintext")).thenReturn("false");

        Object antwort = tools.listSettings();

        assertThat(antwort).isInstanceOf(List.class);
        assertThat(String.valueOf(antwort)).contains("app.ownhost").contains("https://app.plaintext.ch");
    }
}
