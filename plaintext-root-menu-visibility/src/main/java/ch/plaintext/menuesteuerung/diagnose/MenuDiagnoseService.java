/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import ch.plaintext.boot.menu.MenuAccessPolicy;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates the four visibility filters <b>individually</b> for every registered menu item and
 * names the reason behind each no.
 *
 * <p><b>Why this is needed.</b> {@code MenuItemImpl.isOn()} combines four independent filters with
 * AND and yields a single truth value. When a menu item is missing, there is no way to tell which
 * of the four takes it away — role, module role, a deactivated module or the tenant list. This
 * analysis makes all four answers visible.</p>
 *
 * <p><b>No second truth.</b> The yes/no values come from the very same methods the menu and the
 * {@code PageAccessGuard} use ({@link MenuItemImpl#isRoleVisible()} and its three siblings). Only
 * the <i>text</i> is produced here, so the diagnostics cannot drift away from the actual
 * decision.</p>
 *
 * <p><b>Impersonation.</b> The menu items carry the {@code SecurityProvider} of the running
 * session, and impersonation swaps the {@code Authentication} in exactly that session. The
 * analysis therefore shows the impersonated user's view without any extra effort.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@Slf4j
public class MenuDiagnoseService {

    private final MandateMenuVisibilityService visibilityService;

    /**
     * @param visibilityService supplies the plain-text reason given by the tenant filter
     */
    public MenuDiagnoseService(MandateMenuVisibilityService visibilityService) {
        this.visibilityService = visibilityService;
    }

    /**
     * Analyses all menu items, alphabetically by full title.
     *
     * @param items   the registered menu items
     * @param mandant the tenant whose list is checked
     * @return one row per menu item (never {@code null})
     */
    public List<MenuDiagnoseZeile> analysiereAlle(Collection<MenuItemImpl> items, String mandant) {
        List<MenuDiagnoseZeile> zeilen = new ArrayList<>();
        if (items == null) {
            return zeilen;
        }
        for (MenuItemImpl item : items) {
            zeilen.add(analysiere(item, mandant));
        }
        zeilen.sort(Comparator.comparing(MenuDiagnoseZeile::titel, String.CASE_INSENSITIVE_ORDER));
        return zeilen;
    }

    /**
     * Analyses a single menu item.
     *
     * <p>The <b>decision</b> is made by the menu item itself, and therefore for the tenant of the
     * running session. The {@code mandant} parameter serves the <b>reason text</b>: it names the
     * list to look in. Callers therefore pass the same tenant the session carries.</p>
     *
     * @param item    the menu item
     * @param mandant the tenant whose list is checked
     * @return the diagnostics row
     */
    public MenuDiagnoseZeile analysiere(MenuItemImpl item, String mandant) {
        boolean rolleOk = item.isRoleVisible();
        boolean modulRolleOk = item.isModuleRoleVisible();
        boolean modulOk = item.isModuleVisible();
        boolean mandantOk = item.isMandateVisible();

        return new MenuDiagnoseZeile(
                item.buildFullTitle(),
                item.getCommand(),
                List.copyOf(item.getModuleKeys() == null ? List.of() : item.getModuleKeys()),
                rolleOk, rolleOk ? "" : rolleGrund(item),
                modulRolleOk, modulRolleOk ? "" : modulRolleGrund(item),
                modulOk, modulOk ? "" : modulGrund(item),
                mandantOk, mandantGrund(item, mandant, mandantOk),
                rolleOk && modulRolleOk && modulOk && mandantOk);
    }

    /**
     * Which roles the menu item requires — the question the first filter asks.
     *
     * @param item the menu item
     * @return plain text, e.g. {@code "Rolle ADMIN oder ROOT fehlt"}
     */
    private static String rolleGrund(MenuItemImpl item) {
        List<String> verlangt = new ArrayList<>();
        if (item.getRoles() != null) {
            for (String rolle : item.getRoles()) {
                if (rolle != null && !rolle.isBlank()) {
                    verlangt.add(rolle.toUpperCase(Locale.ROOT));
                }
            }
        }
        if (item.getAccessPolicy() == MenuAccessPolicy.STRICT) {
            verlangt.add(item.getAutoRole());
            if (item.getRoleStartsWith() != null) {
                for (String prefix : item.getRoleStartsWith()) {
                    if (prefix != null && !prefix.isBlank()) {
                        verlangt.add(prefix + "*");
                    }
                }
            }
        }
        if (verlangt.isEmpty()) {
            return "Rollenpruefung verweigert (keine passende Rolle)";
        }
        return "Rolle " + String.join(" oder ", verlangt) + " fehlt";
    }

    /**
     * Which configured module role is missing.
     *
     * @param item the menu item
     * @return plain text, e.g. {@code "Modul-Rolle WIKI fehlt (plaintext.menu.module-roles)"}
     */
    private static String modulRolleGrund(MenuItemImpl item) {
        List<String> rollen = item.getModuleRoles();
        if (rollen == null || rollen.isEmpty()) {
            return "Modul-Rolle fehlt";
        }
        return "Modul-Rolle " + String.join(" oder ", rollen) + " fehlt (plaintext.menu.module-roles)";
    }

    /**
     * Which module is switched off.
     *
     * @param item the menu item
     * @return plain text, e.g. {@code "Modul 'wiki' ist deaktiviert (Root | Module)"}
     */
    private static String modulGrund(MenuItemImpl item) {
        String modulId = item.getModuleId();
        String bezeichnung = modulId == null || modulId.isBlank() ? "?" : modulId;
        return "Modul '" + bezeichnung + "' ist deaktiviert (Root | Module)";
    }

    /**
     * The reason given by the tenant filter — or, when it lets the item through, the note about the
     * root-branch exemption (so it stays visible that the list was NOT what decided here).
     *
     * @param item      the menu item
     * @param mandant   the tenant
     * @param mandantOk result of the tenant filter
     * @return plain text, possibly empty
     */
    private String mandantGrund(MenuItemImpl item, String mandant, boolean mandantOk) {
        if (item.isRootBranchExemptFromMandate()) {
            return "Root-Zweig: fuer root vom Mandantenfilter ausgenommen";
        }
        if (mandantOk) {
            return "";
        }
        if (visibilityService == null) {
            return "durch die Mandanten-Liste ausgeblendet";
        }
        String grund = visibilityService.mandateReason(item.buildFullTitle(), item.getModuleKeys(), mandant);
        return grund.isEmpty() ? "durch die Mandanten-Liste ausgeblendet" : grund;
    }
}
