/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.paperless;

import ch.plaintext.modules.ModulApi;

/**
 * A document downloaded from Paperless, the return type of
 * {@link IPaperlessAblage#herunterladen(long)}.
 *
 * @param dateiname   file name from the Content-Disposition header, never blank (fallback
 *                    {@code paperless-<id>.pdf})
 * @param contentType MIME type as reported by Paperless, fallback {@code application/pdf}
 * @param inhalt      the file bytes
 * @author info@plaintext.ch
 * @since 2026
 */
@ModulApi(art = ModulApi.Art.DTO)
public record PaperlessDokument(String dateiname, String contentType, byte[] inhalt) {
}
