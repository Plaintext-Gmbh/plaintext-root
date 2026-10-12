/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailversand;

import ch.plaintext.modules.ModulApi;

/**
 * Lean, cross-module reference to a mailbox account (for selection lists in other z-modules),
 * without exposing the internal mailbox JPA types.
 *
 * @param id           id of the mailbox account ({@code mail_account})
 * @param name         display name of the account
 * @param emailAddress email address of the account
 * @author info@plaintext.ch
 * @since 2026
 */
@ModulApi(art = ModulApi.Art.DTO, stabilitaet = ModulApi.Stabilitaet.STABIL)
public record MailAccountRef(Long id, String name, String emailAddress) {
}
