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
 * <p>SECURITY (Karte 303, Befund 1): Der Filter muss <b>vor</b> der
 * {@code springSecurityFilterChain} laufen, sonst sind die Zweige fuer {@code POST /login} und
 * {@code POST /ott/generate} toter Code — Springs {@code UsernamePasswordAuthenticationFilter}
 * beendet den Request innerhalb der Security-Chain und ruft {@code chain.doFilter()} nicht mehr
 * auf. Die Reihenfolge wird deshalb in {@link RateLimitFilterConfig} explizit gesetzt; ein
 * blosses {@code @Component} landet bei {@code Ordered.LOWEST_PRECEDENCE} und damit weit
 * <em>hinter</em> Security.
 */
@Slf4j
@Component
@AllowRawScheduled // System-Waechter: Rate-Limit-Buckets-Cleanup im Sub-Minuten-Rahmen, keine Cron-Expression moeglich
public class RateLimitFilter implements Filter {

    private final RateLimiter apiLimiter;
    private final RateLimiter loginLimiter;
    private final RateLimiter claudeLimiter;
    private final RateLimiter nosecTokenLimiter;
    /** SECURITY (Karte 314, Punkt 16): Auffang-Limit fuer alle uebrigen /nosec/-Pfade. */
    private final RateLimiter nosecPublicLimiter;
    /**
     * Karte 657: Eigener, deutlich groesserer Eimer fuer CalDAV/CardDAV. Diese Pfade liegen nur
     * deshalb unter {@code /nosec/}, damit die Formular-Anmeldung sie nicht abfaengt — sie sind
     * per Basic-Auth geschuetzt und gerade nicht „public".
     */
    private final RateLimiter davLimiter;
    private final ClientIpResolver clientIpResolver;

    /** Vorgabe des DAV-Eimers, auch fuer die Test-Konstruktoren (Karte 657). */
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
            // Karte 657: gemessen am nginx-Zugriffslog von plaintext-app, 7 Tage — ein
            // Apple-Sync-Durchlauf erreicht Spitzen von 85 CalDAV-Anfragen pro Minute, mehrere
            // Minuten lagen ueber 60. Das generische /nosec-Limit hat deshalb 73 Mal echte
            // Clients abgewiesen, darunter vier Aufrufe der oeffentlichen Terminseite durch
            // einen Browser, der sich den Eimer mit dem gleichzeitigen Sync teilte.
            // 240 statt 90: Ein Erstsync eines neuen Geraets macht ein Vielfaches eines
            // Folgesyncs, und ein Anschluss hat mehrere Geraete hinter derselben Adresse.
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

    /** Bequemer Konstruktor fuer Tests: Default-Trusted-Proxies und Default-Bucket-Deckel. */
    RateLimitFilter(int apiMaxRequests, int apiWindowSeconds,
                    int loginMaxRequests, int loginWindowSeconds,
                    int claudeMaxRequests, int claudeWindowSeconds,
                    int nosecTokenMaxRequests, int nosecTokenWindowSeconds) {
        this(apiMaxRequests, apiWindowSeconds, loginMaxRequests, loginWindowSeconds,
                claudeMaxRequests, claudeWindowSeconds, nosecTokenMaxRequests, nosecTokenWindowSeconds,
                60, 60);
    }

    /** Bequemer Konstruktor fuer Tests inkl. des generischen /nosec-Limits (Karte 314, Punkt 16). */
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

    /** Wie oben, aber mit eigener CalDAV/CardDAV-Grenze (Karte 657). */
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

