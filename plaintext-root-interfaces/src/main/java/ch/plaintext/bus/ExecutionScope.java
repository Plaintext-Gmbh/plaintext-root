/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

/**
 * Execution/delivery scope for {@link PlaintextBusEvent}/{@link PlaintextBusSubscriber} — the same
 * gradation that {@code PlaintextCron} already knows implicitly (via {@code isGlobal()}), here made
 * explicit as an enum and extended by {@code PERSOENLICH}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public enum ExecutionScope {

    /** Tenant-less, system-wide (analogous to {@code PlaintextCron.isGlobal() == true}). */
    APPLICATION,

    /** Belongs to a tenant (analogous to {@code PlaintextCron.isGlobal() == false}). */
    MANDAT,

    /** Belongs to a tenant AND to a specific user within it. */
    PERSOENLICH
}
