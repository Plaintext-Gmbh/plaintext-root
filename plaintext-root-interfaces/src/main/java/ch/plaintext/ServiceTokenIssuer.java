/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.time.Duration;

/**
 * Issues <b>machine credentials</b>: short-lived, signed tokens with which this instance
 * identifies itself to a foreign counterpart (Card 635).
 *
 * <p>The interface lives in {@code plaintext-root-interfaces} so that application modules can
 * request a credential without depending on {@code plaintext-admin-apitoken} — the implementation
 * ({@code JwtTokenService}) holds the private signing key, and that has no business being in a
 * business module. It is consumed optionally like the other contracts, via
 * {@link org.springframework.beans.factory.ObjectProvider}: a module that gets by without a
 * credential should also start without one.
 *
 * <p><b>What it is for at all.</b> The usual arrangement between two services is a shared secret:
 * whoever knows the value counts as authorized. That comes at two costs — the value has to be
 * maintained, distributed and rotated, and whoever <em>forgets</em> it is locked out. That is
 * exactly what happens on every deploy: the label printer from Card 556 holds an exclusive
 * session, guild loses the token for it on restart, and the only way out would be to restart the
 * device. With a credential the service simply signs a new one after the restart.
 *
 * <p><b>How the counterpart verifies it.</b> Via this instance's public key, which it can fetch
 * for itself: {@code /.well-known/jwks.json} (RFC 7517). The private key never leaves the
 * application.
 *
 * <p><b>What a credential is NOT.</b> It is not an API token. The implementation marks it in such
 * a way that this instance's own token validation rejects it — otherwise a value travelling over
 * the wire as an HTTP header would be a fully privileged entry into our own API.
 */
public interface ServiceTokenIssuer {

    /**
     * Signs a credential.
     *
     * @param subject     who is identifying themselves, e.g. {@code guild-checkin-desk}
     *                    (mandatory) — it lets the counterpart recognize the same caller across a
     *                    restart
     * @param audience    who the credential is valid for, e.g. {@code guild42-label-printer};
     *                    leaving it empty omits the {@code aud} claim, which makes the credential
     *                    valid everywhere the key is known — so set it
     * @param gueltigkeit lifetime from now; the implementation caps it at both ends
     * @return signed JWT (RS256)
     * @throws IllegalArgumentException if {@code subject} is missing
     * @throws IllegalStateException    if the signing keys are not loaded yet (startup may still
     *                                  be waiting for the vault) — <b>not</b> a permanent error, a
     *                                  later attempt can succeed
     */
    String signServiceToken(String subject, String audience, Duration gueltigkeit);
}
