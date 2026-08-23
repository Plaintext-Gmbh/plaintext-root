/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.Locale;
import java.util.Set;

/**
 * Welche Rollen nur {@code root} vergeben darf — und welche ausdruecklich auch {@code admin}.
 *
 * <p><b>Die Zustaendigkeitsregel.</b> <i>root</i> beantwortet, welche Module zu einem Mandanten
 * gehoeren (Mandanten-White-/Blacklist der Menuesteuerung). <i>admin</i> beantwortet, wer sie
 * benutzen darf — und dafuer braucht admin die Modul-Rollen. Eine Modul-Rolle
 * ({@code plaintext.menu.module-roles.<modul>=<rolle>}) verleiht nichts weiter als den Zugang zu
 * einem fachlichen Modul; sie ist damit <b>keine privilegierte Rolle</b> und fuer admin vergebbar.</p>
 *
 * <p><b>Privilegiert</b> — und deshalb ausschliesslich fuer root — ist:</p>
 * <ul>
 *   <li>{@code root} und {@code admin}: sie vergeben Verwaltungsrechte weiter. Ein admin, der
 *       {@code admin} oder {@code root} vergeben duerfte, koennte seine eigene Beschraenkung
 *       aufheben — die Trennung waere nur noch Dekoration.</li>
 *   <li>jede {@code PROPERTY_*}-Rolle: sie steuert Quereinstiege wie den Mandanten-Wechsel und
 *       wirkt damit ueber den eigenen Mandanten hinaus.</li>
 * </ul>
 *
 * <p><b>Bestand bleibt unangetastet.</b> Die Regel gilt fuer das <i>Neu-Vergeben</i>. Eine bereits
 * gespeicherte Zuweisung bleibt bestehen und bleibt editierbar; die aufrufenden Stellen pruefen
 * deshalb gegen den persistierten Stand, nicht gegen das Formular.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
public final class PrivilegedRoleRules {

    /** Rollen, die nur root vergeben darf (normalisiert: klein, ohne {@code ROLE_}-Prefix). */
    private static final Set<String> NUR_ROOT = Set.of("root", "admin");

    /** Praefix der Rollen, die ueber den eigenen Mandanten hinaus wirken. */
    private static final String QUERZUGRIFF_PREFIX = "property_";

    private PrivilegedRoleRules() {
    }

    /**
     * Darf diese Rolle nur von root vergeben werden?
     *
     * @param roleName Rollenname in beliebiger Schreibweise, mit oder ohne {@code ROLE_}-Prefix
     * @return {@code true}, wenn nur root sie neu vergeben darf
     */
    public static boolean isPrivileged(String roleName) {
        String normalized = normalize(roleName);
        if (normalized.isEmpty()) {
            return false;
        }
        return NUR_ROOT.contains(normalized) || normalized.startsWith(QUERZUGRIFF_PREFIX);
    }

    /**
     * Die Meldung, mit der eine abgelehnte Vergabe begruendet wird.
     *
     * @param roleName die abgelehnte Rolle
     * @return Klartext fuer die Oberflaeche
     */
    public static String rejectionMessage(String roleName) {
        return "Nur ROOT darf die Rolle '" + roleName + "' vergeben. "
                + "Modul-Rollen (Zugang zu einem Fachmodul) darf ADMIN vergeben.";
    }

    /**
     * Normalisierte Form eines Rollennamens: getrimmt, klein, ohne {@code ROLE_}-Prefix.
     *
     * @param roleName roher Rollenname, darf {@code null} sein
     * @return normalisierter Name, nie {@code null}
     */
    private static String normalize(String roleName) {
        if (roleName == null) {
            return "";
        }
        String value = roleName.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("role_")) {
            value = value.substring("role_".length());
        }
        return value;
    }
}
