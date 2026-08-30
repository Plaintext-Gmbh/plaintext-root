/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

/**
 * Optional collaborator for {@link McpBearerTokenFilter}: checks whether a token has been revoked
 * based on its {@code jti} (JWT ID claim). Root itself does not maintain a blocklist table — apps
 * that want revocation (e.g. schuetu) register their own Spring bean implementing this interface;
 * the filter obtains it optionally via {@code ObjectProvider} (see
 * {@link McpBearerTokenFilterConfig}). If no bean is present, no token counts as revoked —
 * 100% behaviourally identical to the previous filter for apps that do not (yet) run a blocklist.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@FunctionalInterface
public interface JtiRevocationChecker {

    /**
     * @param jti JWT ID claim of the token to be checked (never {@code null} — the filter only
     *            calls this when the token carries a {@code jti} claim)
     * @return {@code true} if the token has been revoked and the request should be rejected with 401
     */
    boolean isRevoked(String jti);
}
