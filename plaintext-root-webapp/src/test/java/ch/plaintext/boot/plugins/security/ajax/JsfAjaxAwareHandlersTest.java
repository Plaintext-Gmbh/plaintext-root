/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Karte 385: Ein JSF-Ajax-Request darf nie eine Antwort bekommen, die die PrimeFaces-Ajax-Engine
 * nicht parsen kann — sonst dreht der Ladeindikator endlos.
 */
class JsfAjaxAwareHandlersTest {

    private static MockHttpServletRequest ajaxRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiki.xhtml");
        request.addHeader(JsfAjaxResponses.FACES_REQUEST_HEADER, JsfAjaxResponses.FACES_PARTIAL_AJAX);
        return request;
    }

    @Test
    void csrfFailureOnAjax_yields200PartialResponseWithRedirect() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsfAjaxAwareAccessDeniedHandler("/login.html")
                .handle(ajaxRequest(), response, new MissingCsrfTokenException("token"));

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/xml"));
        assertTrue(response.getContentAsString().contains("<partial-response>"));
        assertTrue(response.getContentAsString().contains("<redirect url=\"/login.html\"/>"));
    }

    @Test
    void accessDeniedForAuthenticatedUserOnAjax_yieldsParsableErrorInsteadOfJson() throws Exception {
        // Bewusst ein FRISCHER Kontext statt getContext().setAuthentication(...): laesst eine zuvor
        // gelaufene Testklasse einen Mock-SecurityContext im Holder stehen, landet das
        // setAuthentication auf dem Mock und verpufft — der Test saehe dann einen abgemeldeten
        // Benutzer und schlaege fehl. Genau das ist am 02.08.2026 auf ubuntu-latest passiert
        // (PlaintextSecurityImplExtendedTest raeumte nicht auf, Karte 426); dort ist die Ursache
        // behoben, hier steht die Absicherung dagegen, dass es wieder jemand einschleppt.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", AuthorityUtils.createAuthorityList("ROLE_USER")));
        SecurityContextHolder.setContext(context);
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();

            new JsfAjaxAwareAccessDeniedHandler("/login.html")
                    .handle(ajaxRequest(), response, new AccessDeniedException("nope"));

            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("<error>"));
            assertFalse(response.getContentAsString().contains("<redirect"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void accessDeniedOnNormalRequest_keepsSpringDefault403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsfAjaxAwareAccessDeniedHandler("/login.html")
                .handle(new MockHttpServletRequest("POST", "/wiki.xhtml"), response,
                        new AccessDeniedException("nope"));

        assertEquals(403, response.getStatus());
    }

    @Test
    void entryPointOnAjax_yields200PartialResponseWithRedirect() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsfAjaxAwareAuthenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/login.html"), "/login.html")
                .commence(ajaxRequest(), response, new BadCredentialsException("nope"));

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/xml"));
        assertTrue(response.getContentAsString().contains("<redirect url=\"/login.html\"/>"));
    }

    @Test
    void entryPointOnNormalRequest_delegatesToLoginRedirect() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsfAjaxAwareAuthenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/login.html"), "/login.html")
                .commence(new MockHttpServletRequest("GET", "/wiki.xhtml"), response,
                        new BadCredentialsException("nope"));

        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().endsWith("/login.html"));
    }

    /**
     * Karte 385 (Manager-Review): Die Antwort ist jetzt HTTP 200 statt 403 — ohne eigenen
     * Log-Eintrag waeren abgewiesene Requests und echte CSRF-Angriffsversuche unsichtbar.
     * Der Eintrag muss die URI und den Grund nennen und darf kein Token enthalten.
     */
    @Test
    void csrfFailureOnAjax_wirdGeloggtOhneToken() throws Exception {
        ListAppender<ILoggingEvent> appender = attach(JsfAjaxAwareAccessDeniedHandler.class);
        try {
            new JsfAjaxAwareAccessDeniedHandler("/login.html")
                    .handle(ajaxRequest(), new MockHttpServletResponse(),
                            new MissingCsrfTokenException("geheimes-token-123"));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            String meldung = event.getFormattedMessage();
            assertTrue(meldung.contains("/wiki.xhtml"), meldung);
            assertTrue(meldung.contains("CSRF"), meldung);
            assertTrue(meldung.contains("partial-response"), meldung);
            assertFalse(meldung.contains("geheimes-token-123"), "Kein Token ins Log: " + meldung);
        } finally {
            detach(JsfAjaxAwareAccessDeniedHandler.class, appender);
        }
    }

    /** Karte 385: Die abgelaufene Session ist der Normalfall — sichtbar, aber nur INFO. */
    @Test
    void entryPointOnAjax_wirdAufInfoGeloggt() throws Exception {
        ListAppender<ILoggingEvent> appender = attach(JsfAjaxAwareAuthenticationEntryPoint.class);
        try {
            new JsfAjaxAwareAuthenticationEntryPoint(
                    new LoginUrlAuthenticationEntryPoint("/login.html"), "/login.html")
                    .commence(ajaxRequest(), new MockHttpServletResponse(),
                            new BadCredentialsException("nope"));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.INFO, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("/wiki.xhtml"));
            assertTrue(event.getFormattedMessage().contains("partial-response"));
        } finally {
            detach(JsfAjaxAwareAuthenticationEntryPoint.class, appender);
        }
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> typ) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(typ);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> typ, ListAppender<ILoggingEvent> appender) {
        ((Logger) org.slf4j.LoggerFactory.getLogger(typ)).detachAppender(appender);
    }

    @Test
    void isJsfAjaxRequest_onlyForFacesPartialHeader() {
        assertTrue(JsfAjaxResponses.isJsfAjaxRequest(ajaxRequest()));
        assertFalse(JsfAjaxResponses.isJsfAjaxRequest(new MockHttpServletRequest("POST", "/wiki.xhtml")));
    }
}
