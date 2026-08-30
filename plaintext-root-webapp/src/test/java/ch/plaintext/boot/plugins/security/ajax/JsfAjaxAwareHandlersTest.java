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
 * Card 385: a JSF Ajax request must never receive a response that the PrimeFaces Ajax engine
 * cannot parse — otherwise the loading indicator spins forever.
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
        // Deliberately a FRESH context instead of getContext().setAuthentication(...): if a previously
        // executed test class leaves a mock SecurityContext in the holder, the
        // setAuthentication lands on the mock and fizzles out — the test would then see a logged-out
        // user and fail. Exactly that happened on 02.08.2026 on ubuntu-latest
        // (PlaintextSecurityImplExtendedTest did not clean up, card 426); the cause is fixed there,
        // here stands the safeguard against somebody dragging it back in.
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
     * Card 385 (manager review): the response is now HTTP 200 instead of 403 — without a log
     * entry of its own, rejected requests and real CSRF attack attempts would be invisible.
     * The entry has to name the URI and the reason and must not contain a token.
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

    /** Card 385: the expired session is the normal case — visible, but only INFO. */
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
