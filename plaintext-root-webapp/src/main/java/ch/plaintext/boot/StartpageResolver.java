/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot;

import jakarta.servlet.ServletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;

import java.net.MalformedURLException;
import java.util.Collection;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Resolves the individual start/landing page of a user from their {@link GrantedAuthority}s and
 * secures it. A configured start page (authority {@code PROPERTY_STARTPAGE_<page>}) is
 * only used if it looks like a valid app-internal page path; otherwise
 * (empty/invalid) it falls back to {@link #DEFAULT_PAGE}, so that no user is locked out of the
 * start page. This keeps individual start pages working, while a broken value always leads
 * reliably to index.html.
 *
 * <p><b>Card 1331 (23.09.2026): the form is not enough, the page has to EXIST.</b> The start page
 * {@code Index.html} (capital I) passed the form check, {@code /} redirected there, the page
 * does not exist ({@code index.xhtml}), the 404 was turned into a redirect to {@code /} by
 * {@code PlaintextErrorViewResolver} - an endless redirect loop, and the user was locked out
 * after every login. The overloads with a {@link ServletContext} therefore also check that the
 * Facelets view behind the path exists ({@code <page>.xhtml}, looked up exactly the way JSF looks
 * it up: {@link ServletContext#getResource(String)}, which includes {@code META-INF/resources} of
 * every jar). A page that does not exist falls back to {@link #DEFAULT_PAGE} as well.
 *
 * @author plaintext.ch
 */
@Slf4j
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
        return safe(configured(authorities));
    }

    /**
     * Like {@link #resolve(Collection)}, but additionally requires the page to exist in this
     * application (card 1331). Use this wherever a redirect is sent.
     *
     * @param authorities the granted authorities of the user (may be {@code null})
     * @param servletContext the servlet context to look the view up in; {@code null} = form check only
     * @return a valid, existing relative page path, otherwise {@link #DEFAULT_PAGE}
     */
    public static String resolve(Collection<? extends GrantedAuthority> authorities,
                                 ServletContext servletContext) {
        return resolve(authorities, existsIn(servletContext));
    }

    /**
     * Core of {@link #resolve(Collection, ServletContext)} with an explicit existence check.
     *
     * @param authorities the granted authorities of the user (may be {@code null})
     * @param pageExists answers whether a (form-valid) page path exists
     * @return a valid, existing relative page path, otherwise {@link #DEFAULT_PAGE}
     */
    public static String resolve(Collection<? extends GrantedAuthority> authorities,
                                 Predicate<String> pageExists) {
        String configured = configured(authorities);
        String page = safe(configured);
        if (!DEFAULT_PAGE.equals(page) && !pageExists.test(page)) {
            log.warn("Startseite '{}' existiert nicht — Umleitung auf {} (Karte 1331)", page, DEFAULT_PAGE);
            return DEFAULT_PAGE;
        }
        return page;
    }

    /**
     * Checks a start page value before it is saved (card 1331). Empty means "no individual start
     * page" and is allowed.
     *
     * @param page the value to save (may be {@code null})
     * @param servletContext the servlet context; {@code null} = form check only
     * @return {@code null} if the value may be saved, otherwise a message for the user
     */
    public static String rejectionReason(String page, ServletContext servletContext) {
        return rejectionReason(page, existsIn(servletContext));
    }

    /**
     * Core of {@link #rejectionReason(String, ServletContext)} with an explicit existence check.
     *
     * @param page the value to save (may be {@code null})
     * @param pageExists answers whether a (form-valid) page path exists
     * @return {@code null} if the value may be saved, otherwise a message for the user
     */
    public static String rejectionReason(String page, Predicate<String> pageExists) {
        if (page == null || page.isBlank()) {
            return null;
        }
        String trimmed = page.trim();
        if (!SAFE_PAGE.matcher(trimmed).matches()) {
            return "Startseite '" + trimmed + "' ist kein gueltiger Seitenpfad (z.B. auszahlungen.html).";
        }
        if (!pageExists.test(trimmed)) {
            return "Startseite '" + trimmed + "' gibt es in dieser Anwendung nicht"
                    + " (Gross-/Kleinschreibung beachten, z.B. index.html).";
        }
        return null;
    }

    /**
     * Existence check against a servlet context: {@code a/b.html?x=1} exists if the Facelets view
     * {@code /a/b.xhtml} is a resource of the context. Without a context every page counts as
     * existing (form check only, the behaviour before card 1331).
     *
     * @param servletContext the servlet context (may be {@code null})
     * @return the existence check
     */
    public static Predicate<String> existsIn(ServletContext servletContext) {
        if (servletContext == null) {
            return page -> true;
        }
        return page -> {
            String path = page;
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            String view = "/" + path.replaceFirst("\\.html$", ".xhtml");
            try {
                return servletContext.getResource(view) != null;
            } catch (MalformedURLException e) {
                return false;
            }
        };
    }

    private static String configured(Collection<? extends GrantedAuthority> authorities) {
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
        return page;
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
