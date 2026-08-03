/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

/**
 * Interface for security/role checking.
 * Implement this interface to provide custom security logic for menu items.
 */
public interface SecurityProvider {

    /**
     * Check if the current user has the specified role
     * @param role the role to check
     * @return true if the user has the role, false otherwise
     */
    boolean hasRole(String role);

    /**
     * Check if security is enabled
     * @return true if security is enabled, false otherwise
     */
    default boolean isSecurityEnabled() {
        return true;
    }

    /**
     * Check whether the current user holds any role starting with the given prefix.
     * <p>
     * Needed for {@code roleStartsWith} on {@code MenuAnnotation}, where the role family is not
     * known at compile time — a team membership like {@code MEMBER_<team>}, for instance. The
     * default answers {@code false}, so an implementation that does not override it simply never
     * matches a prefix rule.
     *
     * @param prefix the role name prefix to look for
     * @return true if the user holds at least one matching role
     * @since 1.480.0
     */
    default boolean hasAnyRoleStartingWith(String prefix) {
        return false;
    }
}
