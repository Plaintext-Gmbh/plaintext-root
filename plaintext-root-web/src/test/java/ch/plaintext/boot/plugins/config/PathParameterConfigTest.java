/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the PathParameterFilter (Card 612).
 *
 * <p>The cases mirror the measurements from 07.08.2026: a semicolon in the URL led to a 400
 * without a log line for authenticated requests, and to a login redirect on pages that were
 * actually public for anonymous ones.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PathParameterConfigTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @Mock
    private FilterConfig filterConfig;

    private PathParameterConfig.PathParameterFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PathParameterConfig.PathParameterFilter();
    }

    private void get(String uri, String queryString) {
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("GET");
        when(request.getQueryString()).thenReturn(queryString);
    }

    private String capturedRedirect() throws Exception {
        ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(location.capture());
        return location.getValue();
    }

    // --- Registration ----------------------------------------------------------------------

    @Test
    void filterRegistration_shouldRunBeforeSpringSecurityAndHtmlRewrite() {
        FilterRegistrationBean<PathParameterConfig.PathParameterFilter> registration =
                new PathParameterConfig().pathParameterFilter();

        assertNotNull(registration.getFilter());
        // Card 612: the order has to satisfy TWO conditions at once --
        //   after   ForwardedHeaderFilter (+10), otherwise sendRedirect() builds the redirect
        //           with the connector's scheme (http) instead of the one of the original
        //           request (https)
        //   before  htmlRewriteFilter (+30), otherwise /login.html;jsessionid=... arrives there
        //           uncleaned and does not end in .html
        // Spring Security still sits far behind that (-100).
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 20, registration.getOrder());
        assertTrue(registration.getOrder() > Ordered.HIGHEST_PRECEDENCE + 10,
                "muss nach dem ForwardedHeaderFilter laufen, sonst degradiert der Redirect auf http");
        assertTrue(registration.getOrder() < Ordered.HIGHEST_PRECEDENCE + 30,
                "muss vor dem htmlRewriteFilter laufen, sonst sieht der eine unbereinigte URL");
        assertTrue(registration.getUrlPatterns().contains("/*"));
    }

    // --- Normal case: do nothing -----------------------------------------------------------

    @Test
    void doFilter_shouldPassThroughUrlsWithoutSemicolon() throws Exception {
        when(request.getRequestURI()).thenReturn("/kontakte.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void doFilter_shouldPassThroughNullUri() throws Exception {
        when(request.getRequestURI()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- The triggering case from Card 612 -------------------------------------------------

    @Test
    void doFilter_shouldRedirectRootWithJsessionidToRoot() throws Exception {
        get("/;jsessionid=C252CABB81F6399C0000000000000000", null);

        filter.doFilter(request, response, chain);

        assertEquals("/", capturedRedirect());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_shouldRedirectPageWithJsessionid() throws Exception {
        get("/rechnungen.html;jsessionid=ABC", null);

        filter.doFilter(request, response, chain);

        assertEquals("/rechnungen.html", capturedRedirect());
    }

    @Test
    void doFilter_shouldKeepQueryString() throws Exception {
        get("/;jsessionid=ABC", "filter=offen");

        filter.doFilter(request, response, chain);

        assertEquals("/?filter=offen", capturedRedirect());
    }

    @Test
    void doFilter_shouldStripParameterOnEveryPathSegment() throws Exception {
        get("/a;jsessionid=X/b;foo=1/c", null);

        filter.doFilter(request, response, chain);

        assertEquals("/a/b/c", capturedRedirect());
    }

    @Test
    void doFilter_shouldAlsoHandleSemicolonWithoutJsessionid() throws Exception {
        // Measured: ";foo=bar" triggered the 400 as well - not just the session id.
        get("/actuator/health;foo=bar", null);

        filter.doFilter(request, response, chain);

        assertEquals("/actuator/health", capturedRedirect());
    }

    @Test
    void doFilter_shouldHandleHeadLikeGet() throws Exception {
        when(request.getRequestURI()).thenReturn("/;jsessionid=ABC");
        when(request.getMethod()).thenReturn("HEAD");

        filter.doFilter(request, response, chain);

        assertEquals("/", capturedRedirect());
    }

    // --- Non-GET: no redirect, but a cleaned-up path ---------------------------------------

    @Test
    void doFilter_shouldNotRedirectPostButCleanThePath() throws Exception {
        when(request.getRequestURI()).thenReturn("/speichern.html;jsessionid=ABC");
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(response, never()).sendRedirect(anyString());
        ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), eq(response));
        assertEquals("/speichern.html", ((HttpServletRequest) forwarded.getValue()).getRequestURI());
    }

    @Test
    void doFilter_shouldNotRedirectCaldavMethods() throws Exception {
        when(request.getRequestURI()).thenReturn("/carddav/adressbuch;foo=1");
        when(request.getMethod()).thenReturn("PROPFIND");

        filter.doFilter(request, response, chain);

        verify(response, never()).sendRedirect(anyString());
        verify(chain).doFilter(any(ServletRequest.class), eq(response));
    }

    @Test
    void wrappedRequest_shouldRewriteRequestUrl() {
        when(request.getRequestURL()).thenReturn(new StringBuffer("https://app.plaintext.ch/a;jsessionid=X/b"));

        PathParameterConfig.StrippedPathRequest wrapped =
                new PathParameterConfig.StrippedPathRequest(request, "/a/b");

        assertEquals("https://app.plaintext.ch/a/b", wrapped.getRequestURL().toString());
        assertEquals("/a/b", wrapped.getRequestURI());
    }

    // --- Security --------------------------------------------------------------------------

    @Test
    void doFilter_shouldNotProduceProtocolRelativeOpenRedirect() throws Exception {
        // "//boese.example/x" would be a protocol-relative URL - the browser would end up on a
        // foreign host. Such a path is folded back to the root.
        get("//boese.example/x;jsessionid=ABC", null);

        filter.doFilter(request, response, chain);

        assertEquals("/", capturedRedirect());
    }

    @Test
    void appendQueryString_shouldDropQueryWithLineBreaks() {
        assertEquals("/",
                PathParameterConfig.PathParameterFilter.appendQueryString("/", "a=b\r\nLocation: x"));
        assertEquals("/?a=b",
                PathParameterConfig.PathParameterFilter.appendQueryString("/", "a=b"));
    }

    @Test
    void maskParameterValues_shouldHideTheSessionId() {
        // The session id is access without a password - it must not end up in the log.
        String masked = PathParameterConfig.PathParameterFilter
                .maskParameterValues("/;jsessionid=C252CABB81F6399C");

        assertEquals("/;jsessionid=***", masked);
        assertFalse(masked.contains("C252CABB81F6399C"));
    }

    @Test
    void stripPathParameters_shouldHandleEdgeCases() {
        assertEquals("/", PathParameterConfig.PathParameterFilter.stripPathParameters("/;jsessionid=X"));
        assertEquals("/a/", PathParameterConfig.PathParameterFilter.stripPathParameters("/a;x=1/"));
        assertEquals("/a/b", PathParameterConfig.PathParameterFilter.stripPathParameters("/a/b"));
        assertEquals("/", PathParameterConfig.PathParameterFilter.stripPathParameters(";x=1"));
    }

    // --- Lifecycle -------------------------------------------------------------------------

    @Test
    void initAndDestroy_shouldNotThrow() {
        assertDoesNotThrow(() -> filter.init(filterConfig));
        assertDoesNotThrow(() -> filter.destroy());
    }
}
