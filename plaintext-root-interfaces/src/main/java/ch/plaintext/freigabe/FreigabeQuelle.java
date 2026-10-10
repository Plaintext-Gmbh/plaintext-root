/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import ch.plaintext.modules.ModulApi;

import java.util.Optional;

/**
 * A module whose objects can be shown (and, with {@code rw}, changed) through share links without a login
 * (card 1476). The module registers one {@code @Component}; root supplies token, hash, expiry, revocation,
 * call counter, the management (tag {@code pt:freigabeLinks}, MCP) and the address {@code /nosec/freigabe/{token}}.
 *
 * <p><b>Rules for the module:</b></p>
 * <ul>
 *   <li>{@link #darfFreigeben} runs as the signed-in user (UI, MCP) and checks role, tenant and visibility of
 *       the object. Only then does root create, list or revoke a link.</li>
 *   <li>{@link #zeige} and {@link #schreibe} run <b>without</b> a login: root clears the SecurityContext for
 *       them. Look the object up in {@link FreigabeZugriff#mandat()}, never through {@code PlaintextSecurity}.</li>
 *   <li>{@link #zeige} changes nothing (link scanners open every link). Only {@link #schreibe} writes, and root
 *       calls it on POST only and only for links with {@link FreigabeRecht#SCHREIBEN}.</li>
 *   <li>A {@link FreigabeZugriff#teil()} limits the response to that part; answer an unknown part with empty (404).</li>
 * </ul>
 */
@ModulApi(art = ModulApi.Art.ERWEITERUNG, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface FreigabeQuelle {

    /** Stable key, {@code [a-z0-9-]}, e.g. {@code drawio}; stored with every link. */
    String typ();

    /** Display name in the management. */
    default String bezeichnung() {
        return typ();
    }

    /** May the signed-in user share this object (and see and revoke its links)? */
    boolean darfFreigeben(Long objektId);

    /** Can the module write through a link? Without it root creates no {@code rw} links. */
    default boolean schreibbar() {
        return false;
    }

    /** GET: render the object (or the part) without changing anything; empty means 404. */
    Optional<FreigabeInhalt> zeige(FreigabeZugriff zugriff);

    /**
     * POST through an {@code rw} link: take over the content; empty means 404.
     *
     * @param contentType content type of the request, may be {@code null}
     * @param inhalt      request body, at most {@code plaintext.freigabe.max-bytes}
     */
    default Optional<FreigabeInhalt> schreibe(FreigabeZugriff zugriff, String contentType, byte[] inhalt) {
        return Optional.empty();
    }
}
