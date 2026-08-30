/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link PageAccessGuardFilter} (card 308, H3).
 *
 * <p>The most important proof sits in {@link #postbackAufGesperrteSeiteErreichtDenServletNie()}:
 * the guard used to hang off {@code preRenderView} (RENDER_RESPONSE, phase 6) and therefore ran
 * AFTER INVOKE_APPLICATION (phase 5) — by the time of the check, the action method of a blocked
 * page had already been executed and its write operation committed. A Mockito verify on the
 * backing bean would be the wrong proof here: with the filter in place the request never reaches
 * the JSF lifecycle at all, so there is no bean one could observe. What is proven instead is that
 * the filter chain does not continue.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageAccessGuardFilterTest {

    @Mock
    private PageAccessGuardService service;

    @Mock
    private FilterChain chain;

    private PageAccessGuardFilter filter() {
        when(service.isEnabled()).thenReturn(true);
        return new PageAccessGuardFilter(service);
    }

    private MockHttpServletRequest request(String methode, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(methode, uri);
        request.setRequestURI(uri);
        return request;
    }

    // ============================================================ allowed

    @Test
    void erlaubteSeiteLaeuftWeiter() throws ServletException, IOException {
        when(service.hasAccessToView("/kontakte.xhtml")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("GET", "/kontakte.html"), response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("URL-Endung ist egal: .html, .htm, .xhtml und .jsf werden auf dieselbe View-Id abgebildet")
    void alleViewEndungenWerdenAufDieViewIdAbgebildet() {
        assertEquals("/kontakte.xhtml", PageAccessGuardFilter.viewId("/kontakte.html"));
        assertEquals("/kontakte.xhtml", PageAccessGuardFilter.viewId("/kontakte.htm"));
        assertEquals("/kontakte.xhtml", PageAccessGuardFilter.viewId("/kontakte.xhtml"));
        assertEquals("/kontakte.xhtml", PageAccessGuardFilter.viewId("/kontakte.jsf"));
        assertEquals("/unter/seite.xhtml", PageAccessGuardFilter.viewId("/unter/seite.html"));
    }

    @Test
    @DisplayName("Das /faces/*-Mapping des FacesServlet ist kein Weg um den Guard herum")
    void facesPraefixWirdNormalisiert() {
        // joinfaces also maps the FacesServlet to /faces/*. /faces/mandatemenu.xhtml renders the
        // same view — without normalization that would be a view id without a menu match, and thus
        // allowed in REPORT mode.
        assertEquals("/mandatemenu.xhtml", PageAccessGuardFilter.viewId("/faces/mandatemenu.xhtml"));
        assertEquals("/mandatemenu.xhtml", PageAccessGuardFilter.viewId("/faces/mandatemenu.html"));
        // "facesXY" is not a prefix and must not be cut into
        assertEquals("/facesluft.xhtml", PageAccessGuardFilter.viewId("/facesluft.html"));
    }

    // ============================================================ denied

    @Test
    void gesperrterGetLandetAufAccessDenied() throws ServletException, IOException {
        when(service.hasAccessToView("/mandatemenu.xhtml")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("GET", "/mandatemenu.html"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(302, response.getStatus());
        assertEquals("/access-denied.html", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("H3: ein Postback auf eine gesperrte Seite erreicht das FacesServlet nie (Action laeuft nicht)")
    void postbackAufGesperrteSeiteErreichtDenServletNie() throws ServletException, IOException {
        when(service.hasAccessToView("/rootentities.xhtml")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("POST", "/rootentities.html"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(403, response.getStatus(),
                "Ein 302 auf einen Postback wuerde dem Client vortaeuschen, die Aktion sei ausgefuehrt worden");
    }

    @Test
    void gesperrterAjaxRequestBekommt403StattRedirect() throws ServletException, IOException {
        when(service.hasAccessToView("/rootentities.xhtml")).thenReturn(false);
        MockHttpServletRequest request = request("GET", "/rootentities.html");
        request.addHeader("Faces-Request", "partial/ajax");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(403, response.getStatus());
    }

    @Test
    void gesperrteSeiteMitContextPathLeitetAufDenRichtigenPfadUm() throws ServletException, IOException {
        when(service.hasAccessToView("/mandatemenu.xhtml")).thenReturn(false);
        MockHttpServletRequest request = request("GET", "/app/mandatemenu.html");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, chain);

        assertEquals("/app/access-denied.html", response.getRedirectedUrl());
    }

    // ============================================================ not responsible

    @Test
    @DisplayName("JSF-Ressourcen werden nicht geprueft — sonst wuerde STRICT alle PrimeFaces-Assets sperren")
    void jsfRessourcenWerdenNichtGeprueft() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("GET", "/jakarta.faces.resource/primefaces.js.xhtml"), response, chain);

        verify(chain).doFilter(any(), any());
        verify(service, never()).hasAccessToView(any());
    }

    @Test
    void technischePfadeUndEndungsloseUrlsWerdenDurchgelassen() {
        assertFalse(PageAccessGuardFilter.istZuPruefen("/api/kontakte"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/actuator/health"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/swagger-ui/index.html"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/v3/api-docs"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/webjars/jquery/jquery.js"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/jakarta.faces.resource/theme.css.xhtml"));
        assertFalse(PageAccessGuardFilter.istZuPruefen("/login/oauth2/code/keycloak"));
        assertFalse(PageAccessGuardFilter.istZuPruefen(null));
        assertFalse(PageAccessGuardFilter.istZuPruefen(""));

        assertTrue(PageAccessGuardFilter.istZuPruefen("/kontakte.html"));
        assertTrue(PageAccessGuardFilter.istZuPruefen("/kontakte.xhtml"));
        assertTrue(PageAccessGuardFilter.istZuPruefen("/nosec/uhr.xhtml"));
    }

    @Test
    void notAusSchalterUeberspringtDenFilterKomplett() throws ServletException, IOException {
        when(service.isEnabled()).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PageAccessGuardFilter(service).doFilter(request("GET", "/mandatemenu.html"), response, chain);

        verify(chain).doFilter(any(), any());
        verify(service, never()).hasAccessToView(any());
    }

    @Test
    @DisplayName("Zweiter Dispatch derselben Anfrage (FORWARD des UrlRewriteFilters) prueft nicht erneut")
    void zweiterDispatchPruefftNichtErneut() throws ServletException, IOException {
        when(service.hasAccessToView("/kontakte.xhtml")).thenReturn(true);
        MockHttpServletRequest request = request("GET", "/kontakte.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        PageAccessGuardFilter filter = filter();

        filter.doFilter(request, response, chain);
        assertTrue(request.getAttribute(PageAccessGuardFilter.ATTRIBUT_GEPRUEFT) != null);

        filter.doFilter(request, response, chain);

        verify(service).hasAccessToView("/kontakte.xhtml");
        verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    }

    @Test
    void nichtHttpRequestsWerdenDurchgelassen() throws ServletException, IOException {
        jakarta.servlet.ServletRequest request = org.mockito.Mockito.mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse response = org.mockito.Mockito.mock(jakarta.servlet.ServletResponse.class);

        new PageAccessGuardFilter(service).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(service);
    }
}
