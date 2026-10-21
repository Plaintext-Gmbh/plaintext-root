/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.linktoken;

import ch.plaintext.modules.ModulApi;

import java.time.LocalDate;

/**
 * What {@link LinkTokenStore#anlegen} needs for a new link (card 1559). The calling module checks beforehand that
 * the object exists and that the user may share it.
 *
 * @param art        module key, {@code [a-z0-9-]}, e.g. {@code files}
 * @param objekt     key of the object within the art
 * @param recht      {@code null} = {@link LinkRecht#LESEN}
 * @param besitzer   creating user, may be {@code null}
 * @param zweck      free text, may be {@code null}
 * @param gueltigBis last valid day, {@code null} = unlimited
 */
@ModulApi(art = ModulApi.Art.DTO)
public record LinkTokenAntrag(String art, String objekt, LinkRecht recht, String besitzer, String zweck,
                              LocalDate gueltigBis) {
}
