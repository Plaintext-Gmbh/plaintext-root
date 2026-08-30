/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of the central {@link McpBearerTokenFilter} (prefix
 * {@code plaintext.mcp.bearer-filter}).
 *
 * <pre>{@code
 * plaintext:
 *   mcp:
 *     bearer-filter:
 *       enabled: true                # Default false — the filter is only registered on demand
 *       validation: DATABASE         # JWT (default, without DB revocation) or DATABASE
 *       url-patterns:                # Default: /mcp/*
 *         - /mcp/*
 *         - /api/turnier/*
 *       order: 1                     # Default 1 (after the security chain, permitAll paths)
 * }</pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
@ConfigurationProperties(prefix = "plaintext.mcp.bearer-filter")
public class McpBearerTokenFilterProperties {

    /** Registers the filter only if explicitly {@code true} (no auto-filter for apps without MCP). */
    private boolean enabled = false;

    /** Servlet URL patterns the filter applies to. Empty => default {@code /mcp/*}. */
    private List<String> urlPatterns = new ArrayList<>(List.of("/mcp/*"));

    /** Validation strategy, see {@link Validation}. */
    private Validation validation = Validation.JWT;

    /** Filter order of the {@code FilterRegistrationBean} (default 1, as in all previous copies). */
    private int order = 1;

    /**
     * Migration opt-out for tokens WITHOUT a {@code scope} claim (card 312, H-7).
     *
     * <p>Up to root 1.424.0 a missing scope counted as {@code ADMIN} — and because token issuance
     * did not assign any scope at all, <b>every</b> API token was effectively a full-access token.
     * Since then the rule is fail-closed: missing/empty claim ⇒ only {@code SCOPE_READ}.</p>
     *
     * <p>An instance whose productive integrations still work with scope-less legacy tokens can use
     * this to restore the old behaviour for a limited time ({@code true}), until the tokens have been
     * re-issued with an explicit scope. Deliberately configurable per instance instead of hard-coded —
     * and deliberately with default {@code false}, so that "doing nothing" is the safe option.</p>
     */
    private boolean legacyScopeAdmin = false;

    /** Validation strategies of the filter. */
    public enum Validation {
        /**
         * JWT signature/expiry only ({@link JwtTokenService}), NO DB access. Historical
         * workaround for the (meanwhile fixed) Hikari leak; revocation only takes effect at expiry.
         */
        JWT,
        /**
         * Full validation including a DB revocation check ({@link IApiTokenService}),
         * leak-free since root ≥ 1.246.0 — recommended.
         */
        DATABASE
    }
}
