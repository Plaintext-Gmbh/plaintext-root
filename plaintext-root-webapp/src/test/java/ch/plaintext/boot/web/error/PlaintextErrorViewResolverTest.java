/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.error;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card 406: an unknown path shall lead to the start page instead of to the
 * whitelabel error page — but only where that hides no defects.
 */
class PlaintextErrorViewResolverTest {

    private final PlaintextErrorViewResolver resolver = new PlaintextErrorViewResolver();

    /** Error forward: getRequestURI() points to /error, the real path stands in the attribute. */
    private static MockHttpServletRequest fehlerRequest(String urspruenglicherPfad) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, urspruenglicherPfad);
        return request;
    }

    private ModelAndView aufloesen(String pfad, HttpStatus status) {
        return resolver.resolveErrorView(fehlerRequest(pfad), status, Map.of());
    }

    @Test
    void unbekannterPfad_wirdAufStartseiteUmgeleitet() {
        ModelAndView mav = aufloesen("/autologin", HttpStatus.NOT_FOUND);

        assertNotNull(mav, "404 auf einem gewoehnlichen Pfad muss umgeleitet werden");
        assertInstanceOf(RedirectView.class, mav.getView());
        assertEquals(PlaintextErrorViewResolver.STARTSEITE, ((RedirectView) mav.getView()).getUrl());
    }

    @Test
    void ausloeserDerKarte_autologinMitKey_wirdUmgeleitet() {
        assertNotNull(aufloesen("/autologin?key=entwertet", HttpStatus.NOT_FOUND));
    }

    @Test
    void serverfehler_bleibtSichtbar() {
        assertNull(aufloesen("/irgendwas", HttpStatus.INTERNAL_SERVER_ERROR),
                "5xx darf nicht umgeleitet werden — sonst verschwinden Stoerungen lautlos");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/kunden/4711",
            "/mcp/tools",
            "/actuator/health",
            "/jakarta.faces.resource/theme.css",
            "/webjars/jquery/jquery.min.js",
            "/resources/logo.png",
            "/static/app.js",
            "/css/main.css",
            "/js/app.js",
            "/images/logo.svg"})
    void technischePfade_bleibenBeim404(String pfad) {
        assertNull(aufloesen(pfad, HttpStatus.NOT_FOUND),
                pfad + " muss ein 404 bleiben — sonst bekommen Clients HTML statt JSON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/error", "/favicon.ico"})
    void redirectZiel_wirdNichtAufSichSelbstUmgeleitet(String pfad) {
        assertNull(aufloesen(pfad, HttpStatus.NOT_FOUND),
                pfad + " wuerde eine Endlosschleife ergeben");
    }

    @Test
    void ohneAttribut_faelltAufRequestUriZurueck() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unbekannt");

        assertNotNull(resolver.resolveErrorView(request, HttpStatus.NOT_FOUND, Map.of()));
    }

    @Test
    void leererPfad_wirdNichtUmgeleitet() {
        assertFalse(resolver.istUmleitbar(""));
        assertFalse(resolver.istUmleitbar(null));
    }

    @Test
    void aehnlicherPraefix_wirdNichtVersehentlichAusgenommen() {
        // "/apitest" does start with "/api", but not with "/api/" — it is an
        // ordinary page and has to be redirected.
        assertTrue(resolver.istUmleitbar("/apitest"));
        assertTrue(resolver.istUmleitbar("/javascript-kurs"));
    }
}
