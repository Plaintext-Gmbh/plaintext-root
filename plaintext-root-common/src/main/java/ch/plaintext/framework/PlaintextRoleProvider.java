/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A module's contribution of roles — the counterpart to the menu pattern ({@code @MenuAnnotation}
 * / submenu beans) with which modules contribute menu entries: a module (in root itself or in an
 * app that uses root) implements this interface as a Spring bean and thereby declares the roles
 * it knows/checks. The {@link PlaintextRoleRegistry} collects all provider beans (union,
 * deduplicated) and offers the roles to the user administration, for instance, as a selection.
 *
 * <p>Reference implementation: {@code RootRoleProvider} in the webapp module declares root's own
 * roles ({@code root}, {@code admin}, {@code user}, {@code system}).</p>
 *
 * @author info@plaintext.ch
 * @since 0.0.1
 */
public interface PlaintextRoleProvider {

    /**
     * The technical role names of this module.
     *
     * @return role names (with or without the {@code ROLE_} prefix)
     */
    Set<String> getRoles();

    /**
     * The roles of this module including a description for selection UIs.
     *
     * <p>Default: derives roles without a description from {@link #getRoles()} — existing
     * providers therefore keep working unchanged. Modules that want to display a description
     * override this method (and keep {@link #getRoles()} consistent with it).</p>
     *
     * @return declared roles with description
     * @since 1.600.0
     */
    default Set<PlaintextRole> getDeclaredRoles() {
        Set<PlaintextRole> ret = new LinkedHashSet<>();
        for (String role : getRoles()) {
            if (role != null && !role.trim().isEmpty()) {
                ret.add(PlaintextRole.of(role));
            }
        }
        return ret;
    }

}
