/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Meldet den {@link MaintenanceModeFilter} an.
 *
 * <h2>REQUEST <b>und</b> FORWARD (Karte 1290, gemessen am 20.09.2026)</h2>
 *
 * <p>Der Filter war nur fuer {@code DispatcherType.REQUEST} angemeldet und sitzt mit
 * {@code LOWEST_PRECEDENCE - 100} weit <b>hinter</b> dem
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} ({@code HIGHEST_PRECEDENCE + 30}). Jede Seite
 * dieses Hauses wird als {@code .html} adressiert und erreicht ihre Sicht ueber den
 * {@code RequestDispatcher.forward()} jenes Filters — der REQUEST-Durchgang endet dort, bevor
 * dieser Filter erreicht wird, und den FORWARD-Durchgang sah er nicht.</p>
 *
 * <p>Gemessen an einem laufenden Tomcat ({@code FilterDispatcherInventarTest}, 20.09.2026):
 * auf {@code GET /login.html} lief der Filter nicht mit, auf einer Adresse ohne {@code .html}
 * schon. Der eingeschaltete Wartungsmodus haette damit <b>keine einzige Seite</b> der Anwendung
 * gesperrt — nur REST-Endpunkte, statische Dateien und Adressen ohne Endung. Derselbe
 * Mechanismus wie bei der Zugangsbeschraenkung aus Karte 1280.</p>
 *
 * <p>{@code ERROR} bleibt bewusst draussen (Karte 652): der Fehlerdurchgang ist der des
 * Containers; eine Antwort dort verwandelt jede 404 in eine 503.</p>
 */
@Configuration
public class MaintenanceModeConfig {

    /** Ganz am Ende der Kette — damit ist der {@code SecurityContextHolder} gefuellt. */
    public static final int MAINTENANCE_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Bean
    public MaintenanceModeFilter maintenanceModeFilter(MaintenanceModeProperties properties) {
        return new MaintenanceModeFilter(properties);
    }

    @Bean
    public FilterRegistrationBean<MaintenanceModeFilter> maintenanceModeFilterRegistration(
            MaintenanceModeFilter filter) {
        FilterRegistrationBean<MaintenanceModeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(MAINTENANCE_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        // Karte 1290: ohne FORWARD sperrt der Wartungsmodus keine einzige .html-Seite.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD);
        return registration;
    }
}
