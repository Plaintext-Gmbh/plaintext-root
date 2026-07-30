/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

/**
 * Ausführungs-/Zustellungs-Scope für {@link PlaintextBusEvent}/{@link PlaintextBusSubscriber} —
 * dieselbe Stufung, die {@code PlaintextCron} (via {@code isGlobal()}) bereits implizit kennt, hier
 * explizit als Enum und um {@code PERSOENLICH} erweitert.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public enum ExecutionScope {

    /** Mandantenlos, systemweit (analog {@code PlaintextCron.isGlobal() == true}). */
    APPLICATION,

    /** Gehört einem Mandanten (analog {@code PlaintextCron.isGlobal() == false}). */
    MANDAT,

    /** Gehört einem Mandanten UND einem konkreten Benutzer darin. */
    PERSOENLICH
}
