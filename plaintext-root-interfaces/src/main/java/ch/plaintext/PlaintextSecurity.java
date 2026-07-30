/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import org.springframework.security.core.Authentication;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interface providing security context information for the current user.
 * Gives access to the current mandate, user identity, roles, and
 * impersonation capabilities.
 *
 * @author mad
 * @since 15.11.2025
 */
public interface PlaintextSecurity {

    /**
     * Gets the current mandate/tenant identifier for the logged-in user.
     *
     * @return the current mandate identifier
     */
    String getMandat();

    /**
     * Gets all mandates the current user has access to.
     *
     * @return set of all mandate identifiers
     */
    Set<String> getAllMandate();

    /**
     * Gets the database ID of the current user.
     *
     * @return the user ID
     */
    Long getId();

    /**
     * Gets the username (login name) of the current user.
     *
     * @return the username
     */
    String getUser();

    /**
     * Gets the Spring Security authentication object for the current user.
     *
     * @return the current authentication
     */
    Authentication getAuthentication();
    /**
     * Gets the mandat for a specific user by their ID
     * @param userId User ID
     * @return The mandat string for the user, or null if user not found
     */
    String getMandatForUser(long userId);

    /**
     * Checks if the current user has been granted the specified role.
     *
     * @param role the role name to check
     * @return true if the user has the role, false otherwise
     */
    boolean ifGranted(String role);

    /**
     * Gets all usernames for a specific mandat
     * @param mandat The mandat to filter by
     * @return List of usernames belonging to the specified mandat
     */
    List<String> getUsersForMandat(String mandat);

    /**
     * Gets the startpage for the current user with fallback to index.html
     * If startpage is null, empty, or "N/A", returns "/index.html?faces-redirect=true"
     * @return The startpage URL with faces-redirect parameter
     */
    String getStartpageOrDefault();

    /**
     * Checks if the current user is impersonating another user
     * @return true if currently impersonating
     */
    boolean isImpersonating();

    /**
     * Starts impersonation of another user
     * @param userId The ID of the user to impersonate
     */
    void startImpersonation(Long userId);

    /**
     * Stops impersonation and returns to original user
     */
    void stopImpersonation();

    /**
     * Gets the original user ID before impersonation
     * @return Original user ID, or null if not impersonating
     */
    Long getOriginalUserId();

    /**
     * Liefert alle Mandate, zwischen denen der aktuelle Benutzer wechseln darf.
     * <p>Default-Implementierung: nur der aktuelle Mandant. Die echte Implementierung
     * liefert für ROOT alle Mandate und sonst {Heimat-Mandant} ∪ zugeordnete Zusatz-Mandate.
     *
     * @return Menge erlaubter Mandanten (kleingeschrieben)
     */
    default Set<String> getAllowedMandate() {
        Set<String> single = new HashSet<>();
        String m = getMandat();
        if (m != null && !m.isBlank()) {
            single.add(m.toLowerCase());
        }
        return single;
    }

    /**
     * Ob dem aktuellen Benutzer ein Mandanten-Wechsler angezeigt werden soll — nur wenn mehr als
     * ein erlaubter Mandant existiert (für ROOT sind das alle Mandate der Instanz, sonst
     * Heimat-Mandant plus zugeordnete Zusatz-Mandate). Bei genau einem erlaubten Mandanten gibt es
     * nichts auszuwählen, der Wechsler bleibt dann ausgeblendet statt nur deaktiviert.
     * <p>Default-Implementierung: {@code false}.
     *
     * @return true, wenn ein Wechsel möglich ist
     */
    default boolean isCanSwitchMandat() {
        return false;
    }

    /**
     * Wechselt den aktiven Mandanten NUR für die laufende Session (ohne DB-Persistierung),
     * sofern er in {@link #getAllowedMandate()} enthalten ist.
     * <p>Default-Implementierung: keine Aktion.
     *
     * @param mandat Ziel-Mandant
     */
    default void switchActiveMandat(String mandat) {
        // Default: keine Aktion – nur die echte Implementierung wechselt den Mandanten.
    }

    /**
     * Liefert alle Benutzernamen, die Zugriff auf den Mandanten haben — als Heimat-Mandant
     * ODER als zugeordneten (aktiven) Zusatz-Mandant.
     * <p>Default-Implementierung: nur die Benutzer des Heimat-Mandanten
     * ({@link #getUsersForMandat(String)}).
     *
     * @param mandat Mandant
     * @return Benutzernamen mit Zugriff auf den Mandanten
     */
    default List<String> getUsernamesWithMandatAccess(String mandat) {
        return getUsersForMandat(mandat);
    }

}