/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", AuthorityUtils.createAuthorityList("ROLE_USER")));
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

    @Test
    void isJsfAjaxRequest_onlyForFacesPartialHeader() {
        assertTrue(JsfAjaxResponses.isJsfAjaxRequest(ajaxRequest()));
        assertFalse(JsfAjaxResponses.isJsfAjaxRequest(new MockHttpServletRequest("POST", "/wiki.xhtml")));
    }
}
