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
 * Removes path parameters (the semicolon suffix of a URL, typically
 * {@code ;jsessionid=...}) from the request path before anything else evaluates it.
 *
 * <p><b>Why (Card 612):</b> The servlet container appends the session id to the URL when a
 * client sends no cookies. Such URLs end up in bookmarks and get passed around.
 * Measured on 07.08.2026 against guild-INT and app-PROD:
 *
 * <pre>
 *   logged in,   GET /;jsessionid=&lt;valid&gt;     -&gt; 400 Bad Request, without a single log line
 *   logged in,   GET /;foo=bar                 -&gt; 400   (any semicolon is enough)
 *   anonymous,   GET /login.html;foo=bar       -&gt; 302 to the login, even though permitAll
 * </pre>
 *
 * The trigger is not the session but the semicolon: path matching no longer finds the
 * applicable rule. The 400 is raised via {@code sendError}, which is why it shows up in no
 * log — it was visible only to the affected user.
 *
 * <p><b>And the error page handed the session id back</b> ({@code "path":"/;jsessionid=..."}).
 * Whoever reported the error passed on their logged-in session along with it. With this redirect
 * in place the error page is never produced at all.
 *
 * <p><b>Order:</b> {@code HIGHEST_PRECEDENCE + 20} — after Spring's ForwardedHeaderFilter (+10),
 * so that the redirect carries the scheme of the original request, and still before
 * Spring Security (order -100) and
 * before the {@code htmlRewriteFilter} (HIGHEST_PRECEDENCE + 1), which decides based on
 * {@code getRequestURI()} and therefore trips over the very same thing.
 *
 * <p><b>In addition</b>, since the same card {@code application.yml} carries
 * {@code server.servlet.session.tracking-modes: cookie}: this filter catches the legacy URLs,
 * the setting stops new ones from being created.
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
        // Card 612 (08.08.2026): HIGHEST_PRECEDENCE + 20 instead of HIGHEST_PRECEDENCE.
        //
        // sendRedirect() below is given a RELATIVE path; the container builds the absolute URL
        // from it -- using the scheme IT sees. When this filter ran before Spring's
        // ForwardedHeaderFilter, that was still the connector's scheme (http), not the one of
        // the original request (https). Measured on 08.08.2026 on all four hosts:
        //
        //   GET https://app.plaintext.ch/;jsessionid=...  ->  302  Location: http://app.plaintext.ch/
        //
        // So the redirect worked, but it downgraded the user to plaintext HTTP -- and since
        // Card 620 the session cookie is marked Secure, so it is no longer sent there at all.
        // What had been a cosmetic flaw thereby turned into a login problem.
        //
        // Resulting order (root; the values live in RateLimitFilterConfig):
        //   RateLimitFilter        HIGHEST_PRECEDENCE        raw peer address + XFF chain
        //   ForwardedHeaderFilter  HIGHEST_PRECEDENCE + 10   fix up scheme/host/remoteAddr
        //   PathParameterFilter    HIGHEST_PRECEDENCE + 20   <- here, with the correct scheme
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

            // Normal case: no semicolon -> pass through unchanged, no work done.
            if (uri == null || uri.indexOf(';') < 0) {
                chain.doFilter(request, response);
                return;
            }

            String cleanedPath = stripPathParameters(uri);
            String method = httpRequest.getMethod();

            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                String target = appendQueryString(cleanedPath, httpRequest.getQueryString());
                // 302, not 301: the source URL carries a session id, so it is different for
                // every visitor and never comes back. A permanently cached 301 for such an
                // address is worthless and next to impossible to evict from browser caches.
                log.warn("Pfad-Parameter in der URL entfernt und umgeleitet: {} -> {} ({})",
                        maskParameterValues(uri), target, method);
                httpResponse.sendRedirect(target);
                return;
            }

            // Other methods (POST, plus PROPFIND/REPORT for CalDAV/CardDAV) are NOT redirected:
            // a 302 would lose the body. They continue with the cleaned-up path.
            log.warn("Pfad-Parameter im Pfad entfernt (kein Redirect wegen Methode {}): {} -> {}",
                    method, maskParameterValues(uri), cleanedPath);
            chain.doFilter(new StrippedPathRequest(httpRequest, cleanedPath), response);
        }

        /**
         * Removes everything from the first semicolon onwards in EVERY path segment —
         * {@code ;jsessionid} can be attached to any segment, not just the last one
         * ({@code /a;jsessionid=X/b}).
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
            // Protection against open redirects: "//host/path" is a protocol-relative URL and
            // would send the browser to a foreign host. A path that starts like this after
            // cleaning is folded back to the root instead of being passed on.
            if (result.startsWith("//")) {
                return "/";
            }
            return result;
        }

        static String appendQueryString(String path, String queryString) {
            if (queryString == null || queryString.isEmpty()) {
                return path;
            }
            // Defensive: line breaks in the query string would amount to a header injection
            // attempt in the Location header. The container does not let them through; should
            // that ever stop being true, we would rather drop the query string here than poison
            // the header.
            if (queryString.indexOf('\r') >= 0 || queryString.indexOf('\n') >= 0) {
                return path;
            }
            return path + "?" + queryString;
        }

        /**
         * Replaces the VALUES of the path parameters with {@code ***}. A session id is access
         * without a password and without a second factor — it does not belong in the log (that
         * is exactly how one ended up in a card description in Card 612).
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
     * Passes the request on with a cleaned-up path. The container already delivers
     * {@code getServletPath()} and {@code getPathInfo()} without path parameters; only
     * {@code getRequestURI()}/{@code getRequestURL()} still carry them.
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
