/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailversand;

import ch.plaintext.modules.ModulApi;

/**
 * A file attachment for cross-module mail sending via {@link MailSender}, without exposing the internal
 * mailbox types (counterpart to {@link MailAccountRef}).
 *
 * @param filename    file name as the recipient sees it (e.g. {@code Ticket_A1B2C3.pdf})
 * @param contentType MIME type (e.g. {@code application/pdf}); {@code null}/blank ⇒ {@code application/octet-stream}
 * @param data        content of the file
 * @author info@plaintext.ch
 * @since 2026
 */
@ModulApi(art = ModulApi.Art.DTO, stabilitaet = ModulApi.Stabilitaet.STABIL)
public record MailAttachment(String filename, String contentType, byte[] data) {
}
