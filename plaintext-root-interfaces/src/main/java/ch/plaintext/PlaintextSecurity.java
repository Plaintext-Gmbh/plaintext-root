/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import org.springframework.security.core.Authentication;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
     * Returns the username (login name) for a user id.
     *
     * <p><b>What for (Card 596):</b> background runs have to take the recipient from the
     * <b>record</b>, never from the security context — there {@link #getId()} returns {@code -1}
     * during a cron run (Card 588). The record carries only the id; this method is the bridge.
     *
     * <p><b>This is NOT necessarily a mail address.</b> As a rule the username is also the
     * address, but legacy data also holds plain short handles. Anyone who wants to send mail uses
     * {@link #getEmailForUser(long)}.
     *
     * @param userId user id
     * @return the username, or {@code null} if no user with this id exists
     */
    String getUsernameForUser(long userId);

    /**
     * Returns the <b>deliverable mail address</b> for a user id — or nothing.
     *
     * <p>In this application the username is also the mail address: self-registration sets it that
     * way, the password reset sends to it, and user management enforces the mail form when
     * creating an account. That does not hold for legacy data ({@code plafferma}) or for machine
     * writers ({@code anonymousUser}).
     *
     * <p><b>The {@link Optional} return type is deliberate:</b> a recipient that cannot be
     * resolved is a finding, not a normal case — but it must not make the caller fail. The type
     * forces the case to be handled without throwing an exception.
     *
     * @param userId user id
     * @return the mail address, or {@link Optional#empty()} if the user is unknown or the
     *         username is not an address
     */
    default Optional<String> getEmailForUser(long userId) {
        return PlaintextEmailAddress.asDeliverable(getUsernameForUser(userId));
    }

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
     * Returns all tenants the current user is allowed to switch between.
     * <p>Default implementation: the current tenant only. The real implementation returns all
     * tenants for ROOT, and otherwise {home tenant} ∪ assigned additional tenants.
     *
     * @return set of permitted tenants (lowercase)
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
     * Whether a tenant switcher should be shown to the current user — only when more than one
     * permitted tenant exists (for ROOT that is every tenant of the instance, otherwise the home
     * tenant plus the assigned additional tenants). With exactly one permitted tenant there is
     * nothing to choose, so the switcher stays hidden rather than merely disabled.
     * <p>Default implementation: {@code false}.
     *
     * @return true if switching is possible
     */
    default boolean isCanSwitchMandat() {
        return false;
    }

    /**
     * Switches the active tenant for the running session ONLY (without persisting it to the DB),
     * provided it is contained in {@link #getAllowedMandate()}.
     * <p>Default implementation: no action.
     *
     * @param mandat target tenant
     */
    default void switchActiveMandat(String mandat) {
        // Default: no action – only the real implementation switches the tenant.
    }

    /**
     * Returns all usernames that have access to the tenant — either as their home tenant OR as an
     * assigned (active) additional tenant.
     * <p>Default implementation: only the users of the home tenant
     * ({@link #getUsersForMandat(String)}).
     *
     * @param mandat tenant
     * @return usernames with access to the tenant
     */
    default List<String> getUsernamesWithMandatAccess(String mandat) {
        return getUsersForMandat(mandat);
    }

}