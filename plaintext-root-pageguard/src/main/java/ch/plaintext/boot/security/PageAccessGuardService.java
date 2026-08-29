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
 * Service für Page Access Control basierend auf Menu-Sichtbarkeit.
 * Prüft ob ein Benutzer Zugriff auf eine JSF View hat, indem die
 * MenuItem.isOn() Methode verwendet wird. Diese prüft sowohl:
 * - Rollen-basierte Sichtbarkeit (via SecurityProvider)
 * - Mandate-basierte Sichtbarkeit (via MenuVisibilityProvider)
 *
 * <p><b>Karte 308 (SECURITY).</b> Vorher war dieser Guard an drei Stellen fail-open:
 * <ol>
 *   <li>Der Link-Vergleich normalisierte nur {@code .xhtml -> .html} und verglich dann exakt.
 *       Jeder Menuepunkt, dessen {@code link} nicht genau auf {@code .html} endete, fand also
 *       keinen Treffer — {@code mandatemenu.xhtml} in root (ROOT-Menuesteuerung!), 31 {@code .htm}
 *       -Links in plaintext-schuetu, 5 in plaintext-fwtool, 4 {@code .xhtml}-Links in
 *       plaintext-app. Jetzt wird kanonisch verglichen (siehe {@link #kanonisch(String)}).</li>
 *   <li>Kein Menuetreffer -> {@code return true}. Jetzt abhaengig vom {@link PageGuardMode}.</li>
 *   <li>{@code catch (Exception)} -> {@code return true}. Jetzt immer verweigern.</li>
 * </ol>
 * Ausserdem entschied vorher der <i>erste</i> gefundene Menuepunkt; bei mehreren Menuepunkten auf
 * denselben Link (z.B. dreimal {@code index.html}) war das von der Bean-Reihenfolge abhaengig.
 * Jetzt gilt: Zugriff erlaubt, wenn <i>irgendein</i> passender Menuepunkt sichtbar ist.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@Slf4j
public class PageAccessGuardService {

    private final MenuRegistry menuRegistry;
    private final PageGuardProperties properties;

    /**
     * Systemseiten die immer erreichbar sein sollen, unabhängig von Menü-Konfiguration.
     * Kanonische Schreibweise (ohne Endung, ohne führenden Slash).
     */
    private static final Set<String> SYSTEM_PAGES = Set.of(
            "home",
            "index",
            "access-denied",
            "error",
            "login"
    );

    /**
     * Framework-Views ohne Menueeintrag, die trotzdem immer erreichbar sein muessen.
     * Kanonische Schreibweise.
     */
    private static final Set<String> FRAMEWORK_ALLOWLIST = Set.of(
            // Zweiter Schritt der TOTP-Anmeldung. Der User ist hier noch nicht voll
            // authentifiziert; das Gate sitzt im TotpVerificationController.
            "login-totp",
            // Eigenes Profil — in includes/topbar.xhtml fuer JEDEN User verlinkt.
            "myuser",
            // Benutzerverwaltung: hat ein eigenes Gate (MyUserBackingBean.checkAccess) UND ist in
            // PlaintextSecurityConfig hart auf ADMIN/ROOT verdrahtet. Der Menue-Guard hat hier
            // nichts zu entscheiden.
            "useradmin",
            // Anleitung der Menuesteuerung (Auftrag Daniel, 29.08.2026): kein eigener Menuepunkt
            // mehr, sondern der Info-Knopf auf mandatemenu/mandatemenudetail/menudiagnose. In
            // PlaintextSecurityConfig hart auf ROOT verdrahtet (ROOT_ONLY_PAGES).
            "menuesteuerung-anleitung"
    );

    /**
     * Praefixe, unter denen alles erreichbar ist. {@code nosec/} ist im Repo die etablierte
     * Konvention fuer bewusst offene Pfade (siehe {@code DEFAULT_PERMIT_ALL} und
     * {@code DEFAULT_CSRF_IGNORE} in {@code PlaintextSecurityConfig}). Ohne diesen Eintrag wuerden
     * die {@code nosec}-Views der Consumer-Apps (Zeiterfassungs-Uhr, schuetu-Anzeigetafeln) vom
     * Filter erfasst — der greift, anders als der alte {@code preRenderView}-Guard, auch bei Views
     * mit eigenem Template.
     */
    private static final Set<String> FRAMEWORK_ALLOW_PREFIXES = Set.of(
            "nosec/"
    );

    /**
     * View-Aliase des Frameworks: Detailseiten ohne eigenen Menueeintrag werden wie ihre
     * Listenseite bewacht und erben damit deren Rollen. Das ist praeziser als eine Allowlist —
     * die Seiten bleiben geschuetzt — und braucht keinen zusaetzlichen (sichtbaren) Menuepunkt.
     */
    private static final Map<String, String> FRAMEWORK_ALIASES = Map.of(
            // ROOT-Detailseite der Menuesteuerung. Zusaetzlich hart in PlaintextSecurityConfig.
            "mandatemenudetail", "mandatemenu",
            "anforderungdetail", "anforderungen",
            "claudesummary", "anforderungen",
            "howtodetail", "howtos"
    );

    /** Schutz gegen Zyklen in der Menue-Hierarchie. */
    private static final int MAX_PARENT_TIEFE = 10;

    public PageAccessGuardService(MenuRegistry menuRegistry, PageGuardProperties properties) {
        this.menuRegistry = menuRegistry;
        this.properties = properties;
    }

    /**
     * Prüft ob der aktuelle Benutzer Zugriff auf die angegebene View hat.
     * Berücksichtigt BEIDE:
     * - Rollen-basierte Sichtbarkeit (MenuItem.roles via SecurityProvider)
     * - Mandate-basierte Sichtbarkeit (via MenuVisibilityProvider)
     *
     * @param viewId JSF View ID (z.B. "/kontakte.xhtml")
     * @return true wenn Zugriff erlaubt, false sonst
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
                    // Container-Menues ohne Link (z.B. link="" in plaintext-fwtool) duerfen nie
                    // als Treffer gelten, sonst wuerde ein leerer kanonischer Pfad matchen.
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
            // Karte 308: vorher wurde hier erlaubt ("um Systemausfall zu vermeiden"). Das ist ein
            // stiller Autorisierungs-Bypass, sobald irgendwo eine Exception fliegt. Jetzt: verweigern.
            log.error("SECURITY: Error checking access to view '{}' - denying access: {}", viewId, e.getMessage(), e);
            return false;
        }
    }

    /** Verhalten bei einer View ohne Menuezuordnung — abhaengig vom Modus. */
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
     * Sichtbarkeit eines Menuepunkts. In {@link PageGuardMode#STRICT} zusaetzlich mit Vererbung der
     * Eltern-Rollen: {@link MenuItemImpl#isOn()} prueft nur die <i>eigenen</i> {@code roles}. Im
     * gerenderten Menue verbirgt ein unsichtbares Elternmenue trotzdem alle Kinder
     * ({@code PrimefacesSubmenu.isRendered()}); ohne Vererbung waere jeder Menuepunkt ohne eigene
     * {@code roles} unter einem ROOT-/ADMIN-Menue per Direkt-URL fuer jeden User offen.
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
        // Eigene roles sind abschliessend: wer sie deklariert, erbt nicht. Das ist die
        // Ausstiegsklausel fuer bewusst breiter erreichbare Seiten unter einem eingeschraenkten
        // Elternmenue — z.B. notifications.html (Topbar-Glocke fuer jeden User) oder
        // api-token.html, die beide roles={"USER","ADMIN","ROOT"} deklarieren, obwohl sie im
        // Menuebaum unter "Root" bzw. "Admin" haengen.
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
            // Elternmenue existiert nicht (Tippfehler im parent-Wert). Nicht aussperren.
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

    /** Allowlist = Framework-Defaults + konfigurierte Ergaenzungen. */
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

    /** Alias-Ziel (Framework-Defaults + konfigurierte Ergaenzungen), kanonisiert. */
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
     * Kanonische Form eines Pfades oder Menue-Links: kleingeschrieben, ohne fuehrenden Slash, ohne
     * Query-String und ohne die Endungen {@code .xhtml}, {@code .jsf}, {@code .html}, {@code .htm}.
     *
     * <p>Damit matchen {@code /kontakte.xhtml}, {@code kontakte.html}, {@code kontakte.htm} und
     * {@code kontakte} auf denselben Wert. Genau dieser Vergleich fehlte vorher: der Guard
     * normalisierte nur die View-Id ({@code .xhtml -> .html}), nicht den Menue-Link — der
     * {@code UrlRewriteFilter} akzeptiert aber sowohl {@code .html} als auch {@code .htm}, und
     * {@code *.xhtml} ist ebenfalls auf das {@code FacesServlet} gemappt.
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
     * Alle registrierten Menuepunkte. Verwendet {@link MenuRegistryImpl#getAllMenuItemsImpl()},
     * um Classloader-Probleme im Spring-Boot-JAR zu vermeiden.
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
     * Alle Menue-Links in kanonischer Form -> vollstaendiger Menuetitel. Fuer den Startup-Report
     * und den Invariantentest.
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
     * Prueft, ob eine View ueberhaupt einer Regel unterliegt (Systemseite, Allowlist, Alias oder
     * Menuetreffer). Wird vom Startup-Report genutzt, um die Views zu melden, die in
     * {@link PageGuardMode#STRICT} gesperrt wuerden.
     *
     * @param viewId View-Id oder Pfad
     * @return {@code true} wenn die View einer Regel zugeordnet ist
     */
    public boolean istZugeordnet(String viewId) {
        String seite = kanonisch(viewId);
        if (seite.isEmpty() || SYSTEM_PAGES.contains(seite) || istAufAllowlist(seite)) {
            return true;
        }
        return menuLinksKanonisch().containsKey(aliasZiel(seite));
    }

    /** Aktueller Modus (fuer Filter, Startup-Report und Tests). */
    public PageGuardMode getMode() {
        return properties.getMode();
    }

    /** Not-Aus-Schalter (fuer Filter und Tests). */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Redirect zu Access Denied Seite
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
