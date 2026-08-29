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
 * Konfiguration des zentralen {@link McpBearerTokenFilter} (Prefix
 * {@code plaintext.mcp.bearer-filter}).
 *
 * <pre>{@code
 * plaintext:
 *   mcp:
 *     bearer-filter:
 *       enabled: true                # Default false — Filter wird nur auf Wunsch registriert
 *       validation: DATABASE         # JWT (Default, ohne DB-Revocation) oder DATABASE
 *       url-patterns:                # Default: /mcp/*
 *         - /mcp/*
 *         - /api/turnier/*
 *       order: 1                     # Default 1 (nach der Security-Chain, permitAll-Pfade)
 * }</pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
@ConfigurationProperties(prefix = "plaintext.mcp.bearer-filter")
public class McpBearerTokenFilterProperties {

    /** Registriert den Filter nur, wenn explizit {@code true} (kein Auto-Filter für Apps ohne MCP). */
    private boolean enabled = false;

    /** Servlet-URL-Patterns, auf die der Filter greift. Leer => Default {@code /mcp/*}. */
    private List<String> urlPatterns = new ArrayList<>(List.of("/mcp/*"));

    /** Validierungs-Strategie, siehe {@link Validation}. */
    private Validation validation = Validation.JWT;

    /** Filter-Order der {@code FilterRegistrationBean} (Default 1, wie alle bisherigen Kopien). */
    private int order = 1;

    /**
     * Migrations-Opt-out für Tokens OHNE {@code scope}-Claim (Karte 312, H-7).
     *
     * <p>Bis root 1.424.0 galt ein fehlender Scope als {@code ADMIN} — und weil die Token-Ausstellung
     * gar keinen Scope vergab, war damit <b>jeder</b> API-Token ein Vollzugriffs-Token. Seither gilt
     * fail-closed: fehlender/leerer Claim ⇒ nur {@code SCOPE_READ}.</p>
     *
     * <p>Eine Instanz, deren produktive Integrationen noch mit scope-losen Alt-Tokens arbeiten, kann
     * das alte Verhalten hiermit befristet zurückholen ({@code true}), bis die Tokens mit explizitem
     * Scope neu ausgestellt sind. Bewusst pro Instanz konfigurierbar statt hartkodiert — und bewusst
     * mit Default {@code false}, damit „nichts tun" die sichere Variante ist.</p>
     */
    private boolean legacyScopeAdmin = false;

    /** Validierungs-Strategien des Filters. */
    public enum Validation {
        /**
         * Nur JWT-Signatur/Expiry ({@link JwtTokenService}), KEIN DB-Zugriff. Historischer
         * Workaround für den (inzwischen gefixten) Hikari-Leak; Revocation greift erst mit Expiry.
         */
        JWT,
        /**
         * Vollständige Validierung inkl. DB-Revocation-Check ({@link IApiTokenService}),
         * leak-frei seit root ≥ 1.246.0 — empfohlen.
         */
        DATABASE
    }
}
