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
 * H3 hardening for {@code /nosec/api/claude/**}: accepts the API token from an HTTP header
 * — {@code Authorization: Bearer <token>} (preferred) or {@code X-Claude-Token} — and passes
 * it on to the {@link ClaudeAutomationController} as the {@code token} request parameter
 * (request wrapper, NO change to the controller signature required).
 *
 * <p><b>Transitional phase:</b> the previous cleartext token as a URL/query parameter
 * ({@code ?token=...}) is still accepted so that existing clients (watch-claude.sh,
 * goal system) do not break — but is marked DEPRECATED with a WARN log. Roadmap:
 * <ol>
 *   <li>Now: header preferred, URL token accepted + WARN (this state)</li>
 *   <li>After the clients have been migrated: reject the URL token (filter: 401 instead of passing it on)</li>
 *   <li>Follow-up: drop the cleartext column {@code api_token} (hash only)</li>
 * </ol></p>
 *
 * <p>If BOTH are present, the header wins (the more modern client). The token itself is
 * NEVER logged.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class ClaudeTokenRequestFilter implements Filter {

    static final String TOKEN_PARAM = "token";
    static final String BEARER_PREFIX = "Bearer ";
    static final String CLAUDE_TOKEN_HEADER = "X-Claude-Token";

    /**
     * Status report 29.08.2026 (H2): the transitional phase is over. A token in the query
     * string ends up in the nginx access log, via fluent-bit in Graylog, in the browser
     * history and in the Referer — everyone with read access to the logs knows it afterwards.
     * The default is therefore to <b>reject</b> (401). Anyone who has to keep an old client
     * running for the time being sets {@code plaintext.claude.url-token-fallback=true} — and
     * still gets the DEPRECATED warning in the log.
     */
    private final boolean urlTokenFallback;

    public ClaudeTokenRequestFilter() {
        this(false);
    }

    public ClaudeTokenRequestFilter(boolean urlTokenFallback) {
        this.urlTokenFallback = urlTokenFallback;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String headerToken = extractHeaderToken(httpRequest);
        if (headerToken != null) {
            chain.doFilter(new TokenParameterRequestWrapper(httpRequest, headerToken), response);
            return;
        }

        String urlToken = httpRequest.getParameter(TOKEN_PARAM);
        if (urlToken != null && !urlToken.isBlank()) {
            // NEVER log the token.
            if (!urlTokenFallback) {
                log.warn("ABGELEHNT: Klartext-Token als URL-Parameter an {} — nur noch "
                                + "'Authorization: Bearer <token>' oder '{}'-Header "
                                + "(URL-Tokens landen in Access-Logs/Proxies/Browser-History; "
                                + "Uebergang: plaintext.claude.url-token-fallback=true)",
                        httpRequest.getRequestURI(), CLAUDE_TOKEN_HEADER);
                if (response instanceof HttpServletResponse httpResponse) {
                    httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                            "Token nur im Authorization-Header (Bearer) oder " + CLAUDE_TOKEN_HEADER);
                }
                return;
            }
            log.warn("DEPRECATED: Klartext-Token als URL-Parameter an {} — bitte auf "
                            + "'Authorization: Bearer <token>' oder '{}'-Header umstellen "
                            + "(URL-Tokens landen in Access-Logs/Proxies/Browser-History)",
                    httpRequest.getRequestURI(), CLAUDE_TOKEN_HEADER);
        }
        chain.doFilter(request, response);
    }

    /**
     * Reads the token from {@code Authorization: Bearer ...} (preferred) or {@code X-Claude-Token}.
     *
     * @return the token, or {@code null} if no (non-empty) header token is present
     */
    private String extractHeaderToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String claudeToken = request.getHeader(CLAUDE_TOKEN_HEADER);
        if (claudeToken != null && !claudeToken.isBlank()) {
            return claudeToken.trim();
        }
        return null;
    }

    /**
     * Passes the header token on to Spring MVC as the {@code token} parameter
     * ({@code @RequestParam String token} in the controller). Overrides a URL token that may
     * additionally be present (the header wins).
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
