/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import ch.plaintext.arch.AllowRawScheduled;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Rate limiting filter for REST API endpoints.
 * Limits requests per IP address to prevent abuse.
 *
 * <p>SECURITY (card 303, finding 1): the filter has to run <b>before</b> the
 * {@code springSecurityFilterChain}, otherwise the branches for {@code POST /login} and
 * {@code POST /ott/generate} are dead code — Spring's {@code UsernamePasswordAuthenticationFilter}
 * ends the request inside the security chain and no longer calls {@code chain.doFilter()}.
 * The order is therefore set explicitly in {@link RateLimitFilterConfig}; a
 * plain {@code @Component} ends up at {@code Ordered.LOWEST_PRECEDENCE} and thus far
 * <em>behind</em> security.
 */
@Slf4j
@Component
@AllowRawScheduled // System guard: cleanup of rate-limit buckets on a sub-minute interval, no cron expression possible
public class RateLimitFilter implements Filter {

    private final RateLimiter apiLimiter;
    private final RateLimiter loginLimiter;
    private final RateLimiter claudeLimiter;
    private final RateLimiter nosecTokenLimiter;
    /** SECURITY (card 314, item 16): catch-all limit for all remaining /nosec/ paths. */
    private final RateLimiter nosecPublicLimiter;
    /**
     * Card 657: a separate, considerably larger bucket for CalDAV/CardDAV. These paths lie under
     * {@code /nosec/} only so that the form login does not intercept them — they are
     * protected by basic auth and precisely not "public".
     */
    private final RateLimiter davLimiter;
    private final ClientIpResolver clientIpResolver;

    /** Default of the DAV bucket, also for the test constructors (card 657). */
    static final int DEFAULT_DAV_MAX_REQUESTS = 240;
    static final int DEFAULT_DAV_WINDOW_SECONDS = 60;

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(
            @Value("${plaintext.rate-limit.api.max-requests:60}") int apiMaxRequests,
            @Value("${plaintext.rate-limit.api.window-seconds:60}") int apiWindowSeconds,
            @Value("${plaintext.rate-limit.login.max-requests:10}") int loginMaxRequests,
            @Value("${plaintext.rate-limit.login.window-seconds:60}") int loginWindowSeconds,
            @Value("${plaintext.rate-limit.claude.max-requests:60}") int claudeMaxRequests,
            @Value("${plaintext.rate-limit.claude.window-seconds:60}") int claudeWindowSeconds,
            @Value("${plaintext.rate-limit.nosec-token.max-requests:20}") int nosecTokenMaxRequests,
            @Value("${plaintext.rate-limit.nosec-token.window-seconds:60}") int nosecTokenWindowSeconds,
            @Value("${plaintext.rate-limit.nosec-public.max-requests:60}") int nosecPublicMaxRequests,
            @Value("${plaintext.rate-limit.nosec-public.window-seconds:60}") int nosecPublicWindowSeconds,
            // Card 657: measured against the nginx access log of plaintext-app, 7 days — one
            // Apple sync run reaches peaks of 85 CalDAV requests per minute, several
            // minutes were above 60. The generic /nosec limit therefore rejected real clients
            // 73 times, among them four calls of the public appointment page by
            // a browser that shared the bucket with the concurrent sync.
            // 240 instead of 90: a first sync of a new device makes a multiple of a
            // follow-up sync, and one connection has several devices behind the same address.
            @Value("${plaintext.rate-limit.dav.max-requests:240}") int davMaxRequests,
            @Value("${plaintext.rate-limit.dav.window-seconds:60}") int davWindowSeconds,
            @Value("${plaintext.rate-limit.trusted-proxies:" + ClientIpResolver.DEFAULT_TRUSTED_PROXIES + "}")
            String trustedProxies,
            @Value("${plaintext.rate-limit.max-buckets:" + RateLimiter.DEFAULT_MAX_BUCKETS + "}") int maxBuckets) {
        this.apiLimiter = new RateLimiter(apiMaxRequests, apiWindowSeconds * 1000L, maxBuckets);
        this.loginLimiter = new RateLimiter(loginMaxRequests, loginWindowSeconds * 1000L, maxBuckets);
        this.claudeLimiter = new RateLimiter(claudeMaxRequests, claudeWindowSeconds * 1000L, maxBuckets);
        this.nosecTokenLimiter = new RateLimiter(nosecTokenMaxRequests, nosecTokenWindowSeconds * 1000L, maxBuckets);
        this.nosecPublicLimiter = new RateLimiter(nosecPublicMaxRequests, nosecPublicWindowSeconds * 1000L, maxBuckets);
        this.davLimiter = new RateLimiter(davMaxRequests, davWindowSeconds * 1000L, maxBuckets);
        this.clientIpResolver = new ClientIpResolver(trustedProxies);
        log.info("Rate limiting enabled: API={} req/{}s, Login={} req/{}s, Claude-Automation={} req/{}s, "
                        + "Nosec-Token={} req/{}s, Nosec-Public={} req/{}s, CalDAV/CardDAV={} req/{}s, "
                        + "max-buckets={}, trusted-proxies={}",
                apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds,
                nosecPublicMaxRequests, nosecPublicWindowSeconds, davMaxRequests, davWindowSeconds,
                maxBuckets, trustedProxies);
    }

