/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

/**
 * Discovery interface for feature modules (Task #016, module management). Every module that should
 * appear in the central management page ("Root | Module") provides a Spring bean implementing this
 * interface — analogous to the {@code PlaintextCron} discovery (Spring collects
 * {@code List<ModuleDescriptor>} automatically, no central registry). Without such a bean a module
 * does not show up (opt-in). The on/off state is persisted separately (table {@code module_config}).
 */
public interface ModuleDescriptor {

    /** Unique, stable module id (e.g. "secrets", "member", "buchhaltung"). */
    String moduleId();

    /** Display name for the UI (default: {@link #moduleId()}). */
    default String displayName() {
        return moduleId();
    }

    /**
     * Module version — by default from the jar manifest ({@code Implementation-Version} of the
     * implementing class); if it is missing (IDE/dev run), {@code "dev"}. Prerequisite for the
     * manifest: {@code maven-jar-plugin} with {@code addDefaultImplementationEntries}.
     */
    default String version() {
        Package p = getClass().getPackage();
        String v = p != null ? p.getImplementationVersion() : null;
        return v != null && !v.isBlank() ? v : "dev";
    }

    /**
     * JPA entity classes of this module that are included in export/import (Task #016 phase 2).
     * Default: empty (opt-in) — modules without data of their own (e.g. pure UI modules) do not
     * need to override anything.
     */
    default java.util.List<Class<?>> entities() {
        return java.util.List.of();
    }
}
