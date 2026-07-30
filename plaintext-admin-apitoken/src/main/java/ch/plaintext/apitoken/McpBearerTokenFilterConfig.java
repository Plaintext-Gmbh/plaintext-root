/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.McpUserRoles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registriert den zentralen {@link McpBearerTokenFilter} property-gesteuert (Opt-in via
 * {@code plaintext.mcp.bearer-filter.enabled=true}, siehe {@link McpBearerTokenFilterProperties}).
 *
 * <p>Opt-in deshalb, weil dieses Modul in ALLEN Plaintext-Apps auf dem Classpath liegt — Apps ohne
 * MCP-Endpoint (z.B. plaintext-root selbst) sollen keinen zusätzlichen Filter registriert bekommen.
 * Der Bean-Name {@code mcpBearerTokenFilterRegistration} entspricht dem der bisherigen
 * Consumer-Kopien, damit die Umstellung ein reiner Lösch-Patch ist.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "plaintext.mcp.bearer-filter", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpBearerTokenFilterProperties.class)
public class McpBearerTokenFilterConfig {

    @Bean
    FilterRegistrationBean<McpBearerTokenFilter> mcpBearerTokenFilterRegistration(
            McpBearerTokenFilterProperties properties,
            JwtTokenService jwtTokenService,
            IApiTokenService apiTokenService,
            McpUserRoles mcpUserRoles,
            ObjectProvider<JtiRevocationChecker> revocationCheckerProvider) {

        // Optional: nur vorhanden, wenn die App (z.B. schuetu) eine eigene Blocklist-Bean registriert.
        // Ohne Bean = kein Token gilt als revoked, 100% verhaltensgleich zum bisherigen Filter.
        // getIfAvailable() statt getIfAvailable(Supplier) verwendet, damit ein simples
        // mock(ObjectProvider.class) in Tests (ohne Stubbing des default-Methoden-Overloads) sauber
        // auf null zurückfällt, statt sich auf Mockitos Default-Methoden-Handling zu verlassen.
        JtiRevocationChecker revocationChecker = revocationCheckerProvider.getIfAvailable();
        if (revocationChecker == null) {
            revocationChecker = jti -> false;
        }

        McpBearerTokenFilter filter = switch (properties.getValidation()) {
            case JWT -> McpBearerTokenFilter.jwtOnly(jwtTokenService, mcpUserRoles, revocationChecker);
            case DATABASE -> McpBearerTokenFilter.withRevocationCheck(apiTokenService, mcpUserRoles, revocationChecker);
        };

        // Alt-Verhalten fuer scope-lose Tokens (Karte 312): Default false = fail-closed auf READ.
        filter.setLegacyScopeAdmin(properties.isLegacyScopeAdmin());

        // Leere Pattern-Liste NIE an die Registration durchreichen: FilterRegistrationBean ohne
        // Patterns mappt auf /* und würde die GANZE App hinter Bearer-Auth legen.
        List<String> patterns = properties.getUrlPatterns();
        if (patterns == null || patterns.isEmpty()) {
            patterns = List.of("/mcp/*");
        }

        FilterRegistrationBean<McpBearerTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(patterns.toArray(String[]::new));
        registration.setOrder(properties.getOrder());
        log.info("Zentraler McpBearerTokenFilter registriert: validation={}, patterns={}, order={}",
                properties.getValidation(), patterns, properties.getOrder());
        if (properties.isLegacyScopeAdmin()) {
            log.warn("MCP: legacy-scope-admin=true — Tokens OHNE scope-Claim gelten weiterhin als ADMIN "
                    + "(Uebergangsmodus, Karte 312). Tokens mit explizitem Scope neu ausstellen und Flag entfernen.");
        }
        return registration;
    }
}
