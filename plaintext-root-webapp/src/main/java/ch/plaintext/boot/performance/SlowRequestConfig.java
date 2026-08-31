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
 * Registers the {@link SlowRequestFilter} (card 430).
 *
 * <p>Very early in the chain ({@code HIGHEST_PRECEDENCE + 9}), so that the measured duration covers
 * the <i>whole</i> processing — security, rate limit and rendering included. If the
 * filter sat further back, it would only measure the rest and report durations that are too short;
 * exactly that would be worthless for the question "why does the click take so long".
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
        // Card 497: +9, not +10 — the ForwardedHeaderFilter sits on +10
        // (RateLimitFilterConfig.FORWARDED_HEADER_FILTER_ORDER, card 303).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 9);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
