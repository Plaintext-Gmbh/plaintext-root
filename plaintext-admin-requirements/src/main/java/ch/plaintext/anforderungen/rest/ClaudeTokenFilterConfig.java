/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ClaudeTokenRequestFilter} narrowly scoped to the
 * Claude automation endpoints ({@code /nosec/api/claude/*}) — deliberately NOT wider,
 * so that no other request gets the parameter wrapper applied to it.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class ClaudeTokenFilterConfig {

    @Bean
    FilterRegistrationBean<ClaudeTokenRequestFilter> claudeTokenRequestFilterRegistration(
            @Value("${plaintext.claude.url-token-fallback:false}") boolean urlTokenFallback) {
        FilterRegistrationBean<ClaudeTokenRequestFilter> registration =
                new FilterRegistrationBean<>(new ClaudeTokenRequestFilter(urlTokenFallback));
        registration.addUrlPatterns("/nosec/api/claude/*");
        registration.setOrder(10);
        log.info("ClaudeTokenRequestFilter registriert für /nosec/api/claude/* (Header-Auth; "
                + "URL-Token {})", urlTokenFallback ? "UEBERGANGSWEISE erlaubt" : "abgelehnt");
        return registration;
    }
}
