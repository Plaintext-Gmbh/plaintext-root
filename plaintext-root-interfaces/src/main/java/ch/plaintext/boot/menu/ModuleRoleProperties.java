/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Konfigurierbare Modul-Rollen: ordnet einem Modul-Key pro Anwendung eine Rolle zu, die das
 * gesamte Modul ein- bzw. ausschaltet.
 *
 * <pre>
 * plaintext:
 *   menu:
 *     module-roles:
 *       wiki: wiki
 *       mailbox: mail
 *       postkonto: finanzen
 * </pre>
 *
 * <p>Ohne die konfigurierte Rolle ist weder ein Menuepunkt noch eine Dashboard-Kachel des Moduls
 * sichtbar, und der {@code PageAccessGuard} verweigert den Direktaufruf der Seiten.
 * {@code admin} und {@code root} umgehen die Pruefung immer. Module ohne Eintrag verhalten sich
 * unveraendert.</p>
 *
 * <p><b>Warum hier und nicht im Modul-Code:</b> Dasselbe Modul-Artefakt wird von mehreren
 * Anwendungen gebuendelt (z.B. {@code plaintext-z-mailbox} laeuft in app.plaintext.ch UND in
 * app.guild42.ch). Die Zuordnung gehoert deshalb in die Konfiguration der jeweiligen Anwendung,
 * nicht in das Modul.</p>
 *
 * <p><b>Modul-Key:</b> primaer die {@code moduleId} aus
 * {@code @MenuAnnotation(moduleId = "...")} bzw. {@code ModuleDescriptor#moduleId()} — genau der
 * Wert, den das Admin-Panel „Root | Module" anzeigt. Fuer Module ohne {@code moduleId} greift als
 * Fallback die Menu-Root-Id (der {@code menuId} bzw. der aus dem Titel abgeleitete Bezeichner des
 * obersten Menuepunkts des Moduls). Beim Start protokolliert das Framework alle erkannten
 * Modul-Keys; ein Key ohne Treffer wird als WARN gemeldet.</p>
 *
 * @author info@plaintext.ch
 * @since 1.604.0
 */
@ConfigurationProperties(prefix = "plaintext.menu")
public class ModuleRoleProperties {

    /** Modul-Key -&gt; Rollenname (ohne {@code ROLE_}-Prefix, Gross-/Kleinschreibung egal). */
    private Map<String, String> moduleRoles = new LinkedHashMap<>();

    public Map<String, String> getModuleRoles() {
        return moduleRoles;
    }

    public void setModuleRoles(Map<String, String> moduleRoles) {
        this.moduleRoles = moduleRoles == null ? new LinkedHashMap<>() : moduleRoles;
    }

    /**
     * Die Zuordnung in kanonischer Form: Modul-Key kleingeschrieben, Rollenname GROSS und ohne
     * {@code ROLE_}-Prefix — die Schreibweise, in der {@code SecurityProvider#hasRole(String)}
     * geprueft wird. Leere Keys oder Rollen werden verworfen.
     *
     * @return kanonische Zuordnung, alphabetisch nach Modul-Key sortiert (nie {@code null})
     */
    public Map<String, String> canonicalModuleRoles() {
        Map<String, String> ret = new TreeMap<>();
        for (Map.Entry<String, String> entry : moduleRoles.entrySet()) {
            String key = canonicalKey(entry.getKey());
            String role = canonicalRole(entry.getValue());
            if (!key.isEmpty() && !role.isEmpty()) {
                ret.put(key, role);
            }
        }
        return ret;
    }

    /** {@code true}, wenn keine einzige gueltige Zuordnung konfiguriert ist. */
    public boolean isEmpty() {
        return canonicalModuleRoles().isEmpty();
    }

    /**
     * Kanonische Form eines Modul-Keys: getrimmt und kleingeschrieben.
     *
     * @param key roher Key, darf {@code null} sein
     * @return kanonischer Key, nie {@code null}
     */
    public static String canonicalKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Kanonische Form eines Rollennamens: getrimmt, ohne {@code ROLE_}-Prefix, GROSS.
     *
     * @param role roher Rollenname, darf {@code null} sein
     * @return kanonischer Rollenname, nie {@code null}
     */
    public static String canonicalRole(String role) {
        if (role == null) {
            return "";
        }
        String value = role.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("ROLE_")) {
            value = value.substring("ROLE_".length());
        }
        return value;
    }
}
