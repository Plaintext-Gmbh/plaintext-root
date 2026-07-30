/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registriert den {@link ClaudeTokenRequestFilter} eng begrenzt auf die
 * Claude-Automation-Endpoints ({@code /nosec/api/claude/*}) — bewusst NICHT breiter,
 * damit kein anderer Request den Parameter-Wrapper abbekommt.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class ClaudeTokenFilterConfig {

    @Bean
    FilterRegistrationBean<ClaudeTokenRequestFilter> claudeTokenRequestFilterRegistration() {
        FilterRegistrationBean<ClaudeTokenRequestFilter> registration =
                new FilterRegistrationBean<>(new ClaudeTokenRequestFilter());
        registration.addUrlPatterns("/nosec/api/claude/*");
        registration.setOrder(10);
        log.info("ClaudeTokenRequestFilter registriert für /nosec/api/claude/* "
                + "(Header-Auth mit URL-Token-Fallback, Übergangsphase)");
        return registration;
    }
}
