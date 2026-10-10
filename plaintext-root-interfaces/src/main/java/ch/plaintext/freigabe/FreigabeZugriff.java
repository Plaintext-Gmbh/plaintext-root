/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import ch.plaintext.modules.ModulApi;

/**
 * What root tells a {@link FreigabeQuelle} about an opened share link (card 1476). It is everything the
 * anonymous caller has: the object, its tenant, the part and the right of the link.
 *
 * @param linkId   id of the link, for logging
 * @param mandat   tenant the link was created in; look the object up in this tenant only
 * @param typ      {@link FreigabeQuelle#typ()}
 * @param objektId id of the object inside the module
 * @param teil     part of the object (e.g. one page of a diagram), or {@code null} for the whole object
 * @param recht    right of the link
 */
@ModulApi(art = ModulApi.Art.DTO)
public record FreigabeZugriff(Long linkId, String mandat, String typ, Long objektId, String teil, FreigabeRecht recht) {
}
