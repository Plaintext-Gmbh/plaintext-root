/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Setzt die konfigurierbaren Modul-Rollen durch ({@link ModuleRoleProperties}): Ein Modul, dem in
 * der Anwendungs-Konfiguration eine Rolle zugeordnet ist, verschwindet fuer Benutzer ohne diese
 * Rolle komplett — Menuepunkte, Dashboard-Kacheln und (ueber {@link MenuItemImpl#isOn()}) auch der
 * Direktaufruf der Seiten via {@code PageAccessGuard}.
 *
 * <p><b>Ein Schalter, drei Wirkungen.</b> Der Guard und der Menue-Renderer fragen beide
 * {@link MenuItemImpl#isOn()}; die Kacheln fragen {@link #isAllowedForLink(String, String,
 * SecurityProvider)}. Die Rollenpruefung muss deshalb nur an einer Stelle sitzen, damit Menue,
 * Kachel und Seitenzugriff konsistent verschwinden.</p>
 *
 * <p><b>Modul-Zugehoerigkeit</b> wird aus vorhandenen Metadaten abgeleitet, das Modul selbst wird
 * nicht angefasst: Fuer jeden Menuepunkt werden die Kandidaten-Keys gesammelt (eigene
 * {@code moduleId}, die {@code moduleId} jedes Elternmenues, sowie die
 * {@linkplain MenuItemImpl#getEffectiveMenuId() Menu-Root-Id} des obersten Elternmenues). Passt
 * einer davon auf einen konfigurierten Key, gilt dessen Rolle.</p>
 *
 * <p>{@code ROLE_ADMIN} und {@code ROLE_ROOT} umgehen die Pruefung immer — unveraendertes
 * Verhalten. Module ohne Eintrag verhalten sich exakt wie bisher.</p>
 *
 * @author info@plaintext.ch
 * @since 1.604.0
 */
@Slf4j
public class ModuleRoleService implements SmartInitializingSingleton {

    /** Schutz gegen Zyklen in der Menue-Hierarchie. */
    private static final int MAX_PARENT_TIEFE = 10;

    private final ApplicationContext applicationContext;
    private final ModuleRoleProperties properties;

    /**
     * Kanonischer Menue-Link -&gt; geforderte Rollen; fuer die Kacheln.
     *
     * <p>Die drei Nachschlagetabellen werden in {@link #resolve(Collection)} als fertige,
     * unveraenderliche Momentaufnahme gebaut und danach in einem Zug veroeffentlicht. Ein
     * {@code volatile}-Feld wuerde zwar die Referenz sicher publizieren, aber nichts darueber
     * aussagen, ob am dahinterliegenden Objekt noch geschrieben wird — deshalb
     * {@link AtomicReference} als ausdruecklich thread-sicherer Halter fuer den Schnappschuss.</p>
     */
    private final AtomicReference<Map<String, List<String>>> rolesByLink = new AtomicReference<>(Map.of());

    /** Menue-Titel -&gt; geforderte Rollen; Fallback fuer Kacheln ohne passenden Link. */
    private final AtomicReference<Map<String, List<String>>> rolesByMenuTitle = new AtomicReference<>(Map.of());

    /** Alle im Klassenpfad erkannten Modul-Keys (fuer Startup-Report und Doku-Hinweis). */
    private final AtomicReference<Set<String>> knownModuleKeys = new AtomicReference<>(Set.of());

    private volatile boolean resolved;

    public ModuleRoleService(ApplicationContext applicationContext, ModuleRoleProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties == null ? new ModuleRoleProperties() : properties;
    }

    /**
     * Loest die Modul-Zugehoerigkeit einmalig auf und meldet die erkannten Modul-Keys. Laeuft
     * bewusst erst nach der Instanziierung aller Singletons: die {@link MenuItemImpl}-Beans werden
     * von {@link MenuRegistryPostProcessor} per {@code registerSingleton} beigesteuert.
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            ensureResolved();
            reportAtStartup();
        } catch (Exception e) {
            log.warn("Modul-Rollen konnten beim Start nicht aufgeloest werden: {}", e.getMessage());
        }
    }

    private void reportAtStartup() {
        Map<String, String> configured = properties.canonicalModuleRoles();
        Set<String> bekannteKeys = knownModuleKeys.get();
        if (configured.isEmpty()) {
            log.info("Keine Modul-Rollen konfiguriert (plaintext.menu.module-roles) — "
                    + "erkannte Modul-Keys: {}", bekannteKeys);
            return;
        }
        log.info("Modul-Rollen aktiv: {} — erkannte Modul-Keys: {}", configured, bekannteKeys);
        for (Map.Entry<String, String> eintrag : configured.entrySet()) {
            String key = eintrag.getKey();
            if (!bekannteKeys.contains(key)) {
                log.warn("Modul-Rolle konfiguriert fuer unbekannten Modul-Key '{}' "
                                + "(plaintext.menu.module-roles.{}={}) — die Zuordnung greift nirgends. "
                                + "Bekannte Keys: {}",
                        key, key, eintrag.getValue(), bekannteKeys);
            }
        }
    }

    /**
     * Darf der aktuelle Benutzer eine Dashboard-Kachel sehen, soweit es die Modul-Rollen betrifft?
     * Die Kachel wird ueber ihren Link dem Menue zugeordnet, ersatzweise ueber ihren
     * {@code menuTitle} — dieselbe Kopplung, die auch die mandatsspezifische Sichtbarkeit nutzt.
     *
     * @param link             Link der Kachel (z.B. {@code wiki.html})
     * @param menuTitle        Menue-Titel der Kachel, darf leer sein
     * @param securityProvider Rollen-Lookup, darf {@code null} sein
     * @return {@code true}, wenn keine Modul-Rolle gefordert ist oder der Benutzer sie haelt
     */
    public boolean isAllowedForLink(String link, String menuTitle, SecurityProvider securityProvider) {
        if (properties.isEmpty()) {
            return true;
        }
        ensureResolved();
        List<String> required = rolesByLink.get().get(canonicalLink(link));
        if (required == null && menuTitle != null && !menuTitle.trim().isEmpty()) {
            required = rolesByMenuTitle.get().get(menuTitle.trim());
        }
        return holdsAny(required, securityProvider);
    }

    /**
     * Alle im Klassenpfad erkannten Modul-Keys — die gueltige Auswahl fuer
     * {@code plaintext.menu.module-roles.<key>}.
     *
     * @return erkannte Modul-Keys, alphabetisch (nie {@code null})
     */
    public Set<String> getKnownModuleKeys() {
        ensureResolved();
        return knownModuleKeys.get();
    }

    /**
     * Rollenpruefung inklusive admin/root-Bypass. Statisch, damit {@link MenuItemImpl#isOn()} sie
     * auch ohne Spring-Kontext (Unit-Tests) mit bereits aufgeloesten Rollen anwenden kann.
     *
     * @param required         geforderte Rollen (GROSS, ohne Prefix), darf leer/{@code null} sein
     * @param securityProvider Rollen-Lookup, darf {@code null} sein
     * @return {@code true}, wenn der Zugriff erlaubt ist
     */
    public static boolean holdsAny(List<String> required, SecurityProvider securityProvider) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (securityProvider == null) {
            return true;
        }
        // admin und root behalten IMMER Zugriff - unveraendertes Verhalten.
        if (hasRole(securityProvider, "ROOT") || hasRole(securityProvider, "ADMIN")) {
            return true;
        }
        for (String role : required) {
            if (hasRole(securityProvider, role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rollenabfrage mit und ohne {@code ROLE_}-Prefix. {@code SpringSecurityProvider} kennt beide
     * Schreibweisen; eine eigene {@link SecurityProvider}-Implementierung einer App muss das aber
     * nicht — deshalb wird hier beides gefragt, statt sich auf eine Konvention zu verlassen.
     *
     * @param securityProvider Rollen-Lookup, nicht {@code null}
     * @param role             Rollenname GROSS und ohne {@code ROLE_}-Prefix
     * @return {@code true}, wenn der Benutzer die Rolle in einer der beiden Schreibweisen haelt
     */
    private static boolean hasRole(SecurityProvider securityProvider, String role) {
        return securityProvider.hasRole(role) || securityProvider.hasRole("ROLE_" + role);
    }

    /**
     * Loest die Modul-Zugehoerigkeit aller registrierten Menuepunkte einmalig auf und schreibt die
     * geforderten Rollen an die Menuepunkte. Idempotent und thread-sicher.
     */
    public void ensureResolved() {
        if (resolved) {
            return;
        }
        synchronized (this) {
            if (resolved) {
                return;
            }
            resolve(loadMenuItems());
        }
    }

    private Collection<MenuItemImpl> loadMenuItems() {
        if (applicationContext == null) {
            return List.of();
        }
        try {
            return applicationContext.getBeansOfType(MenuItemImpl.class).values();
        } catch (Exception e) {
            log.warn("Menuepunkte fuer die Modul-Rollen-Aufloesung nicht ermittelbar: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Loest die Modul-Zugehoerigkeit fuer alle Menuepunkte auf, schreibt die geforderten Rollen an
     * die Menuepunkte und baut die Nachschlagetabellen fuer die Kacheln.
     *
     * <p>Im Normalbetrieb ruft {@link #ensureResolved()} diese Methode mit den registrierten
     * Menuepunkt-Beans auf; oeffentlich, damit Tests und Anwendungen sie ohne Spring-Kontext mit
     * einer selbst gebauten Menuestruktur benutzen koennen.</p>
     *
     * @param items alle registrierten Menuepunkte
     */
    public void resolve(Collection<MenuItemImpl> items) {
        Map<String, String> configured = properties.canonicalModuleRoles();
        Map<String, List<MenuItemImpl>> byTitle = indexByTitle(items);

        Set<String> keys = new TreeSet<>();
        Map<String, List<String>> byLink = new HashMap<>();
        Map<String, List<String>> byMenuTitle = new HashMap<>();

        for (MenuItemImpl item : items) {
            Set<String> candidates = moduleKeysOf(item, byTitle);
            keys.addAll(candidates);

            List<String> required = requiredRolesOf(candidates, configured);
            item.setModuleRoles(required);
            indexRequiredRoles(item, required, byLink, byMenuTitle);
        }

        this.knownModuleKeys.set(Collections.unmodifiableSet(new TreeSet<>(keys)));
        this.rolesByLink.set(Map.copyOf(byLink));
        this.rolesByMenuTitle.set(Map.copyOf(byMenuTitle));
        this.resolved = true;
    }

    /**
     * Die geforderten Rollen eines Menuepunkts: fuer jeden Kandidaten-Key die konfigurierte Rolle,
     * in Fundreihenfolge und ohne Dubletten.
     *
     * @param candidates Kandidaten-Keys des Menuepunkts
     * @param configured konfigurierte Zuordnung Modul-Key -&gt; Rolle
     * @return geforderte Rollen, ggf. leer (nie {@code null})
     */
    private static List<String> requiredRolesOf(Set<String> candidates, Map<String, String> configured) {
        List<String> required = new ArrayList<>();
        for (String candidate : candidates) {
            String role = configured.get(candidate);
            if (role != null && !required.contains(role)) {
                required.add(role);
            }
        }
        return required;
    }

    /**
     * Traegt einen rollenpflichtigen Menuepunkt in die Nachschlagetabellen der Kacheln ein — ueber
     * seinen kanonischen Link und ersatzweise ueber seinen Titel.
     *
     * @param item        der Menuepunkt
     * @param required    seine geforderten Rollen; ist die Liste leer, passiert nichts
     * @param byLink      Tabelle Link -&gt; Rollen, wird ergaenzt
     * @param byMenuTitle Tabelle Titel -&gt; Rollen, wird ergaenzt
     */
    private static void indexRequiredRoles(MenuItemImpl item, List<String> required,
                                           Map<String, List<String>> byLink,
                                           Map<String, List<String>> byMenuTitle) {
        if (required.isEmpty()) {
            return;
        }
        String link = canonicalLink(item.getCommand());
        if (!link.isEmpty()) {
            byLink.merge(link, required, ModuleRoleService::union);
        }
        String title = item.getTitle();
        if (title != null && !title.trim().isEmpty()) {
            byMenuTitle.merge(title.trim(), required, ModuleRoleService::union);
        }
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return List.copyOf(merged);
    }

    private static Map<String, List<MenuItemImpl>> indexByTitle(Collection<MenuItemImpl> items) {
        Map<String, List<MenuItemImpl>> ret = new LinkedHashMap<>();
        for (MenuItemImpl item : items) {
            String title = item.getTitle();
            if (title != null && !title.trim().isEmpty()) {
                ret.computeIfAbsent(title.trim(), k -> new ArrayList<>()).add(item);
            }
        }
        return ret;
    }

    /**
     * Die Modul-Keys, unter denen ein Menuepunkt ansprechbar ist: die eigene {@code moduleId}, die
     * {@code moduleId} jedes Elternmenues und die Menu-Root-Id des obersten Elternmenues.
     *
     * @param item    der Menuepunkt
     * @param byTitle Index Titel -&gt; Menuepunkte, fuer den Aufstieg zum Elternmenue
     * @return Kandidaten-Keys in kanonischer Form (nie {@code null})
     */
    static Set<String> moduleKeysOf(MenuItemImpl item, Map<String, List<MenuItemImpl>> byTitle) {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();

        MenuItemImpl current = item;
        MenuItemImpl topmost = item;
        int depth = 0;
        while (current != null && depth < MAX_PARENT_TIEFE) {
            topmost = current;
            addKey(keys, current.getModuleId());

            String parentTitle = current.getParent();
            if (parentTitle == null || parentTitle.trim().isEmpty() || !visited.add(parentTitle.trim())) {
                break;
            }
            List<MenuItemImpl> parents = byTitle.get(parentTitle.trim());
            current = (parents == null || parents.isEmpty()) ? null : parents.get(0);
            depth++;
        }

        // Fallback fuer Module ohne moduleId: die Menu-Root-Id des obersten Menuepunkts.
        addKey(keys, topmost.getEffectiveMenuId());
        return keys;
    }

    private static void addKey(Set<String> keys, String raw) {
        String key = ModuleRoleProperties.canonicalKey(raw);
        if (!key.isEmpty()) {
            keys.add(key);
        }
    }

    /**
     * Kanonische Form eines Menue-Links: kleingeschrieben, ohne fuehrenden Slash, ohne Query und
     * ohne die Endungen {@code .xhtml}, {@code .jsf}, {@code .html}, {@code .htm} — dieselbe
     * Normalisierung wie im {@code PageAccessGuardService}.
     *
     * @param link roher Link, darf {@code null} sein
     * @return kanonischer Link, nie {@code null}
     */
    static String canonicalLink(String link) {
        if (link == null) {
            return "";
        }
        String value = link.trim().toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        for (String suffix : new String[]{".xhtml", ".jsf", ".html", ".htm"}) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }
}
