/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter that intercepts every request when maintenance mode is on
 * and serves a static 503 page to non-bypass users.
 *
 * <p>The filter is ordered with {@link Ordered#LOWEST_PRECEDENCE} so it runs
 * after the Spring Security filter chain — by then
 * {@link SecurityContextHolder} is populated and we can check authorities
 * to decide whether the caller has a bypass role.
 *
 * <p>HTML in the {@code message} property is escaped before being placed in
 * the response body, so an operator who pastes user-provided text into the
 * config cannot accidentally inject HTML/script into the maintenance page.
 */
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class MaintenanceModeFilter implements Filter {

    private final MaintenanceModeProperties properties;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!(req instanceof HttpServletRequest request) || !(res instanceof HttpServletResponse response)) {
            chain.doFilter(req, res);
            return;
        }

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isAlwaysAllowed(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (hasBypassRole()) {
            chain.doFilter(request, response);
            return;
        }

        writeMaintenanceResponse(response);
    }

    private boolean isAlwaysAllowed(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : properties.getAlwaysAllowedPathPrefixes()) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBypassRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (properties.getBypassRoles().contains(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void writeMaintenanceResponse(HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("text/html; charset=utf-8");
        response.setHeader("Retry-After", String.valueOf(properties.getRetryAfterSeconds()));
        response.setHeader("Cache-Control", "no-store, must-revalidate");

        String body = renderMaintenancePage(properties.getMessage());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    static String renderMaintenancePage(String rawMessage) {
        String escaped = escapeHtml(rawMessage == null ? "" : rawMessage);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Maintenance &middot; Plaintext</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <style>
                        :root { color-scheme: light dark; }
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            min-height: 100vh;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI",
                                         Roboto, "Helvetica Neue", Arial, sans-serif;
                            background: linear-gradient(135deg, #f8fbff 0%%, #e8f4fd 100%%);
                            color: #333;
                        }
                        @media (prefers-color-scheme: dark) {
                            body { background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%); color: #e0e0e0; }
                            .card { background: #2a2b3d; border-color: #3c3d4f; }
                        }
                        .card {
                            background: #fff;
                            border: 1px solid #dee2e6;
                            border-radius: 12px;
                            padding: 2.5rem 2rem;
                            max-width: 36rem;
                            text-align: center;
                            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08);
                        }
                        h1 { margin: 0 0 0.5rem 0; font-size: 1.6rem; }
                        p { margin: 0.75rem 0; line-height: 1.5; }
                        .subtle { color: #6c757d; font-size: 0.9rem; }
                    </style>
                </head>
                <body>
                    <main class="card">
                        <h1>We&rsquo;ll be back shortly</h1>
                        <p>%s</p>
                        <p class="subtle">If this lasts longer than expected, contact your administrator.</p>
                    </main>
                </body>
                </html>
                """.formatted(escaped);
    }

    static String escapeHtml(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
