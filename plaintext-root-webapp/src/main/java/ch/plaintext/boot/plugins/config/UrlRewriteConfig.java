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
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1); // After Swagger filter
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
            String path = httpRequest.getRequestURI();

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
