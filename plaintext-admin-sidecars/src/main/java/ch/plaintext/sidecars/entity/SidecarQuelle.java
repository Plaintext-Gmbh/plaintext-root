/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.entity;

/** Woher ein Sidecar bekannt ist (Karte 1400). */
public enum SidecarQuelle {
    /** aus {@code plaintext.sidecars}; wird beim Start abgeglichen, nicht von Hand entfernbar */
    KONFIG,
    /** unter Root → Sidecars bzw. per MCP von Hand ergänzt */
    HAND
}
