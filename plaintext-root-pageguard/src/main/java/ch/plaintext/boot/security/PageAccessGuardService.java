/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.MenuRegistryImpl;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service for page access control based on menu visibility.
 * Checks whether a user has access to a JSF view by using the
 * MenuItem.isOn() method. That method checks both:
 * - role-based visibility (via SecurityProvider)
 * - tenant-based visibility (via MenuVisibilityProvider)
 *
 * <p><b>Card 308 (SECURITY).</b> This guard used to be fail-open in three places:
 * <ol>
 *   <li>The link comparison only normalized {@code .xhtml -> .html} and then compared exactly.
 *       Every menu item whose {@code link} did not end in exactly {@code .html} therefore found
 *       no match — {@code mandatemenu.xhtml} in root (the ROOT menu configuration!), 31 {@code .htm}
 *       links in plaintext-schuetu, 5 in plaintext-fwtool, 4 {@code .xhtml} links in
 *       plaintext-app. The comparison is now canonical (see {@link #kanonisch(String)}).</li>
 *   <li>No menu match -> {@code return true}. Now depends on the {@link PageGuardMode}.</li>
 *   <li>{@code catch (Exception)} -> {@code return true}. Now always denies.</li>
 * </ol>
 * On top of that, the <i>first</i> menu item found used to decide; with several menu items pointing
 * at the same link (e.g. {@code index.html} three times) that depended on the bean order.
 * The rule now is: access is granted if <i>any</i> matching menu item is visible.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@Slf4j
public class PageAccessGuardService {

    private final MenuRegistry menuRegistry;
    private final PageGuardProperties properties;

    /**
     * System pages that must always be reachable, regardless of the menu configuration.
     * Canonical spelling (without extension, without leading slash).
     */
    private static final Set<String> SYSTEM_PAGES = Set.of(
            "home",
            "index",
            "access-denied",
            "error",
            "login"
    );

    /**
     * Framework views without a menu entry that must be reachable anyway.
     * Canonical spelling.
     */
    private static final Set<String> FRAMEWORK_ALLOWLIST = Set.of(
            // Second step of the TOTP login. The user is not yet fully authenticated at this
            // point; the gate sits in TotpVerificationController.
            "login-totp",
            // Own profile — linked in includes/topbar.xhtml for EVERY user.
            "myuser",
            // User administration: has a gate of its own (MyUserBackingBean.checkAccess) AND is
            // hard-wired to ADMIN/ROOT in PlaintextSecurityConfig. The menu guard has nothing to
            // decide here.
            "useradmin",
            // Menu configuration manual (order from Daniel, 29.08.2026): no menu item of its own
            // any more, but the info button on mandatemenu/mandatemenudetail/menudiagnose.
            // Hard-wired to ROOT in PlaintextSecurityConfig (ROOT_ONLY_PAGES).
            "menuesteuerung-anleitung"
    );

    /**
     * Prefixes under which everything is reachable. {@code nosec/} is the established convention in
     * this repository for deliberately open paths (see {@code DEFAULT_PERMIT_ALL} and
     * {@code DEFAULT_CSRF_IGNORE} in {@code PlaintextSecurityConfig}). Without this entry the
     * {@code nosec} views of the consuming apps (time-tracking clock, schuetu scoreboards) would be
     * caught by the filter — unlike the old {@code preRenderView} guard, it also applies to views
     * with a template of their own.
     */
    private static final Set<String> FRAMEWORK_ALLOW_PREFIXES = Set.of(
            "nosec/"
    );

    /**
     * View aliases of the framework: detail pages without a menu entry of their own are guarded
     * like their list page and thus inherit its roles. That is more precise than an allowlist —
     * the pages stay protected — and needs no additional (visible) menu item.
     */
    private static final Map<String, String> FRAMEWORK_ALIASES = Map.of(
            // ROOT detail page of the menu configuration. Additionally hard-wired in
            // PlaintextSecurityConfig.
            "mandatemenudetail", "mandatemenu",
            "anforderungdetail", "anforderungen",
            "claudesummary", "anforderungen",
            "howtodetail", "howtos"
    );

    /** Protection against cycles in the menu hierarchy. */
    private static final int MAX_PARENT_TIEFE = 10;

    public PageAccessGuardService(MenuRegistry menuRegistry, PageGuardProperties properties) {
        this.menuRegistry = menuRegistry;
        this.properties = properties;
    }

    /**
     * Checks whether the current user has access to the given view.
     * Takes BOTH into account:
     * - role-based visibility (MenuItem.roles via SecurityProvider)
     * - tenant-based visibility (via MenuVisibilityProvider)
     *
     * @param viewId JSF view ID (e.g. "/kontakte.xhtml")
     * @return true if access is allowed, false otherwise
     */
    public boolean hasAccessToView(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            log.trace("View ID is null or empty, allowing access");
            return true;
        }
        if (!properties.isEnabled()) {
            log.trace("Page guard is disabled (plaintext.security.page-guard.enabled=false)");
            return true;
        }

        String seite = kanonisch(viewId);
        if (seite.isEmpty()) {
            return true;
        }

        if (SYSTEM_PAGES.contains(seite)) {
            log.trace("System page '{}' - allowing access", viewId);
            return true;
        }
        if (istAufAllowlist(seite)) {
            log.trace("Allowlisted page '{}' - allowing access", viewId);
            return true;
        }

        String ziel = aliasZiel(seite);
        if (!ziel.equals(seite)) {
            log.debug("PageAccessGuard: view '{}' is guarded as alias of menu link '{}'", viewId, ziel);
        }

        try {
            List<MenuItemImpl> alleMenus = alleMenuEintraege();
            List<MenuItemImpl> treffer = new ArrayList<>();
            for (MenuItemImpl item : alleMenus) {
                String link = item.getCommand();
                if (link == null || link.isBlank()) {
                    // Container menus without a link (e.g. link="" in plaintext-fwtool) must never
                    // count as a match, otherwise an empty canonical path would match.
                    continue;
                }
                if (kanonisch(link).equals(ziel)) {
                    treffer.add(item);
                }
            }

            if (treffer.isEmpty()) {
                return keinMenuTreffer(viewId, ziel);
            }

            for (MenuItemImpl item : treffer) {
                if (istSichtbar(item, alleMenus)) {
                    log.debug("Access granted to view '{}' via visible menu '{}'", viewId, item.buildFullTitle());
                    return true;
                }
            }

            log.warn("SECURITY: Access denied to view '{}' - no visible menu entry (role, parent role or mandate restriction); candidates: {}",
                    viewId, treffer.stream().map(MenuItemImpl::buildFullTitle).toList());
            return false;

        } catch (Exception e) {
            // Card 308: this used to allow access ("to avoid a system outage"). That is a silent
            // authorization bypass as soon as an exception is thrown anywhere. Now: deny.
            log.error("SECURITY: Error checking access to view '{}' - denying access: {}", viewId, e.getMessage(), e);
            return false;
        }
    }

    /** Behaviour for a view without a menu assignment — depends on the mode. */
    private boolean keinMenuTreffer(String viewId, String ziel) {
        if (properties.getMode() == PageGuardMode.STRICT) {
            log.warn("SECURITY: Access denied to view '{}' - no menu entry, no alias and not allowlisted "
                            + "(page-guard mode=STRICT). Fix: @MenuAnnotation(link=\"{}.html\") ergaenzen, oder "
                            + "plaintext.security.page-guard.aliases/allowlist setzen.",
                    viewId, ziel);
            return false;
        }
        log.warn("SECURITY (page-guard mode=REPORT): view '{}' has no menu entry, no alias and is not "
                        + "allowlisted — access is ALLOWED for now. Vor der Umstellung auf STRICT entweder "
                        + "@MenuAnnotation(link=\"{}.html\") ergaenzen oder "
                        + "plaintext.security.page-guard.aliases/allowlist setzen.",
                viewId, ziel);
        return true;
    }

    /**
     * Visibility of a menu item. In {@link PageGuardMode#STRICT} this additionally inherits the
     * roles of the parent: {@link MenuItemImpl#isOn()} only checks the item's <i>own</i>
     * {@code roles}. In the rendered menu an invisible parent menu nevertheless hides all of its
     * children ({@code PrimefacesSubmenu.isRendered()}); without inheritance every menu item
     * without {@code roles} of its own below a ROOT/ADMIN menu would be open to every user via a
     * direct URL.
     */
    private boolean istSichtbar(MenuItemImpl item, List<MenuItemImpl> alleMenus) {
        if (properties.getMode() != PageGuardMode.STRICT) {
            return item.isOn();
        }
        return istSichtbarMitEltern(item, alleMenus, new HashSet<>(), 0);
    }

    private boolean istSichtbarMitEltern(MenuItemImpl item, List<MenuItemImpl> alleMenus,
                                         Set<String> besucht, int tiefe) {
        if (!item.isOn()) {
            return false;
        }
        // An item's own roles are final: whoever declares them does not inherit. This is the
        // escape clause for pages that are deliberately more broadly reachable below a restricted
        // parent menu — e.g. notifications.html (the topbar bell for every user) or
        // api-token.html, both of which declare roles={"USER","ADMIN","ROOT"} even though they sit
        // below "Root" resp. "Admin" in the menu tree.
        if (item.getRoles() != null && !item.getRoles().isEmpty()) {
            return true;
        }
        String elternTitel = item.getParent();
        if (elternTitel == null || elternTitel.isBlank()) {
            return true;
        }
        if (tiefe >= MAX_PARENT_TIEFE || !besucht.add(elternTitel)) {
            log.warn("PageAccessGuard: parent chain of menu '{}' is cyclic or too deep at '{}' - treating as visible",
                    item.buildFullTitle(), elternTitel);
            return true;
        }

        List<MenuItemImpl> eltern = new ArrayList<>();
        for (MenuItemImpl kandidat : alleMenus) {
            if (elternTitel.equals(kandidat.getTitle())) {
                eltern.add(kandidat);
            }
        }
        if (eltern.isEmpty()) {
            // The parent menu does not exist (typo in the parent value). Do not lock anyone out.
            log.debug("PageAccessGuard: parent menu '{}' of '{}' not found - treating as visible",
                    elternTitel, item.buildFullTitle());
            return true;
        }
        for (MenuItemImpl elternItem : eltern) {
            if (istSichtbarMitEltern(elternItem, alleMenus, besucht, tiefe + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Allowlist = framework defaults + configured additions. */
    private boolean istAufAllowlist(String seite) {
        if (FRAMEWORK_ALLOWLIST.contains(seite)) {
            return true;
        }
        for (String prefix : FRAMEWORK_ALLOW_PREFIXES) {
            if (seite.startsWith(prefix)) {
                return true;
            }
        }
        for (String eintrag : properties.getAllowlist()) {
            if (eintrag == null || eintrag.isBlank()) {
                continue;
            }
            String kandidat = eintrag.trim();
            if (kandidat.endsWith("/**") || kandidat.endsWith("/*")) {
                String prefix = kanonisch(kandidat.substring(0, kandidat.lastIndexOf('/')));
                if (!prefix.isEmpty() && seite.startsWith(prefix + "/")) {
                    return true;
                }
            } else if (kanonisch(kandidat).equals(seite)) {
                return true;
            }
        }
        return false;
    }

    /** Alias target (framework defaults + configured additions), canonicalized. */
    private String aliasZiel(String seite) {
        for (Map.Entry<String, String> eintrag : properties.getAliases().entrySet()) {
            if (kanonisch(eintrag.getKey()).equals(seite)) {
                String ziel = kanonisch(eintrag.getValue());
                return ziel.isEmpty() ? seite : ziel;
            }
        }
        String frameworkZiel = FRAMEWORK_ALIASES.get(seite);
        return frameworkZiel != null ? frameworkZiel : seite;
    }

    /**
     * Canonical form of a path or menu link: lower-cased, without a leading slash, without the
     * query string and without the extensions {@code .xhtml}, {@code .jsf}, {@code .html},
     * {@code .htm}.
     *
     * <p>This makes {@code /kontakte.xhtml}, {@code kontakte.html}, {@code kontakte.htm} and
     * {@code kontakte} match the same value. Exactly this comparison was missing before: the guard
     * only normalized the view id ({@code .xhtml -> .html}), not the menu link — but the
     * {@code UrlRewriteFilter} accepts both {@code .html} and {@code .htm}, and {@code *.xhtml} is
     * mapped to the {@code FacesServlet} as well.
     */
    static String kanonisch(String pfad) {
        if (pfad == null) {
            return "";
        }
        String wert = pfad.trim().toLowerCase(Locale.ROOT);
        int frage = wert.indexOf('?');
        if (frage >= 0) {
            wert = wert.substring(0, frage);
        }
        int raute = wert.indexOf('#');
        if (raute >= 0) {
            wert = wert.substring(0, raute);
        }
        while (wert.startsWith("/")) {
            wert = wert.substring(1);
        }
        for (String endung : new String[]{".xhtml", ".jsf", ".html", ".htm"}) {
            if (wert.endsWith(endung)) {
                return wert.substring(0, wert.length() - endung.length());
            }
        }
        return wert;
    }

    /**
     * All registered menu items. Uses {@link MenuRegistryImpl#getAllMenuItemsImpl()} to avoid
     * classloader problems in the Spring Boot JAR.
     */
    private List<MenuItemImpl> alleMenuEintraege() {
        if (menuRegistry instanceof MenuRegistryImpl impl) {
            return impl.getAllMenuItemsImpl();
        }
        List<MenuItemImpl> ergebnis = new ArrayList<>();
        for (Object item : menuRegistry.getAllMenuItems()) {
            if (item instanceof MenuItemImpl impl) {
                ergebnis.add(impl);
            }
        }
        return ergebnis;
    }

    /**
     * All menu links in canonical form -> full menu title. For the startup report and the
     * invariant test.
     */
    public Map<String, String> menuLinksKanonisch() {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        for (MenuItemImpl item : alleMenuEintraege()) {
            String link = item.getCommand();
            if (link == null || link.isBlank()) {
                continue;
            }
            ergebnis.putIfAbsent(kanonisch(link), item.buildFullTitle());
        }
        return ergebnis;
    }

    /**
     * Checks whether a view is subject to any rule at all (system page, allowlist, alias or menu
     * match). Used by the startup report to report the views that would be blocked in
     * {@link PageGuardMode#STRICT}.
     *
     * @param viewId view id or path
     * @return {@code true} if the view is assigned to a rule
     */
    public boolean istZugeordnet(String viewId) {
        String seite = kanonisch(viewId);
        if (seite.isEmpty() || SYSTEM_PAGES.contains(seite) || istAufAllowlist(seite)) {
            return true;
        }
        return menuLinksKanonisch().containsKey(aliasZiel(seite));
    }

    /** Current mode (for the filter, the startup report and tests). */
    public PageGuardMode getMode() {
        return properties.getMode();
    }

    /** Emergency off switch (for the filter and tests). */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Redirect to the access denied page
     */
    public void redirectToAccessDenied() throws IOException {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            log.error("FacesContext is null, cannot redirect to access denied");
            return;
        }

        ExternalContext externalContext = context.getExternalContext();
        String contextPath = externalContext.getRequestContextPath();
        String redirectUrl = contextPath + "/access-denied.html";

        log.debug("Redirecting to access denied page: {}", redirectUrl);
        externalContext.redirect(redirectUrl);
        context.responseComplete();
    }
}
