/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

/**
 * Slim, cross-module reference to a GLOBAL system mail account of the mailbox – for the selection
 * in the root configuration (setup), without exposing the internal mailbox types.
 *
 * @param id           id of the mailbox account
 * @param name         display name
 * @param emailAddress sender address
 */
public record SystemMailAccount(Long id, String name, String emailAddress) {
}
