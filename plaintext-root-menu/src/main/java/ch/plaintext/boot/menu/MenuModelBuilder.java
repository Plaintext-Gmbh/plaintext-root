/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.I18nProvider;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.model.menu.DefaultMenuModel;
import org.primefaces.model.menu.MenuModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.*;
import java.util.function.Supplier;

/**
 * Builder for creating PrimeFaces MenuModel from annotated menu items
 */
@Slf4j
public class MenuModelBuilder {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private I18nProvider i18nProvider;

    /**
     * Creates a language supplier that reads the current user's language preference.
     * Uses ApplicationContext to lazily resolve the session-scoped UserPreferencesBackingBean.
     */
    private Supplier<String> createLanguageSupplier() {
        return () -> {
            try {
                Object bean = applicationContext.getBean("userPreferencesBackingBean");
                if (bean != null) {
                    java.lang.reflect.Method method = bean.getClass().getMethod("getLanguage");
                    return (String) method.invoke(bean);
                }
            } catch (Exception e) {
                log.debug("Could not resolve user language, using default 'de': {}", e.getMessage());
            }
            return "de";
        };
    }

    /**
     * Creates a PrimefacesMenuItem with i18n support injected.
     */
    private PrimefacesMenuItem createMenuItem(AbstractMenuItem item, Supplier<String> langSupplier) {
        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);
        if (i18nProvider != null) {
            menuItem.setI18nProvider(i18nProvider);
            menuItem.setLanguageSupplier(langSupplier);
        }
        return menuItem;
    }

    /**
     * Creates a PrimefacesSubmenu with i18n support injected.
     */
    private PrimefacesSubmenu createSubmenu(AbstractMenuItem item, Supplier<String> langSupplier) {
        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);
        if (i18nProvider != null) {
            submenu.setI18nProvider(i18nProvider);
            submenu.setLanguageSupplier(langSupplier);
        }
        return submenu;
    }

    /**
     * Build a MenuModel from all registered MenuItemImpl beans
     * @return the constructed MenuModel
     */
    public MenuModel buildMenuModel() {
        DefaultMenuModel menuModel = new DefaultMenuModel();
        Supplier<String> langSupplier = createLanguageSupplier();

        // Get all MenuItemImpl beans from Spring context
        Map<String, MenuItemImpl> menuBeans = applicationContext.getBeansOfType(MenuItemImpl.class);
        List<MenuItemImpl> allMenuItems = new ArrayList<>(menuBeans.values());

        if (allMenuItems.isEmpty()) {
            log.warn("No menu items found in Spring context");
            return menuModel;
        }

        // Filter only visible items
        List<MenuItemImpl> visibleItems = allMenuItems.stream()
            .filter(MenuItemImpl::isOn)
            .toList();

        if (visibleItems.isEmpty()) {
            log.warn("No visible menu items found");
            return menuModel;
        }

        // Sort by order
        visibleItems = new ArrayList<>(visibleItems);
        visibleItems.sort(Comparator.comparingInt(MenuItemImpl::getOrder));

        // Menü-Titel gegen Duplikate absichern: Die Hierarchie wird unten ausschliesslich per
        // Titel als Map-Key aufgebaut. Zwei Items mit gleichem Titel würden sich in submenuMap/
        // itemMap still überschreiben und die Hierarchie zerstören - im schlimmsten Fall wird
        // dasselbe Submenu-Objekt mehrfach eingehängt (verschachteltes Submenu/ELException, das
        // jede eingeloggte Seite crasht). Deshalb wird pro Titel deterministisch das erste
        // Vorkommen (niedrigste order) behalten und jedes weitere Duplikat mit WARN verworfen.
        visibleItems = deduplicateByTitle(visibleItems);

        // Build hierarchy using two-pass algorithm
        Map<String, PrimefacesSubmenu> submenuMap = new HashMap<>();
        Map<String, MenuItemImpl> itemMap = new HashMap<>();

        // First pass: Create all menu items and submenus, register them by title
        for (MenuItemImpl item : visibleItems) {
            itemMap.put(item.getTitle(), item);

            // If item has children, create a submenu for it
            if (hasChildren(item.getTitle(), visibleItems)) {
                PrimefacesSubmenu submenu = createSubmenu(item, langSupplier);
                submenuMap.put(item.getTitle(), submenu);
                log.debug("Created submenu: {}", item.getTitle());
            }
        }

        // Second pass: Build the hierarchy by adding items to their parents
        for (MenuItemImpl item : visibleItems) {
            String parent = item.getParent();

            if (parent == null || parent.trim().isEmpty()) {
                // Root level item
                if (hasChildren(item.getTitle(), visibleItems)) {
                    PrimefacesSubmenu submenu = submenuMap.get(item.getTitle());
                    menuModel.getElements().add(submenu);
                    log.debug("Added root submenu: {}", item.getTitle());
                } else {
                    PrimefacesMenuItem menuItem = createMenuItem(item, langSupplier);
                    menuModel.getElements().add(menuItem);
                    log.debug("Added root menu item: {}", item.getTitle());
                }
            } else {
                // Child item - find parent submenu
                PrimefacesSubmenu parentSubmenu = submenuMap.get(parent);

                if (parentSubmenu == null) {
                    meldeFehlendesElternmenue(parent, item, allMenuItems);
                    continue;
                }

                // Check if this item has children
                if (hasChildren(item.getTitle(), visibleItems)) {
                    PrimefacesSubmenu submenu = submenuMap.get(item.getTitle());
                    parentSubmenu.getElements().add(submenu);
                    log.debug("Added submenu '{}' to parent '{}'", item.getTitle(), parent);
                } else {
                    PrimefacesMenuItem menuItem = createMenuItem(item, langSupplier);
                    parentSubmenu.getElements().add(menuItem);
                    log.debug("Added menu item '{}' to parent '{}'", item.getTitle(), parent);
                }
            }
        }

        // Laeuft bei jedem Seitenaufruf jedes Benutzers (MenuBean ist view-scoped) — debug wie die
        // Einzelschritte darueber, sonst verdeckt es echte INFO-Meldungen im Betriebslog.
        log.debug("Built menu model with {} top-level elements", menuModel.getElements().size());
        return menuModel;
    }

    private boolean hasChildren(String title, List<MenuItemImpl> visibleItems) {
        return visibleItems.stream()
            .anyMatch(item -> title.equals(item.getParent()));
    }

    /**
     * Meldet ein Kind, das ohne Elterncontainer dasteht — und unterscheidet dabei die beiden
     * Faelle, die vorher beide als WARN herausgingen (Karte 521).
     *
     * <p><b>Elternmenue existiert, ist aber nicht sichtbar.</b> Das ist der <i>Normalbetrieb</i>,
     * kein Fehler: Ein Kind darf breiter erreichbar sein als sein Elternmenue. Beispiele aus dem
     * Framework: {@code API Token} und {@code Benachrichtigungen} deklarieren
     * {@code roles={"USER","ADMIN","ROOT"}}, haengen aber unter {@code Admin} (nur ADMIN) bzw.
     * {@code Root} (nur ROOT) — fuer einen normalen Benutzer ist das Elternmenue also unsichtbar
     * und das Kind sichtbar. Dasselbe entsteht, wenn ein Modul deaktiviert oder ein Menue fuer
     * einen Mandanten ausgeblendet ist, das Kind aber nicht. In einem hierarchischen Menue laesst
     * sich ein solches Kind nicht darstellen; Ueberspringen ist richtig — nur ist es eben nichts,
     * wovor man warnen muesste.
     *
     * <p><b>Elternmenue existiert nirgends.</b> Das ist ein echter Konfigurationsfehler (Tippfehler
     * im {@code parent}, entferntes Elternmenue) und bleibt WARN — die Seite ist dann fuer
     * <i>niemanden</i> ueber das Menue erreichbar.
     *
     * <p><b>Warum das zaehlt:</b> In plaintext-app erzeugten acht Eintraege des ersten Falls
     * <b>1218 WARN-Zeilen pro Tag — 79 % aller Warnungen der Anwendung</b> (Karte 521, gemessen in
     * Graylog ueber 24 h). Eine Warnung, die immer dasteht, wird nicht mehr gelesen; echte
     * Warnungen gehen darin unter.
     *
     * @param parent       Titel des gesuchten Elternmenues
     * @param item         das Kind, das nicht eingehaengt werden konnte
     * @param allMenuItems <b>alle</b> registrierten Menuepunkte, auch die unsichtbaren — nur so
     *                     laesst sich "unsichtbar" von "gibt es nicht" unterscheiden
     */
    private void meldeFehlendesElternmenue(String parent, MenuItemImpl item,
                                           List<MenuItemImpl> allMenuItems) {
        boolean existiert = allMenuItems.stream()
                .anyMatch(kandidat -> parent.equals(kandidat.getTitle()));
        if (existiert) {
            log.debug("Parent submenu '{}' exists but is not visible for the current user/mandate "
                    + "- skipping item '{}'", parent, item.getTitle());
            return;
        }
        log.warn("Parent submenu '{}' does not exist for item '{}' - skipping. The item is "
                + "unreachable via the menu for EVERY user; fix the parent title in its "
                + "@MenuAnnotation or add the missing parent menu.", parent, item.getTitle());
    }

    /**
     * Removes menu items whose title would corrupt the title-keyed hierarchy, while allowing leaf
     * items to share a title across different parents.
     * <p>
     * The hierarchy builder resolves a child's {@code parent} link by the parent's <em>title</em>
     * (see {@link #buildMenuModel()}: {@code submenuMap} / {@code hasChildren} are keyed by title).
     * Two distinct rules follow from that:
     * <ul>
     *   <li><b>Container titles</b> (titles referenced as a {@code parent} somewhere) must be
     *       globally unique. A duplicate makes the parent lookup ambiguous and can nest a submenu
     *       into itself (ELException crashing every logged-in page), so duplicates are dropped,
     *       lowest order wins.</li>
     *   <li><b>Leaf titles</b> are never used as a lookup key, so the same leaf title under
     *       <em>different</em> parents is safe (e.g. "Karte" under both "Wanderungen" and "Lauftage",
     *       each pointing at its own page). These must NOT be dropped - the previous global dedup
     *       silently hid distinct pages from the menu. Only an exact {@code parent+title} duplicate
     *       (the same entry registered twice) is collapsed, lowest order wins.</li>
     * </ul>
     * The incoming list is already sorted by order, so "first occurrence" is deterministic. Each
     * dropped duplicate is logged at WARN so the offending module can be fixed.
     *
     * @param sortedItems visible menu items, sorted by order
     * @return the items with corrupting/exact duplicates removed, preserving order
     */
    private List<MenuItemImpl> deduplicateByTitle(List<MenuItemImpl> sortedItems) {
        Set<String> containerTitles = collectContainerTitles(sortedItems);

        Set<String> seenContainers = new HashSet<>();          // container titles already kept
        Map<String, MenuItemImpl> seenLeaves = new HashMap<>(); // parent+title -> kept leaf
        List<MenuItemImpl> result = new ArrayList<>();
        for (MenuItemImpl item : sortedItems) {
            if (containerTitles.contains(item.getTitle())) {
                keepContainerUnlessDuplicate(item, seenContainers, result);
            } else {
                keepLeafUnlessDuplicate(item, seenLeaves, result);
            }
        }
        return result;
    }

    /** Titles referenced as a {@code parent} somewhere - the only titles that must stay unique. */
    private static Set<String> collectContainerTitles(List<MenuItemImpl> sortedItems) {
        Set<String> containerTitles = new HashSet<>();
        for (MenuItemImpl item : sortedItems) {
            String parent = item.getParent();
            if (parent != null && !parent.trim().isEmpty()) {
                containerTitles.add(parent);
            }
        }
        return containerTitles;
    }

    /**
     * Keeps the first occurrence of a container title and drops (with WARN) every later duplicate,
     * which would otherwise make the title-keyed parent lookup ambiguous. Lowest order wins because
     * the incoming list is sorted by order.
     */
    private void keepContainerUnlessDuplicate(MenuItemImpl item, Set<String> seenContainers,
                                              List<MenuItemImpl> result) {
        String title = item.getTitle();
        if (seenContainers.add(title)) {
            result.add(item);
            return;
        }
        MenuItemImpl kept = result.stream()
                .filter(i -> title.equals(i.getTitle()))
                .findFirst().orElse(item);
        log.warn("Duplicate menu container title '{}' detected (ignored item: parent='{}', "
                + "order={}; kept item: parent='{}', order={}). A title used as a parent "
                + "must be globally unique - rename the offending menu item to avoid a "
                + "corrupted menu hierarchy.",
            title, item.getParent(), item.getOrder(), kept.getParent(), kept.getOrder());
    }

    /**
     * Keeps the first leaf per {@code parent+title} and drops (with WARN) only an exact duplicate,
     * so the same leaf title stays allowed under different parents.
     */
    private void keepLeafUnlessDuplicate(MenuItemImpl item, Map<String, MenuItemImpl> seenLeaves,
                                         List<MenuItemImpl> result) {
        String title = item.getTitle();
        String leafKey = (item.getParent() == null ? "" : item.getParent()) + "\u0000" + title;
        MenuItemImpl kept = seenLeaves.putIfAbsent(leafKey, item);
        if (kept == null) {
            result.add(item);
            return;
        }
        log.warn("Duplicate menu item '{}' under parent '{}' detected (ignored order={}; "
                + "kept order={}). The same menu entry is registered more than once.",
            title, item.getParent(), item.getOrder(), kept.getOrder());
    }
}
