/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Card 664: makes {@code revoke_api_token} effective in those places too where
 * {@code plaintext.mcp.validation=JWT} applies (app, guild, schuetu).
 *
 * <p><b>The problem.</b> In JWT mode {@link McpBearerTokenFilter} only checks signature and expiry.
 * A revoked token therefore kept working — for up to a year, until the JWT expired.
 * The tool for decommissioning a token reported success and did nothing.</p>
 *
 * <p><b>Why this must not simply be {@code validation=DATABASE}.</b> That strategy rejects
 * every token that has no row in {@code api_token} — and exactly such tokens exist
 * legitimately: the time-tracking clock, the schuetu Juriwagen and {@code minten} create them
 * directly via {@link JwtTokenService}. Switching over would take those accesses off the air
 * (card 305).</p>
 *
 * <p><b>The difference lies in the unknown token.</b> This checker only blocks on a
 * <em>positively found, revoked</em> entry. An unknown jti counts as not revoked
 * and passes through unchanged:</p>
 *
 * <pre>
 * validation=DATABASE   jti not in api_token  -&gt;  rejected       (locks out clock/Juriwagen/minten)
 * this checker          jti not in api_token  -&gt;  passed through (no entry = not revoked)
 * </pre>
 *
 * <p><b>Limitation.</b> Tokens that were issued before card 664 have no {@code jti} in their
 * row — they stay non-revocable until they expire. The jti is only contained in the issued token
 * itself, which only the owner has; the database cannot learn it after the fact. Such
 * tokens have to be reissued once.</p>
 *
 * <p><b>Not a {@code @Component}, but a {@code @Bean} with {@code @ConditionalOnMissingBean}</b>
 * ({@code ch.plaintext.apitoken.config.JtiRevocationAutoConfiguration}): with
 * {@code RevokedTokenService}, plaintext-schuetu already ships its own implementation. Two beans of
 * the same interface would have made {@code ObjectProvider.getIfAvailable()} in
 * {@link McpBearerTokenFilterConfig} throw a {@code NoUniqueBeanDefinitionException} —
 * that is, a startup failure in schuetu, triggered by a patch in root.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class ApiTokenJtiRevocationChecker implements JtiRevocationChecker {

    /**
     * How long a "not revoked" holds before it is looked up again.
     *
     * <p>The lookup itself is already leak-free ({@link ApiTokenRevocationLookup} goes via
     * JdbcTemplate, not via JPA — card 659). The cache is therefore not a stopgap against
     * hanging connections, but a plain load brake: an MCP session fires many
     * requests with the <em>same</em> token, and the answer almost never changes.</p>
     *
     * <p>The price is that a revocation only takes effect after at most a minute instead of
     * immediately. Measured against "up to a year" — the state before this card — that is the
     * right trade.</p>
     */
    static final long NEGATIV_CACHE_TTL_MS = 60_000L;

    private final ApiTokenRevocationLookup lookup;

    /** jti -&gt; point in time (ms) up to which "not revoked" holds without a fresh lookup. */
    private final Map<String, Long> nichtWiderrufenBis = new ConcurrentHashMap<>();

    public ApiTokenJtiRevocationChecker(ApiTokenRevocationLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }

        long jetzt = System.currentTimeMillis();
        Long gueltigBis = nichtWiderrufenBis.get(jti);
        if (gueltigBis != null && gueltigBis > jetzt) {
            return false;
        }

        boolean widerrufen;
        try {
            widerrufen = lookup.isJtiRevoked(jti);
        } catch (RuntimeException e) {
            // FAIL-OPEN on purpose: on a database outage, fail-closed would cut off EVERY
            // MCP access — the wrong trade for a gap with no known case of abuse. It is
            // moreover the convention the filter already follows: no
            // checker bean = nothing counts as revoked (see McpBearerTokenFilterConfig).
            log.warn("Widerruf-Pruefung fuer jti={} nicht moeglich, lasse durch: {}", jti, e.getMessage());
            return false;
        }

        if (widerrufen) {
            // Positive matches are NOT cached: a revocation is never taken back, and
            // an entry here would only grow without bound.
            nichtWiderrufenBis.remove(jti);
            return true;
        }

        if (nichtWiderrufenBis.size() > MAX_CACHE_EINTRAEGE) {
            // Plain clearing instead of LRU: the cache is a load brake, not a correctness device —
            // a cleared cache costs one lookup per token, nothing more.
            log.debug("jti-Negativ-Cache uebersteigt {} Eintraege, wird geleert", MAX_CACHE_EINTRAEGE);
            nichtWiderrufenBis.clear();
        }
        nichtWiderrufenBis.put(jti, jetzt + NEGATIV_CACHE_TTL_MS);
        return false;
    }

    /**
     * Upper bound against unbounded growth. Made-up jti values never get here — the filter
     * checks the signature first — but every token ever issued leaves an entry behind, and
     * a process runs for a long time.
     */
    static final int MAX_CACHE_EINTRAEGE = 10_000;
}
