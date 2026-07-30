/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

/**
 * Discovery-Interface für Feature-Module (Task #016, Modul-Verwaltung). Jedes Modul, das in der
 * zentralen Verwaltung („Root | Module") erscheinen soll, stellt eine Spring-Bean bereit, die dieses
 * Interface implementiert — analog zum {@code PlaintextCron}-Discovery (Spring sammelt automatisch
 * {@code List<ModuleDescriptor>} ein, keine zentrale Registry). Ohne Bean taucht ein Modul nicht auf
 * (Opt-in). Der Ein-/Aus-Zustand wird separat (Tabelle {@code module_config}) persistiert.
 */
public interface ModuleDescriptor {

    /** Eindeutige, stabile Modul-Id (z. B. "secrets", "member", "buchhaltung"). */
    String moduleId();

    /** Anzeigename für die UI (Default: {@link #moduleId()}). */
    default String displayName() {
        return moduleId();
    }

    /**
     * Modul-Version — Default aus dem Jar-Manifest ({@code Implementation-Version} der implementierenden
     * Klasse); fehlt es (IDE/Dev-Run), {@code "dev"}. Voraussetzung fürs Manifest:
     * {@code maven-jar-plugin} mit {@code addDefaultImplementationEntries}.
     */
    default String version() {
        Package p = getClass().getPackage();
        String v = p != null ? p.getImplementationVersion() : null;
        return v != null && !v.isBlank() ? v : "dev";
    }

    /**
     * JPA-Entity-Klassen dieses Moduls, die beim Export/Import (Task #016 Phase 2) mitgenommen
     * werden. Default: leer (Opt-in) — Module ohne eigene Daten (z. B. reine UI-Module) müssen
     * nichts überschreiben.
     */
    default java.util.List<Class<?>> entities() {
        return java.util.List.of();
    }
}
