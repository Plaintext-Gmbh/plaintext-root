/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.config;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.watch.web.WatchTokenSitzungFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link WatchTokenSitzungFilter} at the one position where it works.
 *
 * <p>{@code springSecurityFilterChain} runs at
 * {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER},
 * which is {@code -100}. A higher order value means later, that is <b>inside</b> the security
 * chain:
 * at {@code -99} this filter sees the {@code SecurityContextHolder} already filled from the
 * session, and Spring Security's own authorization has already run. That is exactly what is
 * wanted — the filter only ever takes away. Registered as a {@code @Component} it would land at
 * {@code LOWEST_PRECEDENCE}, which still works but says nothing about the intent; the explicit
 * order does (same reasoning as {@code RateLimitFilterConfig}, card 303).</p>
 *
 * <p>{@code DispatcherType.REQUEST} only: the {@code ERROR} pass is the container's own and
 * carries no caller, and a filter answering 403 there would turn every 404 into a 403
 * (card 652).</p>
 */
@Configuration
public class WatchTokenFilterConfig {

    /**
     * Order of the {@code springSecurityFilterChain}, as Spring Boot registers it
     * ({@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}, Spring Boot 4).
     *
     * <p>Written out as a number and not referenced: this module deliberately carries no
     * {@code spring-boot-starter-security} — it contributes pages, not a security
     * configuration, and pulling the starter in for one constant would give every consumer a
     * second security auto-configuration to reason about.</p>
     *
     * <p>So that the number cannot drift away from the constant unnoticed,
     * {@code SicherheitsketteOrdnungTest} in plaintext-root-webapp — where the starter IS on
     * the classpath — holds {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER} against this
     * value.</p>
     */
    public static final int SICHERHEITSKETTE_ORDER = -100;

    /** One behind the security chain — that is, inside it. */
    public static final int WATCH_TOKEN_FILTER_ORDER = SICHERHEITSKETTE_ORDER + 1;

    @Bean
    public FilterRegistrationBean<WatchTokenSitzungFilter> watchTokenSitzungFilterRegistration(
            ObjectProvider<IApiTokenService> tokenDienst) {
        FilterRegistrationBean<WatchTokenSitzungFilter> registration =
                new FilterRegistrationBean<>(new WatchTokenSitzungFilter(tokenDienst));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(WATCH_TOKEN_FILTER_ORDER);
        return registration;
    }
}