        // H3-Härtung: Brute-Force-Bremse für die tokenbasierten Claude-Automation-Endpoints.
        // /nosec/** ist permitAll — ohne Limit könnte eine IP unbegrenzt Token raten.
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
            // Karte 657: CalDAV/CardDAV bekommt einen EIGENEN Eimer statt des generischen
            // /nosec-Limits. Zwei Gruende, und der zweite ist der wichtigere:
            //
            // 1. Groessenordnung. Ein Apple-Sync (remindd/dataaccessd) erzeugt binnen Sekunden
            //    dutzende PROPFIND/REPORT — gemessen am nginx-Log von plaintext-app ueber 7 Tage:
            //    Spitzen von 85 Anfragen pro Minute, mehrere Minuten ueber der Grenze von 60.
            //
            // 2. Es sind verschiedene Dinge im selben Topf. Der geteilte Eimer hat nicht nur den
            //    Sync gebremst, sondern die OEFFENTLICHE Terminseite gleich mit: Am 08.08.2026
            //    bekam ein Browser vier Mal in 22 Sekunden HTTP 429 auf
            //    /nosec/khost/termin/<token>, weil parallel synchronisiert wurde. Ein Besucher
            //    zahlt so fuer die Geraete eines anderen.
            //
            // Bewusst NICHT das generische Limit angehoben: Die Begruendung aus Karte 314/16
            // (fail-closed-Default fuer jeden neuen /nosec-Endpunkt) bleibt gueltig. CalDAV ist
            // ausserdem gar nicht „public" — es authentisiert sich per Basic-Auth und liegt nur
            // deshalb unter /nosec/, damit die Formular-Anmeldung es nicht abfaengt.
            //
            // Der Eimer bleibt pro Client-IP wirksam: Die Ablehnungen nennen die echte
            // oeffentliche Adresse (Graylog: "from IP: 144.2.66.241"), nicht die Docker-Bridge --
            // der ClientIpResolver wertet X-Forwarded-For korrekt aus.
            String clientIp = getClientIp(request);
            if (!davLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for CalDAV/CardDAV endpoint {} from IP: {}", path, clientIp);
                rejectJson(response);
                return;
            }
        } else if (path.startsWith("/nosec/") && !path.startsWith("/nosec/api/claude")) {
            // SECURITY (Karte 314, Punkt 16): generischer Auffangzweig fuer ALLE uebrigen
            // /nosec/-Pfade. Bisher waren nur /nosec/api/claude und /nosec/schiri-mobile
            // namentlich abgedeckt; /nosec/wiki und /nosec/challenge (plaintext-app) hatten
            // trotz gegenteiliger Zusage in ihren Controller-Javadocs KEIN Limit. Weil
            // /nosec/** permitAll ist, muss der Default fail-closed sein: neue /nosec-Endpunkte
            // einer konsumierenden App sind ab sofort automatisch limitiert, statt bis zum
            // naechsten Audit unbemerkt offen zu stehen.
            //
            // Bewusst ein EIGENER, grosszuegigerer Limiter statt des strengen Token-Limits:
            // hinter /nosec/wiki stehen oeffentlich lesbare Seiten, die mehrere Besucher
            // hinter derselben NAT-Adresse abrufen koennen. Das strenge Limit gilt weiterhin
            // fuer die tokenratenden Pfade oben.
            String clientIp = getClientIp(request);
            if (!nosecPublicLimiter.tryConsume(clientIp)) {
                log.warn("Rate limit exceeded for public nosec endpoint {} from IP: {}", path, clientIp);
                rejectJson(response);
                return;
            }
        }

        // SECURITY (Karte 314, Punkt 10): /password-reset und /register sind permitAll und
        // versenden beide Mails an eine vom Aufrufer gewaehlte Adresse. Ohne Limit laesst sich
        // darueber sowohl der Mailversand als Spam-Relais missbrauchen als auch die
        // Existenz von Konten durchprobieren. Sie laufen auf dem Login-Limiter, weil sie
        // demselben Missbrauchsmuster folgen (Anmelde-/Kontooperationen pro IP).
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
            // Das Limit soll Rateversuche bremsen, nicht echte Anmeldungen. Deshalb wird das
            // Token nach einem nachweislich erfolgreichen Login zurueckgegeben. Bewusst
            // "verbrauchen und ggf. erstatten" statt "nur bei Misserfolg verbrauchen": faellt die
            // Erfolgserkennung je aus, wird im Zweifel gezaehlt (fail-closed) statt gar nicht.
            filterChain.doFilter(servletRequest, servletResponse);
            if (isAuthenticated(request)) {
                loginLimiter.refund(clientIp);
            }
            return;
        }

        // Karte 560: Der Zweig fuer /token-login ist entfallen, weil es den Endpunkt nicht mehr
        // gibt. Er faellt damit unter anyRequest().authenticated() und ist fuer Anonyme gar nicht
        // mehr erreichbar -- ein Rate-Limit darauf haette nichts mehr zu bremsen.

        // Zustandsbericht 29.08.2026 (H3): Der zweite Faktor war die einzige Anmeldestufe ohne
        // Bremse. Der Lockout-Schluessel "totp:<user>" im TotpVerificationController zaehlt
        // Fehlversuche pro Konto; dieser Zweig bremst zusaetzlich pro Adresse, damit ein Angreifer
        // mit dem Passwort die sechs Ziffern nicht im Sekundentakt durchprobieren kann. Kein
        // Refund: ein legitimer Nutzer braucht genau einen Versuch.
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
     * Haelt die Session nach dem Request einen angemeldeten (nicht anonymen) Benutzer? Gelesen
     * wird die Session-Ablage von Springs {@code HttpSessionSecurityContextRepository} — der
     * {@code SecurityContextHolder} ist an dieser Stelle bereits geleert. Bewusst kein Auswerten
     * des Redirect-Ziels: das haengt an der Konfiguration des Failure-Handlers.
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
     * Antwort fuer die Login-Pfade. Frueher wurde hier {@code setStatus(429)} mit einem
     * anschliessenden {@code sendRedirect(...)} kombiniert — der Redirect ueberschreibt den Status,
     * am Ende ging also ein 302 raus und der Client konnte gar nicht erkennen, dass er limitiert
     * wurde (die Ziel-Seite wertet {@code error=rate_limited} auch nirgends aus). Jetzt geht ein
     * echter 429 mit {@code Retry-After} und einer kurzen, verlinkten Meldung raus.
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
     * SECURITY (Karte 303, Befund 2): frueher wurde das erste — vom Client frei waehlbare —
     * {@code X-Forwarded-For}-Element als Bucket-Schluessel benutzt. Details zum jetzigen
     * Verfahren siehe {@link ClientIpResolver}. {@code X-Real-IP} wird bewusst nicht mehr
     * ausgewertet: der Header traegt keine Hop-Kette und ist damit nicht verifizierbar.
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
