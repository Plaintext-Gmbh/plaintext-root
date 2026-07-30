/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for UrlRewriteConfig - HTML to XHTML rewrite filter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlRewriteConfigTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @Mock
    private RequestDispatcher dispatcher;

    @Mock
    private FilterConfig filterConfig;

    private UrlRewriteConfig.HtmlToXhtmlRewriteFilter filter;

    @BeforeEach
    void setUp() {
        filter = new UrlRewriteConfig.HtmlToXhtmlRewriteFilter();
    }

    @Test
    void htmlRewriteFilter_shouldCreateFilterRegistration() {
        UrlRewriteConfig config = new UrlRewriteConfig();
        FilterRegistrationBean<UrlRewriteConfig.HtmlToXhtmlRewriteFilter> registration = config.htmlRewriteFilter();

        assertNotNull(registration);
        assertNotNull(registration.getFilter());
    }

    @Test
    void doFilter_shouldRewriteHtmlToXhtml() throws Exception {
        when(request.getRequestURI()).thenReturn("/kontakte.html");
        when(request.getRequestDispatcher("/kontakte.xhtml")).thenReturn(dispatcher);

        filter.doFilter(request, response, chain);

        verify(request).getRequestDispatcher("/kontakte.xhtml");
        verify(dispatcher).forward(request, response);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughSwaggerUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request, never()).getRequestDispatcher(anyString());
    }

    @Test
    void doFilter_shouldPassThroughSwaggerUrl() throws Exception {
        when(request.getRequestURI()).thenReturn("/swagger/api.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughWebjarsUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/webjars/bootstrap/style.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughApiDocsUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/api-docs/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughV3ApiDocsUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/v3/api-docs/something.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughActuatorUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_shouldPassThroughNonHtmlUrls() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/data.json");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void init_shouldNotThrow() {
        assertDoesNotThrow(() -> filter.init(filterConfig));
    }

    @Test
    void destroy_shouldNotThrow() {
        assertDoesNotThrow(() -> filter.destroy());
    }
}
