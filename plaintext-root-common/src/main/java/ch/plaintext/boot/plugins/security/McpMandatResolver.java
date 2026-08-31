/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.PlaintextSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Central resolution of the tenant for MCP tools (and other token-based accesses).
 *
 * <p>Rule (uses {@link PlaintextSecurity#getAllowedMandate()} — that already encodes "ROOT may
 * use all tenants, everybody else their home tenant plus the assigned additional tenants"):</p>
 * <ul>
 *   <li>No mandat given → the token's current tenant ({@link PlaintextSecurity#getMandat()}).</li>
 *   <li>The given mandat is allowed → that tenant.</li>
 *   <li>Otherwise → {@link SecurityException} (cross-tenant denied).</li>
 * </ul>
 *
 * <p>Precondition: the security context contains the real roles of the token user (the MCP
 * bearer token filter loads them), so that {@code getAllowedMandate()} returns all tenants for ROOT.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpMandatResolver {

    private final PlaintextSecurity security;

    /**
     * Resolves the effective tenant.
     *
     * @param requestedMandat optional mandat parameter (null/empty = current tenant)
     * @return the tenant to use
     * @throws SecurityException when the requested tenant is not allowed
     */
    public String resolve(String requestedMandat) {
        if (requestedMandat == null || requestedMandat.isBlank()) {
            return security.getMandat();
        }
        String req = requestedMandat.trim();
        Set<String> allowed = security.getAllowedMandate(); // lowercased
        if (allowed.contains(req.toLowerCase())) {
            return req;
        }
        log.warn("MCP: Mandat '{}' nicht erlaubt (User {} / erlaubt: {})", req, security.getUser(), allowed);
        throw new SecurityException("Mandant '" + req + "' ist für diesen Token nicht erlaubt");
    }

    /** @return true when the current token may use the requested tenant. */
    public boolean isAllowed(String requestedMandat) {
        if (requestedMandat == null || requestedMandat.isBlank()) {
            return true;
        }
        return security.getAllowedMandate().contains(requestedMandat.trim().toLowerCase());
    }
}
