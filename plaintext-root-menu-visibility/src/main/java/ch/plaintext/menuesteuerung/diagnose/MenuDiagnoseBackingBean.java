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
 * Diagnose-Ansicht der Menue-Sichtbarkeit (nur root).
 *
 * <p>Zeigt jeden Menuepunkt mit den vier Filtern aus {@code MenuItemImpl.isOn()} — Rolle,
 * Modul-Rolle, Modul aktiv, Mandant — und pro Zeile dem konkreten Grund, wenn einer Nein sagt.
 * Im Impersonate-Modus ist das die Sicht des impersonierten Benutzers, weil die Menuepunkte den
 * {@code SecurityProvider} der laufenden Session befragen.</p>
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

    /** Wenn gesetzt, werden nur die Zeilen angezeigt, die mindestens ein Nein haben. */
    @Getter
    @Setter
    private boolean nurUnsichtbare;

    /**
     * @param menuRegistry      liefert die registrierten Menuepunkte
     * @param visibilityService liefert den Grund des Mandantenfilters
     * @param plaintextSecurity liefert Benutzer, Mandant und Impersonate-Zustand
     */
    public MenuDiagnoseBackingBean(MenuRegistry menuRegistry,
                                   MandateMenuVisibilityService visibilityService,
                                   PlaintextSecurity plaintextSecurity) {
        this.menuRegistry = menuRegistry;
        this.diagnoseService = new MenuDiagnoseService(visibilityService);
        this.plaintextSecurity = plaintextSecurity;
    }

    /**
     * preRenderView-Listener: laedt die Auswertung bei jedem GET frisch. Der isPostback-Guard
     * verhindert das Neuladen bei Ajax-Postbacks (Filter-Umschalter).
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        aktualisieren();
    }

    /** Wertet den Menuebaum neu aus. */
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
     * Die registrierten Menuepunkte. Wie im {@code PageAccessGuardService} ueber
     * {@link MenuRegistryImpl#getAllMenuItemsImpl()}, um Classloader-Probleme im Spring-Boot-JAR
     * zu vermeiden.
     *
     * @return alle Menuepunkte, ggf. leer
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
     * Die anzuzeigenden Zeilen — alle, oder nur die mit mindestens einem Nein.
     *
     * @return gefilterte Zeilen
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
     * Der Mandant, dessen Liste ausgewertet wird.
     *
     * @return Mandantenname, oder {@code "—"} wenn keiner gesetzt ist
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
     * Der Benutzer, dessen Sicht gezeigt wird.
     *
     * @return Benutzername, oder {@code "—"}
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
     * Ob gerade ein anderer Benutzer impersoniert wird — dann zeigt die Tabelle DESSEN Sicht.
     *
     * @return {@code true} im Impersonate-Modus
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
     * Anzahl der Menuepunkte, die der aktuelle Benutzer sieht.
     *
     * @return Anzahl sichtbarer Menuepunkte
     */
    public long getAnzahlSichtbar() {
        return zeilen.stream().filter(MenuDiagnoseZeile::sichtbar).count();
    }

    /**
     * Gesamtzahl der registrierten Menuepunkte.
     *
     * @return Anzahl ausgewerteter Menuepunkte
     */
    public int getAnzahlGesamt() {
        return zeilen.size();
    }
}
