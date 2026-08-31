/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import java.util.List;

/**
 * Bean interface through which a module contributes its own hits to the global search (Cmd+K in
 * the topbar). It mirrors the registry pattern of {@code MenuAnnotation}/{@code MenuRegistry} and
 * {@code DashboardTile}/{@code DashboardTileDataProvider} exactly: <b>root defines the interface,
 * every module registers a {@code @Component}, root collects all beans automatically and queries
 * them.</b>
 * <p>
 * Core principle: <b>every module supplies its own hits – including the correct target link.</b>
 * root does not need to know anything about the module's pages; the deep link
 * ({@link SearchHit#getLink()}) is exactly the same as a {@code MenuAnnotation.link} (e.g.
 * {@code "korrespondenz.html?id=42"}) and is guaranteed to land on the module's proper detail page.
 * <p>
 * <b>"Plugging in" a module =</b> a single {@code @Component} class implementing this interface –
 * no change in root required.
 * <p>
 * <b>Visibility/security:</b> root queries a provider only if its {@link #moduleTitle()} is
 * visible for the current tenant/user (matched against {@code MenuRegistry.getAllMenuTitles()}).
 * In addition, every provider should restrict its own hits to the active tenant via
 * {@code PlaintextSecurity.getMandat()} – just like a tenant-scoped
 * {@code DashboardTileDataProvider}.
 *
 * @author plaintext.ch
 */
public interface SearchProvider {

    /**
     * Technical, stable ID of this provider (e.g. {@code "korrespondenz"}, {@code "kontakte"}).
     * Used for diagnostics and logging only, and has to be unique.
     *
     * @return the provider ID (never {@code null})
     */
    String providerId();

    /**
     * Display/group title of this provider. <b>It has to match a menu title</b> (title or full
     * title from {@code MenuRegistry}) so that root can couple its visibility to the menu
     * visibility: if the associated menu is not visible for the user/tenant, the provider is not
     * queried at all and does not appear in the results.
     *
     * @return the group title (never {@code null})
     */
    String moduleTitle();

    /**
     * Searches the business module for {@code query} and returns up to {@code limit} hits. Called
     * in the security/tenant context of the current user; the provider filters by the active
     * tenant itself.
     * <p>
     * The implementation should be robust and fast: root calls the providers time-boxed and
     * catches errors (a slow or faulty provider must not block the overall search), but still: no
     * exceptions for the normal case, and never return {@code null} (return an empty list).
     *
     * @param query the search term (already trimmed; at least 2 characters)
     * @param limit maximum number of hits this provider should return
     * @return list of hits (never {@code null}, possibly empty)
     */
    List<SearchHit> search(String query, int limit);

    /**
     * Whether this provider is coupled to the menu visibility of its {@link #moduleTitle()}
     * (default: {@code true}). If it is coupled, root queries it only when a visible menu with a
     * matching title exists – the normal case for business module providers.
     * <p>
     * Cross-cutting root providers (e.g. the menu/page search or the role-bound user search)
     * belong to no single menu and return {@code false} here; they then enforce visibility and
     * roles <b>themselves</b> in {@link #search(String, int)}.
     *
     * @return {@code true} if root should check visibility via the menu title
     */
    default boolean isMenuScoped() {
        return true;
    }

    /**
     * A single search hit. The <b>deep link</b> ({@link #getLink()}) is the key: it points to the
     * module's own target page, exactly like a {@code MenuAnnotation.link}.
     */
    interface SearchHit {

        /**
         * Main text/label of the hit (e.g. the document title, the contact's name).
         *
         * @return the title (never {@code null})
         */
        String getTitle();

        /**
         * Context line below the title (e.g. date, tenant, short description). May be {@code null}
         * or empty.
         *
         * @return the subtitle or {@code null}
         */
        String getSubtitle();

        /**
         * <b>Deep link to the module's target page</b> – exactly like a {@code MenuAnnotation.link},
         * relative to the context path (e.g. {@code "korrespondenz.html?id=42"}). The frontend
         * navigates via {@code window.location = contextPath + "/" + link}. Use only harmless,
         * relative targets; no absolute or protocol-relative URLs.
         *
         * @return the relative target link (never {@code null})
         */
        String getLink();

        /**
         * PrimeFaces icon class for the hit (e.g. {@code "pi pi-envelope"}). May be {@code null}.
         *
         * @return the icon class or {@code null}
         */
        String getIcon();

        /**
         * Ranking within the module group: higher values appear further up.
         *
         * @return the score
         */
        int getScore();
    }
}
