/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

/**
 * Interface for querying the on/off state of a feature module (Task #016 phase 2, automatic menu
 * hiding). It lives in {@code root-interfaces} so that {@code plaintext-root-menu} can ask whether
 * a module is disabled without needing a (forbidden, backwards) dependency on
 * {@code plaintext-admin-modules} — analogous to {@link ch.plaintext.MenuVisibilityProvider}, the
 * implementation (in admin-modules) is fetched lazily at runtime through the {@code BeanFactory}.
 */
public interface ModuleEnablementProvider {

    /** On/off state of a module (default: enabled when no entry exists). */
    boolean isEnabled(String moduleId);
}
