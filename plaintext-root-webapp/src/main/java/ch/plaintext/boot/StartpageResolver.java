/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Resolves the individual start/landing page of a user from their {@link GrantedAuthority}s and
 * secures it. A configured start page (authority {@code PROPERTY_STARTPAGE_<page>}) is
 * only used if it looks like a valid app-internal page path; otherwise
 * (empty/invalid) it falls back to {@link #DEFAULT_PAGE}, so that no user is locked out of the
 * start page. This keeps individual start pages working, while a broken value always leads
 * reliably to index.html.
 *
 * @author plaintext.ch
 */
public final class StartpageResolver {

    /** Default landing page when no valid individual start page is set. */
    public static final String DEFAULT_PAGE = "index.html";

    private static final String STARTPAGE_PREFIX = "PROPERTY_STARTPAGE_";

    /**
     * Permits a relative page path made of path segments (letters/digits/{@code _-}) that ends in
     * {@code .html} or {@code .xhtml}, optionally followed by a simple query string -
     * no scheme, no leading slash (also no protocol-relative {@code //host}), no {@code ..}.
     */
    private static final Pattern SAFE_PAGE =
            Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.x?html(?:\\?[A-Za-z0-9_=&%.+-]*)?");

    private StartpageResolver() {
    }

    /**
     * Returns the secured start page (relative path without a leading slash) for the given
     * authorities: the configured {@code PROPERTY_STARTPAGE_} page if it is valid, otherwise
     * {@link #DEFAULT_PAGE}.
     *
     * @param authorities the granted authorities of the user (may be {@code null})
     * @return a valid relative page path
     */
    public static String resolve(Collection<? extends GrantedAuthority> authorities) {
        String page = DEFAULT_PAGE;
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                String authStr = authority.getAuthority();
                if (authStr != null && authStr.startsWith(STARTPAGE_PREFIX)) {
                    page = authStr.substring(STARTPAGE_PREFIX.length());
                    break; // the first configured start page wins
                }
            }
        }
        return safe(page);
    }

    /**
     * Validates a start page value; returns it only if it looks like a valid app-internal
     * page path, otherwise {@link #DEFAULT_PAGE}.
     *
     * @param page the page path to check (may be {@code null})
     * @return the trimmed path if valid, otherwise {@link #DEFAULT_PAGE}
     */
    public static String safe(String page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        String trimmed = page.trim();
        return SAFE_PAGE.matcher(trimmed).matches() ? trimmed : DEFAULT_PAGE;
    }
}
