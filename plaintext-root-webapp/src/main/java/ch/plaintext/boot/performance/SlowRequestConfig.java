/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registriert den {@link SlowRequestFilter} (Karte 430).
 *
 * <p>Sehr frueh in der Kette ({@code HIGHEST_PRECEDENCE + 9}), damit die gemessene Dauer die
 * <i>ganze</i> Verarbeitung umfasst — Security, Rate-Limit und Rendering eingeschlossen. Sitzt der
 * Filter weiter hinten, misst er nur den Rest und meldet zu kurze Zeiten; genau das waere fuer die
 * Frage „warum dauert der Klick so lang" wertlos.
 *
 * @author plaintext.ch
 */
@Configuration
@EnableConfigurationProperties(SlowRequestProperties.class)
public class SlowRequestConfig {

    @Bean
    public SlowRequestFilter slowRequestFilter(SlowRequestProperties properties,
                                               PerformanceService performanceService) {
        return new SlowRequestFilter(properties, performanceService);
    }

    @Bean
    public FilterRegistrationBean<SlowRequestFilter> slowRequestFilterRegistration(
            SlowRequestFilter filter) {
        FilterRegistrationBean<SlowRequestFilter> registration = new FilterRegistrationBean<>(filter);
        // Karte 497: +9, nicht +10 — auf +10 sitzt der ForwardedHeaderFilter
        // (RateLimitFilterConfig.FORWARDED_HEADER_FILTER_ORDER, Karte 303).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 9);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
