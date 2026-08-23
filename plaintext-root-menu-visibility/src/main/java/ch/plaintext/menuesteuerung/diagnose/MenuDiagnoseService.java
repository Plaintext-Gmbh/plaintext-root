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
 * Wertet fuer jeden registrierten Menuepunkt die vier Sichtbarkeits-Filter <b>einzeln</b> aus und
 * benennt zu jedem Nein den Grund.
 *
 * <p><b>Warum es das braucht.</b> {@code MenuItemImpl.isOn()} verknuepft vier unabhaengige Filter
 * mit UND und liefert einen einzigen Wahrheitswert. Fehlt ein Menuepunkt, ist damit nicht zu
 * erkennen, welcher der vier ihn wegnimmt — Rolle, Modul-Rolle, deaktiviertes Modul oder die
 * Mandanten-Liste. Diese Auswertung macht die vier Antworten sichtbar.</p>
 *
 * <p><b>Keine zweite Wahrheit.</b> Die Ja/Nein-Werte kommen aus denselben Methoden, die auch das
 * Menue und der {@code PageAccessGuard} benutzen ({@link MenuItemImpl#isRoleVisible()} und die
 * drei Geschwister). Hier entsteht nur der <i>Text</i> dazu; damit kann die Diagnose nicht von der
 * tatsaechlichen Entscheidung abdriften.</p>
 *
 * <p><b>Impersonate.</b> Die Menuepunkte tragen den {@code SecurityProvider} der laufenden Session,
 * und die Impersonation tauscht die {@code Authentication} in genau dieser Session aus. Die
 * Auswertung zeigt deshalb ohne Zutun die Sicht des impersonierten Benutzers.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@Slf4j
public class MenuDiagnoseService {

    private final MandateMenuVisibilityService visibilityService;

    /**
     * @param visibilityService liefert den Klartext-Grund des Mandantenfilters
     */
    public MenuDiagnoseService(MandateMenuVisibilityService visibilityService) {
        this.visibilityService = visibilityService;
    }

    /**
     * Wertet alle Menuepunkte aus, alphabetisch nach vollem Titel.
     *
     * @param items   die registrierten Menuepunkte
     * @param mandant der Mandant, dessen Liste geprueft wird
     * @return eine Zeile je Menuepunkt (nie {@code null})
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
     * Wertet einen einzelnen Menuepunkt aus.
     *
     * <p>Die <b>Entscheidung</b> trifft der Menuepunkt selbst und damit fuer den Mandanten der
     * laufenden Session. Der Parameter {@code mandant} dient dem <b>Grundtext</b>: er benennt die
     * Liste, in der nachzusehen ist. Der Aufrufer uebergibt deshalb denselben Mandanten, den auch
     * die Session traegt.</p>
     *
     * @param item    der Menuepunkt
     * @param mandant der Mandant, dessen Liste geprueft wird
     * @return die Diagnose-Zeile
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
     * Welche Rollen der Menuepunkt verlangt — die Frage, die der erste Filter stellt.
     *
     * @param item der Menuepunkt
     * @return Klartext, z.B. {@code "Rolle ADMIN oder ROOT fehlt"}
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
     * Welche konfigurierte Modul-Rolle fehlt.
     *
     * @param item der Menuepunkt
     * @return Klartext, z.B. {@code "Modul-Rolle WIKI fehlt (plaintext.menu.module-roles)"}
     */
    private static String modulRolleGrund(MenuItemImpl item) {
        List<String> rollen = item.getModuleRoles();
        if (rollen == null || rollen.isEmpty()) {
            return "Modul-Rolle fehlt";
        }
        return "Modul-Rolle " + String.join(" oder ", rollen) + " fehlt (plaintext.menu.module-roles)";
    }

    /**
     * Welches Modul abgeschaltet ist.
     *
     * @param item der Menuepunkt
     * @return Klartext, z.B. {@code "Modul 'wiki' ist deaktiviert (Root | Module)"}
     */
    private static String modulGrund(MenuItemImpl item) {
        String modulId = item.getModuleId();
        String bezeichnung = modulId == null || modulId.isBlank() ? "?" : modulId;
        return "Modul '" + bezeichnung + "' ist deaktiviert (Root | Module)";
    }

    /**
     * Der Grund des Mandantenfilters — oder, wenn er den Punkt durchlaesst, der Hinweis auf die
     * Root-Zweig-Ausnahme (damit sichtbar bleibt, dass hier NICHT die Liste entschieden hat).
     *
     * @param item      der Menuepunkt
     * @param mandant   der Mandant
     * @param mandantOk Ergebnis des Mandantenfilters
     * @return Klartext, ggf. leer
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
