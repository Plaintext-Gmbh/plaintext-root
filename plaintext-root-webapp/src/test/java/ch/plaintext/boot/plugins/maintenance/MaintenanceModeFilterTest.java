/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceModeFilterTest {

    private MaintenanceModeProperties properties;
    private MaintenanceModeFilter filter;

    @BeforeEach
    void setUp() {
        properties = new MaintenanceModeProperties();
        filter = new MaintenanceModeFilter(properties);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledFilterIsNoOp() throws ServletException, IOException {
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotEquals(503, response.getStatus());
    }

    @Test
    void enabledFilterServes503ForUnauthenticatedRequest() throws ServletException, IOException {
        properties.setEnabled(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertEquals(503, response.getStatus());
        assertEquals("text/html; charset=utf-8", response.getContentType());
        assertEquals("1800", response.getHeader("Retry-After"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertTrue(response.getContentAsString().contains("We&rsquo;ll be back shortly"));
    }

    @Test
    void allowedPathsAreAlwaysServed() throws ServletException, IOException {
        properties.setEnabled(true);

        for (String path : List.of(
                "/login", "/login.html", "/login.xhtml",
                "/logout",
                "/actuator/health",
                "/jakarta.faces.resource/somefile.css",
                "/static/foo.png",
                "/css/main.css",
                "/js/app.js",
                "/images/logo.svg")) {

            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertNotEquals(503, response.getStatus(),
                    "path " + path + " must be allowed under maintenance");
        }
    }

    @Test
    void bypassRolePassesThrough() throws ServletException, IOException {
        properties.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotEquals(503, response.getStatus());
    }

    @Test
    void rootRoleAlsoBypasses() throws ServletException, IOException {
        properties.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("root", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_ROOT"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotEquals(503, response.getStatus());
    }

    @Test
    void regularUserIsBlocked() throws ServletException, IOException {
        properties.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard.xhtml");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertEquals(503, response.getStatus());
    }

    @Test
    void anonymousAuthenticationIsTreatedAsUnauthenticated() throws ServletException, IOException {
        properties.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
    }

    @Test
    void htmlInMessageIsEscaped() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.setMessage("<script>alert('xss')</script> & \"quote\"");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        String body = response.getContentAsString();
        assertFalse(body.contains("<script>"), "raw <script> must not appear");
        assertTrue(body.contains("&lt;script&gt;"));
        assertTrue(body.contains("&amp;"));
        assertTrue(body.contains("&quot;"));
    }

    @Test
    void retryAfterHeaderUsesConfiguredValue() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.setRetryAfterSeconds(3600);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertEquals("3600", response.getHeader("Retry-After"));
    }

    @Test
    void customBypassRoleCanBeConfigured() throws ServletException, IOException {
        properties.setEnabled(true);
        properties.setBypassRoles(List.of("ROLE_OPERATOR"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void escapeHtmlHandlesAllPunctuation() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", MaintenanceModeFilter.escapeHtml("&<>\"'"));
    }

    @Test
    void renderedPageContainsEscapedMessage() {
        String html = MaintenanceModeFilter.renderMaintenancePage("hello & <world>");
        assertTrue(html.contains("hello &amp; &lt;world&gt;"));
        assertTrue(html.startsWith("<!DOCTYPE html>"));
    }
}
