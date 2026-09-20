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
 * <p>{@code REQUEST} <b>and {@code FORWARD}</b>, never {@code ERROR}. The {@code ERROR} pass is
 * the container's own and carries no caller, and a filter answering 403 there would turn every
 * 404 into a 403 (card 652) — that reasoning stands. {@code FORWARD} had been left out, and
 * that was the hole; see below.</p>
 *
 * <h2>Why FORWARD is not optional (card 1280, measured 20.09.2026)</h2>
 *
 * <p>Every page of this house is addressed as {@code .html}, and
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} turns that into {@code .xhtml} with a
 * {@code RequestDispatcher.forward()}. It sits at {@code HIGHEST_PRECEDENCE + 30}, that is
 * <b>far ahead</b> of the security chain and of this filter — so on a {@code .html} address it
 * forwards before this filter is ever reached, and the forwarded dispatch did not run it
 * either. The result was that the confinement bit on exactly the addresses nobody types
 * ({@code /plaintext-layout/js/config.js} and the like) and on none of the pages:</p>
 *
 * <ul>
 *   <li>a token session reached {@code /index.html} — and every other page of the
 *       application — with HTTP 200, so a link handed out "just for the watch" <b>was</b> the
 *       owner's session;</li>
 *   <li>{@code /watch-einstellungen.html} among them, the one page that issues and revokes the
 *       link: the link could re-issue itself;</li>
 *   <li>and the per-request revocation check never ran on the watch pages themselves, because
 *       they too are reached as {@code .html}. An already open session therefore survived
 *       "deactivate" until the session timed out — the very promise the filter exists for.</li>
 * </ul>
 *
 * <p>Found by {@code WatchHandyLinkPlaywrightIT}, which is the first test to drive the real
 * addresses through a browser; every unit test of this filter hands it the path directly and
 * therefore cannot see a dispatch type. {@code OncePerRequestFilter} keeps the filter from
 * running twice when both dispatches reach it.</p>
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
        // FORWARD gehoert dazu, sonst greift die Einsperrung auf keiner einzigen Seite —
        // Begruendung und Messung im Klassenkommentar (Karte 1280). ERROR bleibt bewusst
        // draussen (Karte 652).
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD);
        registration.setOrder(WATCH_TOKEN_FILTER_ORDER);
        return registration;
    }
}
