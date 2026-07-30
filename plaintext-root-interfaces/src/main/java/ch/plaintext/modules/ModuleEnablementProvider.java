/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

/**
 * Interface für die Ein-/Aus-Abfrage eines Feature-Moduls (Task #016 Phase 2, Menü-Auto-
 * Ausblendung). Liegt in {@code root-interfaces}, damit {@code plaintext-root-menu} das
 * Deaktivieren eines Moduls abfragen kann, ohne einen (verbotenen, rückwärtigen) Dependency auf
 * {@code plaintext-admin-modules} zu benötigen — analog zu {@link ch.plaintext.MenuVisibilityProvider}
 * wird die Implementierung (in admin-modules) zur Laufzeit lazy über den {@code BeanFactory} geholt.
 */
public interface ModuleEnablementProvider {

    /** Ein-/Aus-Zustand eines Moduls (Default: aktiviert, wenn kein Eintrag vorhanden ist). */
    boolean isEnabled(String moduleId);
}
