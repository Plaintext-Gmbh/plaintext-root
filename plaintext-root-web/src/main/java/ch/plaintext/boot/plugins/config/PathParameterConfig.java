/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * Entfernt Path-Parameter (das Semikolon-Anhaengsel einer URL, typischerweise
 * {@code ;jsessionid=...}) aus dem Request-Pfad, bevor irgendetwas anderes ihn auswertet.
 *
 * <p><b>Warum (Karte 612):</b> Der Servlet-Container haengt die Sitzungskennung an die URL, wenn
 * ein Client keine Cookies mitschickt. Solche URLs landen in Lesezeichen und werden
 * weitergegeben. Gemessen am 07.08.2026 an guild-INT und app-PROD:
 *
 * <pre>
 *   angemeldet,  GET /;jsessionid=&lt;gueltig&gt;   -&gt; 400 Bad Request, ohne jede Logzeile
 *   angemeldet,  GET /;foo=bar                 -&gt; 400   (jedes Semikolon genuegt)
 *   anonym,      GET /login.html;foo=bar       -&gt; 302 auf die Anmeldung, obwohl permitAll
 * </pre>
 *
 * Ausloeser ist nicht die Sitzung, sondern das Semikolon: die Pfad-Auswertung trifft die
 * passende Regel nicht mehr. Der 400 entsteht per {@code sendError}, deshalb steht er in keinem
 * Log — er war nur fuer den betroffenen Benutzer sichtbar.
 *
 * <p><b>Und die Fehlerseite gab die Sitzungskennung zurueck</b> ({@code "path":"/;jsessionid=..."}).
 * Wer den Fehler meldete, gab damit seine angemeldete Sitzung weiter. Nach dieser Umleitung
 * entsteht die Fehlerseite gar nicht mehr.
 *
 * <p><b>Ordnung:</b> {@code HIGHEST_PRECEDENCE + 20} — nach Springs ForwardedHeaderFilter (+10),
 * damit die Weiterleitung das Schema der urspruenglichen Anfrage traegt, und weiterhin vor
 * Spring Security (Order -100) und
 * vor dem {@code htmlRewriteFilter} (HIGHEST_PRECEDENCE + 1), der ueber
 * {@code getRequestURI()} entscheidet und deshalb an derselben Stelle stolpert.
 *
 * <p><b>Ergaenzend</b> steht in {@code application.yml} seit derselben Karte
 * {@code server.servlet.session.tracking-modes: cookie}: Dieser Filter faengt die Altlasten,
 * die Einstellung stoppt den Nachschub.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class PathParameterConfig {

    @Bean
    public FilterRegistrationBean<PathParameterFilter> pathParameterFilter() {
        FilterRegistrationBean<PathParameterFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PathParameterFilter());
        registration.addUrlPatterns("/*");
        // Karte 612 (08.08.2026): HIGHEST_PRECEDENCE + 20 statt HIGHEST_PRECEDENCE.
        //
        // sendRedirect() unten bekommt einen RELATIVEN Pfad; die absolute URL baut der
        // Container daraus -- mit dem Schema, das ER sieht. Lief dieser Filter vor Springs
        // ForwardedHeaderFilter, war das noch das Schema des Connectors (http), nicht das der
        // urspruenglichen Anfrage (https). Gemessen am 08.08.2026 auf allen vier Hosts:
        //
        //   GET https://app.plaintext.ch/;jsessionid=...  ->  302  Location: http://app.plaintext.ch/
        //
        // Die Weiterleitung funktionierte also, stufte den Benutzer aber auf Klartext-HTTP
        // zurueck -- und seit Karte 620 traegt das Session-Cookie Secure, wird dorthin also gar
        // nicht mehr gesendet. Aus einem Schoenheitsfehler wurde damit ein Anmeldeproblem.
        //
        // Resultierende Reihenfolge (root; die Werte stehen in RateLimitFilterConfig):
        //   RateLimitFilter        HIGHEST_PRECEDENCE        rohe Peer-Adresse + XFF-Kette
        //   ForwardedHeaderFilter  HIGHEST_PRECEDENCE + 10   Scheme/Host/RemoteAddr korrigieren
        //   PathParameterFilter    HIGHEST_PRECEDENCE + 20   <- hier, mit korrektem Schema
        //   SecurityFilterChain    -100
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("pathParameterFilter");
        return registration;
    }

    public static class PathParameterFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            String uri = httpRequest.getRequestURI();

            // Normalfall: kein Semikolon -> unveraendert weiter, kein Aufwand.
            if (uri == null || uri.indexOf(';') < 0) {
                chain.doFilter(request, response);
                return;
            }

            String cleanedPath = stripPathParameters(uri);
            String method = httpRequest.getMethod();

            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                String target = appendQueryString(cleanedPath, httpRequest.getQueryString());
                // 302, nicht 301: Die Quell-URL traegt eine Sitzungskennung, ist also fuer jeden
                // Besucher eine andere und kehrt nie wieder. Ein dauerhaft gecachter 301 auf eine
                // solche Adresse ist wertlos und aus Browser-Caches kaum mehr zu entfernen.
                log.warn("Pfad-Parameter in der URL entfernt und umgeleitet: {} -> {} ({})",
                        maskParameterValues(uri), target, method);
                httpResponse.sendRedirect(target);
                return;
            }

            // Andere Methoden (POST, und PROPFIND/REPORT fuer CalDAV/CardDAV) werden NICHT
            // umgeleitet: Ein 302 verloere den Rumpf. Sie laufen mit bereinigtem Pfad weiter.
            log.warn("Pfad-Parameter im Pfad entfernt (kein Redirect wegen Methode {}): {} -> {}",
                    method, maskParameterValues(uri), cleanedPath);
            chain.doFilter(new StrippedPathRequest(httpRequest, cleanedPath), response);
        }

        /**
         * Entfernt in JEDEM Pfadsegment alles ab dem ersten Semikolon — {@code ;jsessionid} kann
         * an jedem Segment haengen, nicht nur am letzten ({@code /a;jsessionid=X/b}).
         */
        static String stripPathParameters(String uri) {
            String[] segments = uri.split("/", -1);
            StringBuilder cleaned = new StringBuilder(uri.length());
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    cleaned.append('/');
                }
                String segment = segments[i];
                int semicolon = segment.indexOf(';');
                cleaned.append(semicolon < 0 ? segment : segment.substring(0, semicolon));
            }
            String result = cleaned.toString();
            if (result.isEmpty()) {
                return "/";
            }
            // Schutz gegen Open Redirect: "//host/pfad" ist eine protokollrelative URL und wuerde
            // den Browser auf einen fremden Host schicken. Ein Pfad, der nach dem Bereinigen so
            // beginnt, wird auf die Wurzel zurueckgefuehrt statt weitergereicht.
            if (result.startsWith("//")) {
                return "/";
            }
            return result;
        }

        static String appendQueryString(String path, String queryString) {
            if (queryString == null || queryString.isEmpty()) {
                return path;
            }
            // Defensiv: Zeilenumbrueche im Query-String wuerden im Location-Header einen
            // Header-Injection-Versuch bedeuten. Der Container laesst sie nicht durch; faellt das
            // einmal weg, wird hier lieber der Query-String verworfen als der Header vergiftet.
            if (queryString.indexOf('\r') >= 0 || queryString.indexOf('\n') >= 0) {
                return path;
            }
            return path + "?" + queryString;
        }

        /**
         * Ersetzt die WERTE der Path-Parameter durch {@code ***}. Eine Sitzungskennung ist ein
         * Zugang ohne Passwort und ohne zweiten Faktor — sie gehoert nicht ins Log (genau so ist
         * sie in Karte 612 in eine Kartenbeschreibung gelangt).
         */
        static String maskParameterValues(String uri) {
            return uri.replaceAll(";([^;/=]+)=[^;/]*", ";$1=***");
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            log.info("PathParameterFilter initialized");
        }

        @Override
        public void destroy() {
            log.info("PathParameterFilter destroyed");
        }
    }

    /**
     * Reicht den Request mit bereinigtem Pfad weiter. {@code getServletPath()} und
     * {@code getPathInfo()} liefert der Container bereits ohne Path-Parameter; nur
     * {@code getRequestURI()}/{@code getRequestURL()} tragen sie noch.
     */
    static class StrippedPathRequest extends HttpServletRequestWrapper {

        private final String cleanedUri;

        StrippedPathRequest(HttpServletRequest request, String cleanedUri) {
            super(request);
            this.cleanedUri = cleanedUri;
        }

        @Override
        public String getRequestURI() {
            return cleanedUri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer original = super.getRequestURL();
            if (original == null) {
                return null;
            }
            String url = original.toString();
            int pathStart = url.indexOf('/', url.indexOf("//") + 2);
            if (pathStart < 0) {
                return new StringBuffer(url);
            }
            return new StringBuffer(url.substring(0, pathStart)).append(cleanedUri);
        }
    }
}
