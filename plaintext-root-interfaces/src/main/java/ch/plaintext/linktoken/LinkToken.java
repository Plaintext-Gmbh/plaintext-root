/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.linktoken;

import ch.plaintext.modules.ModulApi;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A token link from a {@link LinkTokenStore} (card 1559).
 *
 * @param id                stable key for {@link LinkTokenStore#widerrufe}; not secret
 * @param token             the secret of the address; {@code null} if the store keeps only a hash and this is not
 *                          the result of {@link LinkTokenStore#anlegen}
 * @param art               module key, e.g. {@code drawio}
 * @param objekt            key of the shared object within the art, e.g. {@code ordner/plan.drawio}
 * @param recht             read only or read and write
 * @param besitzer          user who created the link, {@code null} if unknown
 * @param mandat            tenant of the link, {@code null} in single-tenant stores
 * @param zweck             free text for the overview, may be empty
 * @param erstellt          time of creation, {@code null} if unknown
 * @param gueltigBis        last valid day; {@code null} = unlimited
 * @param aufrufe           number of calls
 * @param zuletztAufgerufen last call, {@code null} = never
 */
@ModulApi(art = ModulApi.Art.DTO)
public record LinkToken(String id, String token, String art, String objekt, LinkRecht recht, String besitzer,
                        String mandat, String zweck, Instant erstellt, LocalDate gueltigBis, int aufrufe,
                        Instant zuletztAufgerufen) {

    /** @return {@code true} if the last valid day lies before {@code heute} */
    public boolean abgelaufen(LocalDate heute) {
        return gueltigBis != null && heute.isAfter(gueltigBis);
    }

    /** @return {@code true} for a link that may also write */
    public boolean schreiben() {
        return recht == LinkRecht.SCHREIBEN;
    }
}
