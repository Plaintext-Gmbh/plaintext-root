/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import ch.plaintext.MenuRegistry;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.MenuRegistryImpl;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostics view of menu visibility (root only).
 *
 * <p>Shows every menu item with the four filters from {@code MenuItemImpl.isOn()} — role,
 * module role, module active, tenant — and, per row, the concrete reason whenever one of them
 * says no. In impersonation mode this is the view of the impersonated user, because the menu
 * items query the {@code SecurityProvider} of the running session.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@Slf4j
@Scope("session")
@Component
@MenuAnnotation(
        icon = "pi pi-search",
        title = "Menü-Diagnose",
        parent = "Root",
        link = "menudiagnose.html",
        order = 66,
        roles = {"ROOT"}
)
public class MenuDiagnoseBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private final transient MenuRegistry menuRegistry;
    private final transient MenuDiagnoseService diagnoseService;
    private final transient PlaintextSecurity plaintextSecurity;

    @Getter
    private List<MenuDiagnoseZeile> zeilen = new ArrayList<>();

    /** If set, only the rows that have at least one no are displayed. */
    @Getter
    @Setter
    private boolean nurUnsichtbare;

    /**
     * @param menuRegistry      supplies the registered menu items
     * @param visibilityService supplies the reason given by the tenant filter
     * @param plaintextSecurity supplies user, tenant and impersonation state
     */
    public MenuDiagnoseBackingBean(MenuRegistry menuRegistry,
                                   MandateMenuVisibilityService visibilityService,
                                   PlaintextSecurity plaintextSecurity) {
        this.menuRegistry = menuRegistry;
        this.diagnoseService = new MenuDiagnoseService(visibilityService);
        this.plaintextSecurity = plaintextSecurity;
    }

    /**
     * preRenderView listener: reloads the analysis fresh on every GET. The isPostback guard
     * prevents a reload on Ajax postbacks (the filter toggle).
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        aktualisieren();
    }

    /** Re-evaluates the menu tree. */
    public void aktualisieren() {
        try {
            zeilen = diagnoseService.analysiereAlle(menuePunkte(), getMandant());
            log.debug("Menue-Diagnose: {} Menuepunkte ausgewertet fuer Mandant '{}'", zeilen.size(), getMandant());
        } catch (Exception e) {
            log.error("Menue-Diagnose konnte nicht ausgewertet werden", e);
            zeilen = new ArrayList<>();
        }
    }

    /**
     * The registered menu items. As in the {@code PageAccessGuardService}, obtained via
     * {@link MenuRegistryImpl#getAllMenuItemsImpl()} to avoid classloader problems inside the
     * Spring Boot JAR.
     *
     * @return all menu items, possibly empty
     */
    private List<MenuItemImpl> menuePunkte() {
        if (menuRegistry instanceof MenuRegistryImpl impl) {
            return impl.getAllMenuItemsImpl();
        }
        log.warn("Menue-Diagnose: MenuRegistry ist kein MenuRegistryImpl ({}) — keine Auswertung moeglich",
                menuRegistry == null ? "null" : menuRegistry.getClass().getName());
        return List.of();
    }

    /**
     * The rows to display — all of them, or only those with at least one no.
     *
     * @return filtered rows
     */
    public List<MenuDiagnoseZeile> getAnzeigeZeilen() {
        if (!nurUnsichtbare) {
            return zeilen;
        }
        List<MenuDiagnoseZeile> ret = new ArrayList<>();
        for (MenuDiagnoseZeile zeile : zeilen) {
            if (!zeile.sichtbar()) {
                ret.add(zeile);
            }
        }
        return ret;
    }

    /**
     * The tenant whose list is analysed.
     *
     * @return tenant name, or {@code "—"} if none is set
     */
    public String getMandant() {
        try {
            String mandat = plaintextSecurity == null ? null : plaintextSecurity.getMandat();
            return mandat == null || mandat.isBlank() ? "—" : mandat;
        } catch (Exception e) {
            log.debug("Mandant nicht ermittelbar: {}", e.getMessage());
            return "—";
        }
    }

    /**
     * The user whose view is shown.
     *
     * @return user name, or {@code "—"}
     */
    public String getBenutzer() {
        try {
            String user = plaintextSecurity == null ? null : plaintextSecurity.getUser();
            return user == null || user.isBlank() ? "—" : user;
        } catch (Exception e) {
            log.debug("Benutzer nicht ermittelbar: {}", e.getMessage());
            return "—";
        }
    }

    /**
     * Whether another user is currently being impersonated — in that case the table shows THEIR
     * view.
     *
     * @return {@code true} in impersonation mode
     */
    public boolean isImpersonating() {
        try {
            return plaintextSecurity != null && plaintextSecurity.isImpersonating();
        } catch (Exception e) {
            log.debug("Impersonate-Zustand nicht ermittelbar: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Number of menu items the current user can see.
     *
     * @return number of visible menu items
     */
    public long getAnzahlSichtbar() {
        return zeilen.stream().filter(MenuDiagnoseZeile::sichtbar).count();
    }

    /**
     * Total number of registered menu items.
     *
     * @return number of analysed menu items
     */
    public int getAnzahlGesamt() {
        return zeilen.size();
    }
}
