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
 * H3-Härtung für {@code /nosec/api/claude/**}: nimmt den API-Token aus einem HTTP-Header
 * entgegen — {@code Authorization: Bearer <token>} (bevorzugt) oder {@code X-Claude-Token} —
 * und reicht ihn als {@code token}-Request-Parameter an den {@link ClaudeAutomationController}
 * weiter (Request-Wrapper, KEINE Controller-Signatur-Änderung nötig).
 *
 * <p><b>Übergangsphase:</b> der bisherige Klartext-Token als URL-/Query-Parameter
 * ({@code ?token=...}) wird weiterhin akzeptiert, damit bestehende Clients (watch-claude.sh,
 * Goal-System) nicht brechen — aber mit WARN-Log als DEPRECATED markiert. Fahrplan:
 * <ol>
 *   <li>Jetzt: Header bevorzugt, URL-Token akzeptiert + WARN (dieser Stand)</li>
 *   <li>Nach Client-Umstellung: URL-Token ablehnen (Filter: 401 statt Durchreichen)</li>
 *   <li>Follow-up: Klartext-Spalte {@code api_token} entfernen (nur noch Hash)</li>
 * </ol></p>
 *
 * <p>Ist BEIDES vorhanden, gewinnt der Header (der modernere Client). Der Token selbst wird
 * NIE geloggt.</p>
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
     * Zustandsbericht 29.08.2026 (H2): Die Uebergangsphase ist beendet. Ein Token im
     * Query-String landet im nginx-Access-Log, via fluent-bit in Graylog, im Browserverlauf und
     * im Referer — jeder mit Log-Lesezugriff kennt ihn danach. Standard ist deshalb
     * <b>ablehnen</b> (401). Wer einen alten Client uebergangsweise weiterlaufen lassen muss,
     * setzt {@code plaintext.claude.url-token-fallback=true} — und bekommt weiterhin die
     * DEPRECATED-Warnung im Log.
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
            // Token NIE mitloggen.
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
     * Token aus {@code Authorization: Bearer ...} (bevorzugt) oder {@code X-Claude-Token} lesen.
     *
     * @return Token oder {@code null}, wenn kein (nicht-leerer) Header-Token vorhanden ist
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
     * Reicht den Header-Token als {@code token}-Parameter an Spring MVC durch
     * ({@code @RequestParam String token} im Controller). Überschreibt einen ggf.
     * zusätzlich vorhandenen URL-Token (Header gewinnt).
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
