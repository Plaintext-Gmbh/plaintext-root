/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
 * Registers the central {@link McpBearerTokenFilter} in a property-driven way (opt-in via
 * {@code plaintext.mcp.bearer-filter.enabled=true}, see {@link McpBearerTokenFilterProperties}).
 *
 * <p>Opt-in because this module is on the classpath of ALL Plaintext apps — apps without an
 * MCP endpoint (e.g. plaintext-root itself) should not get an additional filter registered.
 * The bean name {@code mcpBearerTokenFilterRegistration} matches the one used by the previous
 * consumer copies, so that the migration is a pure deletion patch.</p>
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

        // Optional: only present if the app (e.g. schuetu) registers its own blocklist bean.
        // Without such a bean = no token counts as revoked, 100% behaviourally identical to the previous filter.
        // getIfAvailable() is used instead of getIfAvailable(Supplier) so that a plain
        // mock(ObjectProvider.class) in tests (without stubbing the default-method overload) falls back
        // cleanly to null instead of relying on Mockito's default-method handling.
        JtiRevocationChecker revocationChecker = revocationCheckerProvider.getIfAvailable();
        if (revocationChecker == null) {
            revocationChecker = jti -> false;
        }

        McpBearerTokenFilter filter = switch (properties.getValidation()) {
            case JWT -> McpBearerTokenFilter.jwtOnly(jwtTokenService, mcpUserRoles, revocationChecker);
            case DATABASE -> McpBearerTokenFilter.withRevocationCheck(apiTokenService, mcpUserRoles, revocationChecker);
        };

        // Legacy behaviour for scope-less tokens (card 312): default false = fail-closed to READ.
        filter.setLegacyScopeAdmin(properties.isLegacyScopeAdmin());

        // NEVER pass an empty pattern list on to the registration: a FilterRegistrationBean without
        // patterns maps to /* and would put the WHOLE app behind bearer auth.
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
