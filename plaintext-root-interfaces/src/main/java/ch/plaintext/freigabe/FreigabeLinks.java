/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import ch.plaintext.modules.ModulApi;

import java.time.LocalDate;

/**
 * Creates and revokes share links from a module's own code (card 1498), e.g. when a page is made public or an
 * event gets a helper link whose expiry the module sets itself. Implemented by {@code plaintext-root-freigabe};
 * inject it as {@code ObjectProvider<FreigabeLinks>} (the implementation in turn looks up the module's
 * {@link FreigabeQuelle}). The same rules apply as in the UI and over MCP: only in the signed-in user's tenant and
 * only when {@link FreigabeQuelle#darfFreigeben} allows it; the token is stored as a hash only.
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface FreigabeLinks {

    /**
     * @param teil        {@code null} or blank = the whole object
     * @param recht       {@code null} = {@link FreigabeRecht#LESEN}
     * @param gueltigTage {@code null} = root default (90), {@code 0} = unlimited, at most 3650
     * @return the address; it is shown only this once
     * @throws java.util.NoSuchElementException if the type is unknown or the user may not share the object
     * @throws IllegalArgumentException         for {@code rw} without a writing module, or values out of range
     */
    String erzeugeAdresse(String typ, Long objektId, String teil, FreigabeRecht recht, Integer gueltigTage, String zweck);

    /**
     * Like {@link #erzeugeAdresse}, but valid up to and including a fixed day (e.g. end of an event + 1 day).
     *
     * @param gueltigBis required, from today up to 3650 days ahead; a day in the past is rejected, never unlimited
     */
    String erzeugeAdresseBis(String typ, Long objektId, String teil, FreigabeRecht recht, LocalDate gueltigBis, String zweck);

    /**
     * Revokes every link on the object that the user may manage; they answer 404 at once.
     *
     * @return number of revoked links
     */
    int widerrufeAlle(String typ, Long objektId);
}
