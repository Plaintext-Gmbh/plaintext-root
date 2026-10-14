/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * H3 hardening for {@code /nosec/api/claude/**}: accepts the API token ONLY from
 * {@code Authorization: Bearer <token>} and passes it on to the {@link ClaudeAutomationController}
 * as the {@code token} request parameter (request wrapper, NO change to the controller signature).
 *
 * <p>A {@code token} parameter (query string or form body) is rejected with 401, even next to a
 * header: a token in the URL ends up in the nginx access log, via fluent-bit in Graylog, in the
 * browser history and in the Referer. The transitional switch {@code plaintext.claude.url-token-fallback}
 * and the {@code X-Claude-Token} header are gone (card 1460, N-2; no client used either).</p>
 *
 * <p>The token itself is NEVER logged.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class ClaudeTokenRequestFilter implements Filter {

    static final String TOKEN_PARAM = "token";
    static final String BEARER_PREFIX = "Bearer ";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String urlToken = httpRequest.getParameter(TOKEN_PARAM);
        if (urlToken != null && !urlToken.isBlank()) {
            // NEVER log the token.
            log.warn("ABGELEHNT: Klartext-Token als URL-Parameter an {} — nur noch "
                            + "'Authorization: Bearer <token>' (URL-Tokens landen in Access-Logs/Proxies/Browser-History)",
                    httpRequest.getRequestURI());
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Token nur im Authorization-Header (Bearer)");
            }
            return;
        }

        String headerToken = extractHeaderToken(httpRequest);
        if (headerToken != null) {
            chain.doFilter(new TokenParameterRequestWrapper(httpRequest, headerToken), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Reads the token from {@code Authorization: Bearer ...}.
     *
     * @return the token, or {@code null} if no (non-empty) bearer token is present
     */
    private String extractHeaderToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    /**
     * Passes the header token on to Spring MVC as the {@code token} parameter
     * ({@code @RequestParam String token} in the controller).
     */
    static class TokenParameterRequestWrapper extends HttpServletRequestWrapper {

        private final String token;

        TokenParameterRequestWrapper(HttpServletRequest request, String token) {
            super(request);
            this.token = token;
        }

        @Override
        public String getParameter(String name) {
            if (TOKEN_PARAM.equals(name)) {
                return token;
            }
            return super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            if (TOKEN_PARAM.equals(name)) {
                return new String[]{token};
            }
            return super.getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> map = new LinkedHashMap<>(super.getParameterMap());
            map.put(TOKEN_PARAM, new String[]{token});
            return Collections.unmodifiableMap(map);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getParameterNames()));
            names.add(TOKEN_PARAM);
            return Collections.enumeration(names);
        }
    }
}
