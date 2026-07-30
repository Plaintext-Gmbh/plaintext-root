/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(5, 60, 3, 60, 4, 60, 2, 60);
    }

    @Test
    void shouldAllowNormalRequests() throws Exception {
        when(request.getRequestURI()).thenReturn("/index.xhtml");
        filter.doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldRateLimitApiEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/preferences/save");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 5 should pass
        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(5)).doFilter(request, response);

        // 6th should be blocked
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(filterChain, times(5)).doFilter(request, response); // still 5
    }

    @Test
    void shouldRateLimitLoginAttempts() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        // First 3 should pass
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        // 4th should be blocked
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response).sendRedirect("/login.xhtml?error=rate_limited");
    }

    @Test
    void shouldRateLimitAutologin() throws Exception {
        when(request.getRequestURI()).thenReturn("/autologin");
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");

        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
    }

    @Test
    void shouldUseXForwardedForHeader() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
        assertEquals("1.2.3.4", filter.getClientIp(request));
    }

    @Test
    void shouldUseXRealIpHeader() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("9.8.7.6");
        assertEquals("9.8.7.6", filter.getClientIp(request));
    }

    @Test
    void shouldFallBackToRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        assertEquals("127.0.0.1", filter.getClientIp(request));
    }

    @Test
    void shouldSetRateLimitHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/data");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        filter.doFilter(request, response, filterChain);
        verify(response).setHeader(eq("X-RateLimit-Remaining"), anyString());
    }

    @Test
    void shouldNotRateLimitGetLogin() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("GET");

        // GET /login should not be rate limited (only POST)
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(10)).doFilter(request, response);
    }

    @Test
    void shouldRateLimitClaudeAutomationEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/has-work");
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 4 should pass (claude limiter configured with 4 req/window)
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(4)).doFilter(request, response);

        // 5th should be blocked with 429 + Retry-After
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        verify(filterChain, times(4)).doFilter(request, response); // still 4
    }

    @Test
    void claudeLimiterIsPerIp() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/next-task");
        when(request.getRemoteAddr()).thenReturn("10.0.1.1");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }

        // Different IP is not affected by the blocked one
        when(request.getRemoteAddr()).thenReturn("10.0.1.2");
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(5)).doFilter(request, response); // 4 from first IP + 1 from second
    }

    @Test
    void shouldRateLimitSchiriMobileEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/schiri-mobile/abc123");
        when(request.getRemoteAddr()).thenReturn("10.0.2.1");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 2 should pass (nosec-token limiter configured with 2 req/window)
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(2)).doFilter(request, response);

        // 3rd should be blocked with 429 + Retry-After
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        verify(filterChain, times(2)).doFilter(request, response); // still 2
    }

    @Test
    void shouldHandleCleanup() {
        filter.cleanupExpiredBuckets(); // Should not throw
    }

    private void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
