/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.TileRegistry;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.boot.menu.SecurityProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementierung einer Dashboard-Kachel – analog zu
 * {@link ch.plaintext.boot.menu.MenuItemImpl}. Hält die Metadaten der {@code @DashboardTile}-
 * Annotation und entscheidet über die Sichtbarkeit (Rollen + {@link MenuVisibilityProvider}).
 *
 * @author plaintext.ch
 */
@Data
@Slf4j
public class TileItemImpl implements TileRegistry.TileItem {

    private String id = "";
    private String title = "";
    private String icon = "";
    private String image = "";
    private String link = "";
    private int order = 100;
    private String menuTitle = "";
    private List<String> roles = new ArrayList<>();
    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;
    private ModuleRoleService moduleRoleService;
    private BeanFactory beanFactory;

    /**
     * Liefert den Titel, gegen den die mandatsspezifische Sichtbarkeit geprüft wird:
     * {@link #menuTitle} falls gesetzt, sonst {@link #title}.
     */
    public String getVisibilityTitle() {
        return (menuTitle == null || menuTitle.trim().isEmpty()) ? title : menuTitle;
    }

    /**
     * Konfigurierbare Modul-Rolle ({@code plaintext.menu.module-roles}): Die Kachel wird ueber
     * ihren {@link #link} — ersatzweise ueber ihren {@link #menuTitle} — dem Modul-Menue
     * zugeordnet und verschwindet damit konsistent mit dem Menuepunkt. {@code admin}/{@code root}
     * umgehen die Pruefung.
     *
     * @return {@code true}, wenn keine Modul-Rolle gefordert ist oder der Benutzer sie haelt
     */
    private boolean isModuleRoleVisible() {
        if (moduleRoleService == null && beanFactory != null) {
            try {
                moduleRoleService = beanFactory.getBean(ModuleRoleService.class);
            } catch (Exception e) {
                log.debug("Kein ModuleRoleService verfügbar für Kachel '{}': {}", title, e.getMessage());
            }
        }
        if (moduleRoleService == null) {
            return true;
        }
        boolean visible = moduleRoleService.isAllowedForLink(link, getVisibilityTitle(), securityProvider);
        if (!visible) {
            log.debug("Modul-Rolle fehlt - Kachel '{}' ausgeblendet", title);
        }
        return visible;
    }

    @Override
    public boolean isOn() {
        // Zuerst rollenbasierte Sichtbarkeit prüfen
        if (roles != null && !roles.isEmpty() && securityProvider != null) {
            boolean hasRole = false;
            for (String role : roles) {
                String upperRole = role != null ? role.toUpperCase() : role;
                if (securityProvider.hasRole(upperRole)) {
                    hasRole = true;
                    break;
                }
            }
            if (!hasRole) {
                return false;
            }
        }

        // Konfigurierbare Modul-Rolle: eine Kachel verschwindet zusammen mit ihrem Modul-Menue.
        if (!isModuleRoleVisible()) {
            return false;
        }

        // MenuVisibilityProvider bei Bedarf lazy aus der BeanFactory laden
        if (menuVisibilityProvider == null && beanFactory != null) {
            try {
                menuVisibilityProvider = beanFactory.getBean(MenuVisibilityProvider.class);
                log.debug("Lazy-loaded MenuVisibilityProvider für Kachel: {}", title);
            } catch (Exception e) {
                log.debug("Kein MenuVisibilityProvider verfügbar für Kachel '{}': {}", title, e.getMessage());
            }
        }

        // Mandatsspezifische Sichtbarkeit prüfen, wenn ein Provider verfügbar ist
        if (menuVisibilityProvider != null) {
            String fullTitle = getVisibilityTitle();
            boolean visible = menuVisibilityProvider.isMenuVisible(fullTitle);
            if (!visible) {
                log.debug("MenuVisibilityProvider hat Kachel ausgeblendet: {}", fullTitle);
            }
            return visible;
        }

        return true;
    }
}
