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
        // Card 612 (08.08.2026): + 30 instead of + 1.
        //
        // This filter has to run AFTER the pathParameterFilter, so that a URL such as
        // /login.html;jsessionid=... already arrives here cleaned up -- otherwise it does not
        // end in .html and the urlPattern does not match.
        //
        // The pathParameterFilter in turn had to move behind Spring's ForwardedHeaderFilter
        // (+10), because otherwise its sendRedirect() is built with the connector's scheme
        // (http) instead of the one of the original request (https). Both conditions together
        // give:
        //
        //   ForwardedHeaderFilter  +10   fix up scheme/host
        //   PathParameterFilter    +20   clean up the path, redirect with the correct scheme
        //   htmlRewriteFilter      +30   <- here, only ever sees cleaned-up paths
        //
        // Still after the Swagger filter and before Spring Security (-100).
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
            // Strip path parameters (Card 612): the filter is selected via the servlet path
            // (*.html) — where the container has already removed ";jsessionid=..." — but decides
            // here based on getRequestURI(), where it is still present. Without this cut
            // "/kontakte.html;jsessionid=X" does not end in ".html", the rewrite to .xhtml does
            // not happen and the request runs into a resource that does not physically exist.
            // Normally the pathParameterFilter has already cleared this away beforehand; this
            // line keeps the module correct even without it.
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
         * Removes everything from the first semicolon onwards in every path segment (Card 612).
         * Delegates to
         * {@link PathParameterConfig.PathParameterFilter#stripPathParameters(String)} so that
         * there is exactly one source for the rule.
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
