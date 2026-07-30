/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.service.I18nService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SECURITY-Karte 304: {@code /api/i18n/export} und {@code /api/i18n/import} waren nur
 * {@code authenticated()} — ein beliebiger {@code ROLE_USER} konnte damit global (die Entity hat
 * keine {@code mandat}-Spalte) Uebersetzungen ueberschreiben, die anschliessend auf Admin-Seiten
 * gerendert werden, bzw. alle Labels exportieren.
 * <p>
 * Dieser Test prueft die <b>zweite Verteidigungslinie im Controller</b> ohne Servlet-Container.
 * Die Filter-Chain-Regel ({@code /api/i18n/** -> hasAnyRole("ADMIN","ROOT")}) wird zusaetzlich
 * end-to-end in {@code SecurityTest} (plaintext-root-webapp) abgedeckt.
 */
class I18nExportControllerAuthorizationTest {

    private static final String CSV = "defaultLabel;languageCode;translatedText\nBenutzername;en;Username\n";

    private I18nService i18nService;
    private I18nExportController controller;

    @BeforeEach
    void setUp() {
        i18nService = mock(I18nService.class);
        controller = new I18nExportController(i18nService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void alsBenutzerMitRollen(String username, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a",
                        AuthorityUtils.createAuthorityList(authorities)));
    }

    private MockMultipartFile csvDatei(String inhalt) {
        return new MockMultipartFile("file", "i18n.csv", "text/csv",
                inhalt.getBytes(StandardCharsets.UTF_8));
    }

    // --- Import ---------------------------------------------------------------------------

    @Test
    void importAlsNormalerUserWirdMit403Abgelehnt() {
        alsBenutzerMitRollen("bob", "ROLE_USER");

        ResponseEntity<?> response = controller.importCsv(csvDatei(CSV));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER darf keine Uebersetzungen importieren");
        verify(i18nService, never()).saveTranslation(anyString(), anyString(), anyString());
    }

    @Test
    void importOhneAuthentifizierungWirdMit403Abgelehnt() {
        ResponseEntity<?> response = controller.importCsv(csvDatei(CSV));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(i18nService, never()).saveTranslation(anyString(), anyString(), anyString());
    }

    @Test
    void importAlsAnonymerPrincipalWirdMit403Abgelehnt() {
        // AnonymousAuthenticationToken ist isAuthenticated()==true, traegt aber nur ROLE_ANONYMOUS
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        ResponseEntity<?> response = controller.importCsv(csvDatei(CSV));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(i18nService, never()).saveTranslation(anyString(), anyString(), anyString());
    }

    @Test
    void importAlsAdminFunktioniert() {
        alsBenutzerMitRollen("admin", "ROLE_ADMIN", "ROLE_USER");
        when(i18nService.saveTranslation(anyString(), anyString(), anyString()))
                .thenReturn(new I18nTranslation());

        ResponseEntity<I18nExportController.ImportResult> response = controller.importCsv(csvDatei(CSV));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().imported());
        verify(i18nService).saveTranslation("Benutzername", "en", "Username");
    }

    @Test
    void importAlsRootFunktioniert() {
        // Die konsumierende Seite i18n-translations.xhtml haengt unter dem ROOT-Menue;
        // ein root-User ohne admin-Rolle darf nicht ausgesperrt werden.
        alsBenutzerMitRollen("root", "ROLE_ROOT");
        when(i18nService.saveTranslation(anyString(), anyString(), anyString()))
                .thenReturn(new I18nTranslation());

        ResponseEntity<I18nExportController.ImportResult> response = controller.importCsv(csvDatei(CSV));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().imported());
    }

    /**
     * Dokumentiert die Arbeitsteilung: der Import filtert HTML NICHT (kein Input-Sanitizing),
     * der Schutz sitzt bewusst an der Ausgabe (kein {@code escape="false"} in Views, s.
     * {@code EscapeFalseInvariantTest}). Wuerde hier gefiltert, waeren legitime Uebersetzungen
     * mit {@code <} oder {@code &} kaputt.
     */
    @Test
    void importSpeichertHtmlPayloadUnveraendert() {
        alsBenutzerMitRollen("admin", "ROLE_ADMIN");
        when(i18nService.saveTranslation(anyString(), anyString(), anyString()))
                .thenReturn(new I18nTranslation());
        String payload = "<img src=x onerror=alert(1)>";

        controller.importCsv(csvDatei("Benutzername;en;" + payload + "\n"));

        verify(i18nService).saveTranslation("Benutzername", "en", payload);
    }

    // --- Export ---------------------------------------------------------------------------

    @Test
    void exportAlsNormalerUserWirdMit403Abgelehnt() {
        alsBenutzerMitRollen("bob", "ROLE_USER");

        ResponseEntity<byte[]> response = controller.exportCsv(null, false);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER darf nicht alle Uebersetzungen aller Mandanten abziehen");
        verify(i18nService, never()).getAllTranslations();
    }

    @Test
    void exportOhneAuthentifizierungWirdMit403Abgelehnt() {
        ResponseEntity<byte[]> response = controller.exportCsv(null, false);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(i18nService, never()).getAllTranslations();
    }

    @Test
    void exportAlsAdminFunktioniert() {
        alsBenutzerMitRollen("admin", "ROLE_ADMIN");
        I18nTranslation t = new I18nTranslation();
        t.setDefaultLabel("Benutzername");
        t.setLanguageCode("en");
        t.setTranslatedText("Username");
        when(i18nService.getAllTranslations()).thenReturn(java.util.List.of(t));

        ResponseEntity<byte[]> response = controller.exportCsv(null, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);
        assertEquals("defaultLabel;languageCode;translatedText\nBenutzername;en;Username\n", csv);
    }

    @Test
    void exportAlsRootFunktioniert() {
        alsBenutzerMitRollen("root", "ROLE_ROOT");
        when(i18nService.getAllTranslations()).thenReturn(java.util.List.of());

        ResponseEntity<byte[]> response = controller.exportCsv(null, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(i18nService).getAllTranslations();
    }

    @Test
    void exportMitSprachfilterAlsUserWirdMit403Abgelehnt() {
        alsBenutzerMitRollen("bob", "ROLE_USER");

        assertEquals(HttpStatus.FORBIDDEN, controller.exportCsv("en", false).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.exportCsv("en", true).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.exportCsv(null, true).getStatusCode());
        verify(i18nService, never()).getTranslationsByLanguage(any());
        verify(i18nService, never()).getUntranslatedEntries();
        verify(i18nService, never()).getUntranslatedEntries(eq("en"));
    }
}
