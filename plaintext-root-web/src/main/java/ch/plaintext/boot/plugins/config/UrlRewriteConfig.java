/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * URL Rewrite Filter to map .html to .xhtml for JSF pages
 * This allows users to access pages with .html extension while keeping Swagger working
 */
@Slf4j
@Configuration
public class UrlRewriteConfig {

    @Bean
    public FilterRegistrationBean<HtmlToXhtmlRewriteFilter> htmlRewriteFilter() {
        FilterRegistrationBean<HtmlToXhtmlRewriteFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HtmlToXhtmlRewriteFilter());
        registration.addUrlPatterns("*.html", "*.htm");
        // Karte 612 (08.08.2026): + 30 statt + 1.
        //
        // Dieser Filter muss NACH dem pathParameterFilter laufen, damit eine URL wie
        // /login.html;jsessionid=... hier bereits bereinigt ankommt -- sonst endet sie nicht
        // auf .html und das urlPattern greift nicht.
        //
        // Der pathParameterFilter wiederum musste hinter Springs ForwardedHeaderFilter (+10)
        // wandern, weil sein sendRedirect() sonst mit dem Connector-Schema (http) statt dem
        // der urspruenglichen Anfrage (https) gebaut wird. Beide Bedingungen zusammen ergeben:
        //
        //   ForwardedHeaderFilter  +10   Scheme/Host korrigieren
        //   PathParameterFilter    +20   Pfad bereinigen, Redirect mit korrektem Schema
        //   htmlRewriteFilter      +30   <- hier, sieht nur bereinigte Pfade
        //
        // Weiterhin nach dem Swagger-Filter und vor Spring Security (-100).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setName("htmlRewriteFilter");
        return registration;
    }

    /**
     * Rewrites .html requests to .xhtml for JSF pages only
     * Excludes Swagger and other technical URLs
     */
    public static class HtmlToXhtmlRewriteFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            // Path-Parameter abschneiden (Karte 612): Der Filter wird ueber den Servlet-Pfad
            // ausgewaehlt (*.html) — dort entfernt der Container ";jsessionid=..." bereits —,
            // entscheidet hier aber ueber getRequestURI(), wo es noch drinsteht. Ohne diesen
            // Schnitt endet "/kontakte.html;jsessionid=X" nicht auf ".html", die Umschreibung
            // auf .xhtml unterbleibt und der Request laeuft auf eine Ressource, die es physisch
            // nicht gibt. Der pathParameterFilter raeumt das im Normalfall schon vorher weg;
            // diese Zeile haelt das Modul auch ohne ihn richtig.
            String path = stripPathParameters(httpRequest.getRequestURI());

            // Skip Swagger, OAuth2 and technical URLs
            if (path.contains("/swagger") ||
                path.contains("/swagger-ui") ||
                path.contains("/webjars") ||
                path.contains("/api-docs") ||
                path.contains("/v3/api-docs") ||
                path.contains("/actuator") ||
                path.contains("/oauth2/") ||
                path.contains("/login/oauth2/")) {
                chain.doFilter(request, response);
                return;
            }

            // Rewrite .html/.htm to .xhtml for JSF pages
            if (path.endsWith(".html") || path.endsWith(".htm")) {
                String xhtmlPath = path.replaceAll("\\.(html|htm)$", ".xhtml");
                log.info("HtmlRewriteFilter: Rewriting " + path + " to " + xhtmlPath);
                httpRequest.getRequestDispatcher(xhtmlPath).forward(request, response);
                return;
            }

            chain.doFilter(request, response);
        }

        /**
         * Entfernt in jedem Pfadsegment alles ab dem ersten Semikolon (Karte 612). Delegiert an
         * {@link PathParameterConfig.PathParameterFilter#stripPathParameters(String)}, damit es
         * fuer die Regel genau eine Quelle gibt.
         */
        static String stripPathParameters(String uri) {
            if (uri == null || uri.indexOf(';') < 0) {
                return uri;
            }
            return PathParameterConfig.PathParameterFilter.stripPathParameters(uri);
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            log.info("HtmlRewriteFilter initialized");
        }

        @Override
        public void destroy() {
            log.info("HtmlRewriteFilter destroyed");
        }
    }
}
