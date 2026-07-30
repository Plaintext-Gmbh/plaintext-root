/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import ch.plaintext.arch.AllowRawScheduled;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Rate limiting filter for REST API endpoints.
 * Limits requests per IP address to prevent abuse.
 */
@Slf4j
@Component
@AllowRawScheduled // System-Waechter: Rate-Limit-Buckets-Cleanup im Sub-Minuten-Rahmen, keine Cron-Expression moeglich
public class RateLimitFilter implements Filter {

    private final RateLimiter apiLimiter;
    private final RateLimiter loginLimiter;
    private final RateLimiter claudeLimiter;
    private final RateLimiter nosecTokenLimiter;

    public RateLimitFilter(
            @Value("${plaintext.rate-limit.api.max-requests:60}") int apiMaxRequests,
            @Value("${plaintext.rate-limit.api.window-seconds:60}") int apiWindowSeconds,
            @Value("${plaintext.rate-limit.login.max-requests:10}") int loginMaxRequests,
            @Value("${plaintext.rate-limit.login.window-seconds:60}") int loginWindowSeconds,
            @Value("${plaintext.rate-limit.claude.max-requests:60}") int claudeMaxRequests,
            @Value("${plaintext.rate-limit.claude.window-seconds:60}") int claudeWindowSeconds,
            @Value("${plaintext.rate-limit.nosec-token.max-requests:20}") int nosecTokenMaxRequests,
            @Value("${plaintext.rate-limit.nosec-token.window-seconds:60}") int nosecTokenWindowSeconds) {
        this.apiLimiter = new RateLimiter(apiMaxRequests, apiWindowSeconds * 1000L);
        this.loginLimiter = new RateLimiter(loginMaxRequests, loginWindowSeconds * 1000L);
        this.claudeLimiter = new RateLimiter(claudeMaxRequests, claudeWindowSeconds * 1000L);
        this.nosecTokenLimiter = new RateLimiter(nosecTokenMaxRequests, nosecTokenWindowSeconds * 1000L);
        log.info("Rate limiting enabled: API={} req/{}s, Login={} req/{}s, Claude-Automation={} req/{}s, "
                        + "Nosec-Token={} req/{}s",
                apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request) ||
            !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String path = request.getRequestURI();

        if (path.startsWith("/api/")) {
            String clientIp = getClientIp(request);
            if (!apiLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for API from IP: {}", clientIp);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many requests\"}");
                response.setHeader("Retry-After", "60");
                return;
            }
            response.setHeader("X-RateLimit-Remaining", String.valueOf(apiLimiter.getRemainingRequests(clientIp)));
        }

        // H3-Härtung: Brute-Force-Bremse für die tokenbasierten Claude-Automation-Endpoints.
        // /nosec/** ist permitAll — ohne Limit könnte eine IP unbegrenzt Token raten.
        if (path.startsWith("/nosec/api/claude")) {
            String clientIp = getClientIp(request);
            if (!claudeLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for Claude automation API from IP: {}", clientIp);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many requests\"}");
                response.setHeader("Retry-After", "60");
                return;
            }
        }

        // Haertung: weitere tokenbasierte /nosec-Endpunkte ohne Login (z.B. schuetu
        // /nosec/schiri-mobile/** -- QR-Code-Token auf gedrucktem Schirizettel, kein Account/Login
        // moeglich). Ohne Limit koennte eine IP den Token unbegrenzt durchprobieren.
        if (path.startsWith("/nosec/schiri-mobile")) {
            String clientIp = getClientIp(request);
            if (!nosecTokenLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for nosec-token endpoint {} from IP: {}", path, clientIp);
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many requests\"}");
                response.setHeader("Retry-After", "60");
                return;
            }
        }

        if (path.equals("/login") && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                response.setStatus(429);
                response.sendRedirect("/login.xhtml?error=rate_limited");
                return;
            }
        }

        if (path.startsWith("/autologin") || path.startsWith("/token-login")) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                response.setStatus(429);
                response.sendRedirect("/login.xhtml?error=rate_limited");
                return;
            }
        }

        if (path.equals("/ott/generate") && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                response.setStatus(429);
                response.sendRedirect("/login.xhtml?error=rate_limited");
                return;
            }
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredBuckets() {
        apiLimiter.cleanup();
        loginLimiter.cleanup();
        claudeLimiter.cleanup();
        nosecTokenLimiter.cleanup();
    }
}
