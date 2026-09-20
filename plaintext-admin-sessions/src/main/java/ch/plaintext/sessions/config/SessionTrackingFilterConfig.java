/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Meldet den {@link SessionTrackingFilter} an — mit {@code REQUEST} <b>und</b> {@code FORWARD}.
 *
 * <h2>Warum es diese Klasse gibt (Karte 1290, gemessen am 20.09.2026)</h2>
 *
 * <p>Der Filter war bis dahin allein ueber {@code @Component} und {@code @Order(100)} angemeldet.
 * Spring Boot leitet die Dispatcher-Typen einer solchen Bohne ab
 * ({@code AbstractFilterRegistrationBean#determineDispatcherTypes}): fuer einen
 * {@code OncePerRequestFilter} {@code EnumSet.allOf(...)}, fuer einen einfachen
 * {@code Filter} — und das ist dieser hier — nur {@code EnumSet.of(REQUEST)}.</p>
 *
 * <p>Mit {@code order = 100} sitzt er weit <b>hinter</b> dem
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} ({@code HIGHEST_PRECEDENCE + 30}). Jede Seite
 * wird als {@code .html} adressiert und erreicht ihre Sicht ueber dessen
 * {@code RequestDispatcher.forward()}; der REQUEST-Durchgang endet dort. Ein REQUEST-only
 * angemeldeter Filter dahinter laeuft damit auf <b>keiner einzigen Seite</b> — derselbe
 * Mechanismus, der in Karte 1280 eine Zugangsbeschraenkung wirkungslos machte.</p>
 *
 * <p>Folge fuer diesen Filter: Sitzungen wurden nur noch bei Anfragen <b>ohne</b> {@code .html}
 * erfasst (statische Dateien, REST). Das Seitenbild im Sitzungsprotokoll fehlte, und der
 * {@link ch.plaintext.sessions.service.HttpSessionRegistry} — die Grundlage fuer das
 * zwangsweise Beenden einer Sitzung — bekam eine Sitzung nur zufaellig zu sehen.</p>
 *
 * <p>{@code ERROR} bleibt bewusst draussen (Karte 652): der Fehlerdurchgang ist der des
 * Containers und traegt keinen Anrufer. Eine Doppelzaehlung, wenn REQUEST <i>und</i> FORWARD
 * derselben Anfrage hier ankommen, verhindert
 * {@code SessionTrackingFilter.ATTRIBUT_GEZAEHLT}.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Configuration
public class SessionTrackingFilterConfig {

    /**
     * Wie bisher {@code 100} — hinter der Sicherheitskette ({@code -100}), damit der
     * {@code SecurityContextHolder} gefuellt ist, wenn der Filter die Anmeldung liest.
     */
    public static final int SESSION_TRACKING_FILTER_ORDER = 100;

    @Bean
    public FilterRegistrationBean<SessionTrackingFilter> sessionTrackingFilterRegistration(
            SessionTrackingFilter filter) {
        FilterRegistrationBean<SessionTrackingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        // Karte 1290: ohne FORWARD sieht der Filter keine einzige .html-Seite.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD);
        registration.setOrder(SESSION_TRACKING_FILTER_ORDER);
        return registration;
    }
}