    /** Convenience constructor for tests: default trusted proxies and default bucket cap. */
    RateLimitFilter(int apiMaxRequests, int apiWindowSeconds,
                    int loginMaxRequests, int loginWindowSeconds,
                    int claudeMaxRequests, int claudeWindowSeconds,
                    int nosecTokenMaxRequests, int nosecTokenWindowSeconds) {
        this(apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds,
                60, 60);
    }

    /** Convenience constructor for tests incl. the generic /nosec limit (card 314, item 16). */
    RateLimitFilter(int apiMaxRequests, int apiWindowSeconds,
                    int loginMaxRequests, int loginWindowSeconds,
                    int claudeMaxRequests, int claudeWindowSeconds,
                    int nosecTokenMaxRequests, int nosecTokenWindowSeconds,
                    int nosecPublicMaxRequests, int nosecPublicWindowSeconds) {
        this(apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds,
                nosecPublicMaxRequests, nosecPublicWindowSeconds,
                DEFAULT_DAV_MAX_REQUESTS, DEFAULT_DAV_WINDOW_SECONDS,
                ClientIpResolver.DEFAULT_TRUSTED_PROXIES, RateLimiter.DEFAULT_MAX_BUCKETS);
    }

    /** As above, but with a separate CalDAV/CardDAV limit (card 657). */
    RateLimitFilter(int apiMaxRequests, int apiWindowSeconds,
                    int loginMaxRequests, int loginWindowSeconds,
                    int claudeMaxRequests, int claudeWindowSeconds,
                    int nosecTokenMaxRequests, int nosecTokenWindowSeconds,
                    int nosecPublicMaxRequests, int nosecPublicWindowSeconds,
                    int davMaxRequests, int davWindowSeconds) {
        this(apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds,
                nosecPublicMaxRequests, nosecPublicWindowSeconds,
                davMaxRequests, davWindowSeconds,
                ClientIpResolver.DEFAULT_TRUSTED_PROXIES, RateLimiter.DEFAULT_MAX_BUCKETS);
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
                rejectJson(response);
                return;
            }
            response.setHeader("X-RateLimit-Remaining", String.valueOf(apiLimiter.getRemainingRequests(clientIp)));
        }

        // H3 hardening: brute-force brake for the token-based Claude automation endpoints.
        // /nosec/** is permitAll — without a limit one IP could guess tokens without bound.
        if (path.startsWith("/nosec/api/claude")) {
            String clientIp = getClientIp(request);
            if (!claudeLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for Claude automation API from IP: {}", clientIp);
                rejectJson(response);
                return;
            }
        }

        if (path.startsWith("/nosec/schiri-mobile")) {
            String clientIp = getClientIp(request);
            if (!nosecTokenLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for nosec-token endpoint {} from IP: {}", path, clientIp);
                rejectJson(response);
                return;
            }
        } else if (path.startsWith("/nosec/caldav/") || path.startsWith("/nosec/carddav/")) {
            // Card 657: CalDAV/CardDAV gets a bucket of its OWN instead of the generic
            // /nosec limit. Two reasons, and the second one is the more important:
            //
            // 1. Order of magnitude. An Apple sync (remindd/dataaccessd) produces
            //    dozens of PROPFIND/REPORT within seconds — measured against the nginx log of plaintext-app over 7 days:
            //    peaks of 85 requests per minute, several minutes above the limit of 60.
            //
            // 2. Different things share the same pot. The shared bucket did not only throttle the
            //    sync, but the PUBLIC appointment page along with it: on 08.08.2026
            //    a browser got HTTP 429 four times within 22 seconds on
            //    /nosec/khost/termin/<token>, because a sync was running in parallel. A visitor
            //    thereby pays for somebody else's devices.
            //
            // Deliberately did NOT raise the generic limit: the justification from card 314/16
            // (fail-closed default for every new /nosec endpoint) remains valid. Besides, CalDAV
            // is not "public" at all — it authenticates via basic auth and lies under /nosec/
            // only so that the form login does not intercept it.
            //
            // The bucket remains effective per client IP: the rejections name the real
            // public address (Graylog: "from IP: 144.2.66.241"), not the Docker bridge --
            // the ClientIpResolver evaluates X-Forwarded-For correctly.
            String clientIp = getClientIp(request);
            if (!davLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for CalDAV/CardDAV endpoint {} from IP: {}", path, clientIp);
                rejectJson(response);
                return;
            }
        } else if (path.startsWith("/nosec/") && !path.startsWith("/nosec/api/claude")) {
            // SECURITY (card 314, item 16): generic catch-all branch for ALL remaining
            // /nosec/ paths. Until now only /nosec/api/claude and /nosec/schiri-mobile were
            // covered by name; /nosec/wiki and /nosec/challenge (plaintext-app) had
            // NO limit despite a statement to the contrary in their controller Javadocs. Because
            // /nosec/** is permitAll, the default has to be fail-closed: new /nosec endpoints
            // of a consuming app are automatically limited from now on, instead of standing
            // open unnoticed until the next audit.
            //
            // Deliberately a SEPARATE, more generous limiter instead of the strict token limit:
            // behind /nosec/wiki there are publicly readable pages that several visitors
            // behind the same NAT address may fetch. The strict limit still applies
            // to the token-guessing paths above.
            String clientIp = getClientIp(request);
            if (!nosecPublicLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for public nosec endpoint {} from IP: {}", path, clientIp);
                rejectJson(response);
                return;
            }
        }

        // SECURITY (card 314, item 10): /password-reset and /register are permitAll and
        // both send mails to an address chosen by the caller. Without a limit this can be
        // abused both to misuse the mail dispatch as a spam relay and to probe the
        // existence of accounts. They run on the login limiter, because they
        // follow the same abuse pattern (login/account operations per IP).
        if (("POST".equalsIgnoreCase(request.getMethod()))
                && (path.startsWith("/password-reset") || path.startsWith("/register"))) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                rejectLogin(response, path, clientIp);
                return;
            }
        }

        if (path.equals("/login") && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                rejectLogin(response, path, clientIp);
                return;
            }
            // The limit is meant to slow down guessing attempts, not real logins. Therefore the
            // token is returned after a demonstrably successful login. Deliberately
            // "consume and refund if applicable" instead of "consume only on failure": should the
            // success detection ever fail, the attempt is counted in case of doubt (fail-closed) instead of not at all.
            filterChain.doFilter(servletRequest, servletResponse);
            if (isAuthenticated(request)) {
                loginLimiter.refund(clientIp);
            }
            return;
        }

        // Card 560: the branch for /token-login has been dropped, because the endpoint no longer
        // exists. It thereby falls under anyRequest().authenticated() and is no longer reachable
        // for anonymous users at all -- a rate limit on it would have nothing left to throttle.

        // Status report 29.08.2026 (H3): the second factor was the only login stage without a
        // brake. The lockout key "totp:<user>" in the TotpVerificationController counts
        // failed attempts per account; this branch additionally throttles per address, so that an attacker
        // who has the password cannot try the six digits second by second. No
        // refund: a legitimate user needs exactly one attempt.
        if (path.equals("/login/totp") && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                rejectLogin(response, path, clientIp);
                return;
            }
        }

        if (path.equals("/ott/generate") && "POST".equalsIgnoreCase(request.getMethod())) {
            String clientIp = getClientIp(request);
            if (!loginLimiter.tryConsume(clientIp)) {
                rejectLogin(response, path, clientIp);
                return;
            }
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    /**
     * Does the session hold an authenticated (non-anonymous) user after the request? What is read
     * is the session store of Spring's {@code HttpSessionSecurityContextRepository} — the
     * {@code SecurityContextHolder} has already been cleared at this point. Deliberately no
     * evaluation of the redirect target: that depends on the configuration of the failure handler.
     */
    private boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object context = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(context instanceof SecurityContext securityContext)) {
            return false;
        }
        Authentication authentication = securityContext.getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private void rejectJson(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"error\":\"Too many requests\"}");
    }

    /**
     * Response for the login paths. Previously {@code setStatus(429)} was combined here with a
     * subsequent {@code sendRedirect(...)} — the redirect overrides the status,
     * so in the end a 302 went out and the client could not tell at all that it had been
     * limited (the target page does not evaluate {@code error=rate_limited} anywhere either). Now a
     * real 429 with {@code Retry-After} and a short, linked message goes out.
     */
    private void rejectLogin(HttpServletResponse response, String path, String clientIp) throws IOException {
        log.warn("Rate limit exceeded for login path {} from IP: {}", path, clientIp);
        response.setStatus(429);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Retry-After", "60");
        response.getWriter().write("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<title>Zu viele Anmeldeversuche</title></head><body>"
                + "<h1>Zu viele Anmeldeversuche</h1>"
                + "<p>Bitte in einer Minute erneut versuchen.</p>"
                + "<p><a href=\"/login.xhtml?error=rate_limited\">Zurueck zur Anmeldung</a></p>"
                + "</body></html>");
    }

    /**
     * SECURITY (card 303, finding 2): previously the first — freely choosable by the client —
     * {@code X-Forwarded-For} element was used as the bucket key. For details on the current
     * procedure see {@link ClientIpResolver}. {@code X-Real-IP} is deliberately no longer
     * evaluated: the header carries no hop chain and is therefore not verifiable.
     */
    String getClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredBuckets() {
        apiLimiter.cleanup();
        loginLimiter.cleanup();
        claudeLimiter.cleanup();
        nosecTokenLimiter.cleanup();
        nosecPublicLimiter.cleanup();
    }
}
