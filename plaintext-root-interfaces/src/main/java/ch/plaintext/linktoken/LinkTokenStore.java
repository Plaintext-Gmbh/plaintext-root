/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.linktoken;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Optional;

/**
 * Storage of token links without login, shared by Draw.io, slide shows and Files (card 1559).
 *
 * <p>One store for all three, so that there is one token format, one expiry rule and one call counter instead
 * of three. A module keeps its links apart by {@link LinkToken#art()} (e.g. {@code drawio}, {@code diashow},
 * {@code files}) and identifies the shared object by a string key {@link LinkToken#objekt()} (file path, slide
 * show title, file name).</p>
 *
 * <p><b>Default:</b> {@code plaintext-root-freigabe} brings a JPA implementation (table {@code freigabe_link},
 * token stored as SHA-256 only) that steps back as soon as the application defines its own bean
 * ({@code @ConditionalOnMissingBean}). An application with a different store (e.g. a config list in a Git
 * repository) implements this interface and registers it as a bean.</p>
 *
 * <p><b>Rules for an implementation:</b></p>
 * <ul>
 *   <li>{@link #anlegen} creates the token (at least 128 bits from a {@code SecureRandom}, URL-safe) and returns
 *       it once. Whether it can be read again later ({@link LinkToken#token()} in {@link #liste}) is up to the
 *       implementation; the JPA default keeps only the hash and returns {@code null} there.</li>
 *   <li>{@link #finde} never throws and returns only links that are neither revoked nor expired: a link that
 *       does not open in case of doubt is harmless, one that opens in case of doubt is not.</li>
 *   <li>{@link #aufgerufen} may batch the counter (write at most once per minute); {@link #liste} then already
 *       includes the calls not yet written.</li>
 * </ul>
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE, stabilitaet = ModulApi.Stabilitaet.NEU)
public interface LinkTokenStore {

    /**
     * Creates a link and its token.
     *
     * @return the link including {@link LinkToken#token()}; the only moment the caller is guaranteed to see it
     * @throws IllegalArgumentException if art or objekt is empty
     * @throws IllegalStateException    if the store could not be written
     */
    LinkToken anlegen(LinkTokenAntrag antrag);

    /**
     * The valid link for a token of this art.
     *
     * @return empty for an unknown, revoked or expired token and if the store cannot be read; never throws
     */
    Optional<LinkToken> finde(String art, String token);

    /**
     * @param objekt   {@code null} = all objects of the art
     * @param besitzer {@code null} = all owners; otherwise only links created by this user
     * @return the links of the art, newest first, expired ones included (see {@link LinkToken#abgelaufen})
     */
    List<LinkToken> liste(String art, String objekt, String besitzer);

    /**
     * Revokes one link.
     *
     * @param id {@link LinkToken#id()}
     * @return {@code true} if the link existed and is revoked now
     */
    boolean widerrufe(String art, String id);

    /**
     * Revokes all links of an object, e.g. when the object is deleted; otherwise an old link would later open a
     * new object that happens to have the same name.
     *
     * @return number of revoked links
     */
    int widerrufeFuer(String art, String objekt);

    /**
     * Lets the links of a moved or renamed object follow it.
     *
     * @return number of links that now point to {@code nach}
     */
    int zieheUm(String art, String von, String nach);

    /** Counts one call of the link (call counter and time of the last call). Never throws. */
    void aufgerufen(String art, String token);
}
