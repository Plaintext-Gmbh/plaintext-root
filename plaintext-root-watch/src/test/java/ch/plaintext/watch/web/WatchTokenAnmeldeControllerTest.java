/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.McpUserRoles;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.watch.service.WatchHandyLinkService;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1257: three locks guard the phone link, and each one has to hold on its own. Every test
 * below opens exactly one of them and keeps the other two shut.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTokenAnmeldeControllerTest {

    private static final String JWT = "ein.gueltiger.token";
    private static final String BENUTZER = "daniel@plaintext.ch";

    private IApiTokenService tokenDienst;
    private WatchStateService zustand;
    private PlaintextSecurity sicherheit;
    private WatchTokenAnmeldeController controller;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> liefert(T wert) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(wert);
        return p;
    }

    @BeforeEach
    void setUp() {
        tokenDienst = mock(IApiTokenService.class);
        zustand = mock(WatchStateService.class);
        sicherheit = mock(PlaintextSecurity.class);
        McpUserRoles rollen = mock(McpUserRoles.class);
        when(rollen.rolesForUser(7L)).thenReturn(Set.of("USER"));
        when(sicherheit.getUsernameForUser(7L)).thenReturn(BENUTZER);
        controller = new WatchTokenAnmeldeController(liefert(tokenDienst), liefert(rollen),
                sicherheit, zustand);
    }

    private void tokenIstGueltig(String name) {
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.of(
                new ApiTokenValidationResult(7L, "plaintext", BENUTZER, name,
                        Instant.now().plusSeconds(3600), "READ")));
    }

    private MockHttpServletResponse einstieg(String token) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                WatchHandyLinkService.EINSTIEGSPFAD);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.einstieg(token, request, response);
        this.letzteAnfrage = request;
        return response;
    }

    private MockHttpServletRequest letzteAnfrage;

    @Test
    @DisplayName("Ein gueltiger Link oeffnet die Uhr ohne Anmeldung und nimmt den Token aus der Adresse")
    void gueltigerLink() throws IOException {
        tokenIstGueltig(WatchHandyLinkService.TOKEN_NAME);
        when(zustand.handyLinkAktivFuer(BENUTZER)).thenReturn(true);

        MockHttpServletResponse antwort = einstieg(JWT);

        assertEquals(HttpServletResponse.SC_MOVED_TEMPORARILY, antwort.getStatus());
        assertEquals(WatchStartController.PFAD, antwort.getRedirectedUrl(),
                "Weiterleitung OHNE Token — sonst steht er im Verlauf und im Referrer. Und auf "
                        + "den Einstieg, nicht auf /watch/home.html: sonst landet der Handy-Link "
                        + "immer auf der Uebersicht statt auf der zuletzt offenen Seite "
                        + "(Karte 1289).");
        var sitzung = letzteAnfrage.getSession(false);
        assertNotNull(sitzung);
        SecurityContext kontext = (SecurityContext) sitzung.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(kontext, "ohne Kontext in der Sitzung waere die Folgeseite anonym");
        assertEquals(BENUTZER, kontext.getAuthentication().getName());
        assertTrue(kontext.getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> WatchTokenAnmeldeController.ROLLE_TOKEN_SITZUNG.equals(a.getAuthority())),
                "ohne die Markierung koennte der Sitzungsfilter die Sitzung nicht einsperren");
        assertTrue(kontext.getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> "PROPERTY_MYUSERID_7".equals(a.getAuthority())),
                "PlaintextSecurityImpl.getId() liest den Benutzer aus genau dieser Autoritaet");
        assertEquals(JWT, sitzung.getAttribute(WatchTokenAnmeldeController.SITZUNG_TOKEN));
    }

    @Test
    @DisplayName("Ein widerrufener Token oeffnet nichts — die Pruefung geht gegen die Datenbank")
    void widerrufenerToken() throws IOException {
        // validateToken() des ApiTokenService liefert leer, sobald die Zeile in api_token
        // fehlt, geloescht oder invalidated ist. Genau das ist "sofortiger Widerruf".
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.empty());

        MockHttpServletResponse antwort = einstieg(JWT);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, antwort.getStatus());
        assertNull(letzteAnfrage.getSession(false), "es darf keine Sitzung entstehen");
    }

    @Test
    @DisplayName("Ein anderer Token desselben Benutzers oeffnet die Uhr NICHT")
    void fremderTokenTyp() throws IOException {
        // Ein ADMIN-API-Token derselben Person ist gueltig, signiert und nicht widerrufen —
        // und trotzdem kein Handy-Link. Ohne diese Pruefung waere jeder API-Token ein Zugang
        // zur Uhr, und schlimmer: der Name waere kein Merkmal mehr, an dem der MCP-Filter den
        // Browser-Ausweis erkennt.
        tokenIstGueltig("Mein API-Token");
        when(zustand.handyLinkAktivFuer(BENUTZER)).thenReturn(true);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg(JWT).getStatus());
    }

    @Test
    @DisplayName("Ein deaktivierter Link oeffnet nichts, auch wenn der Token noch gueltig waere")
    void deaktivierterLink() throws IOException {
        tokenIstGueltig(WatchHandyLinkService.TOKEN_NAME);
        when(zustand.handyLinkAktivFuer(BENUTZER)).thenReturn(false);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg(JWT).getStatus());
    }

    @Test
    @DisplayName("Ohne Token und mit leerem Token passiert gar nichts")
    void ohneToken() throws IOException {
        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg(null).getStatus());
        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg("  ").getStatus());
    }

    @Test
    @DisplayName("Wirft die Pruefung, wird abgewiesen — fail-closed")
    void pruefungWirft() throws IOException {
        when(tokenDienst.validateToken(JWT)).thenThrow(new IllegalStateException("DB weg"));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg(JWT).getStatus());
    }

    @Test
    @DisplayName("Eine mitgebrachte Sitzung wird verworfen — keine Sitzungsuebernahme")
    void sitzungsfixierung() throws IOException {
        tokenIstGueltig(WatchHandyLinkService.TOKEN_NAME);
        when(zustand.handyLinkAktivFuer(BENUTZER)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                WatchHandyLinkService.EINSTIEGSPFAD);
        var alt = request.getSession(true);
        alt.setAttribute("fremd", "wert");
        String alteKennung = alt.getId();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.einstieg(JWT, request, response);

        var neu = request.getSession(false);
        assertNotNull(neu);
        assertNotEquals(alteKennung, neu.getId(),
                "die vorher untergeschobene Sitzung darf nicht die angemeldete werden");
        assertNull(neu.getAttribute("fremd"));
    }

    @Test
    @DisplayName("Ohne Token-Modul ist der Einstieg tot statt offen")
    void ohneTokenmodul() throws IOException {
        controller = new WatchTokenAnmeldeController(liefert(null), liefert(null), sicherheit, zustand);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, einstieg(JWT).getStatus());
    }
}
