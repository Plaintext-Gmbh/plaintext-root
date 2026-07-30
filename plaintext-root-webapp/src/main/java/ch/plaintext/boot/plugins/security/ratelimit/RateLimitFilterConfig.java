/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Registriert den {@link RateLimitFilter} an der richtigen Stelle der Servlet-Filterkette.
 *
 * <p>SECURITY (Karte 303): Der Filter war zuvor nur ein {@code @Component} ohne Order. Spring Boot
 * registriert solche Filter mit {@code Ordered.LOWEST_PRECEDENCE}, die
 * {@code springSecurityFilterChain} laeuft dagegen bei
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}. Damit lief das Rate-Limiting
 * <em>hinter</em> Security und die Limits fuer {@code POST /login} sowie {@code POST /ott/generate}
 * konnten gar nicht greifen.
 *
 * <p>Warum ganz nach vorne und nicht nur knapp vor Security: der Filter muss die <b>echte</b>
 * Peer-Adresse und die <b>ungefilterte</b> {@code X-Forwarded-For}-Kette sehen, um den Bucket-Key
 * spoof-sicher bestimmen zu koennen (siehe {@link ClientIpResolver}). Springs
 * {@code ForwardedHeaderFilter} (aktiviert ueber {@code server.forward-headers-strategy=FRAMEWORK})
 * wird von Spring Boot bei {@code Ordered.HIGHEST_PRECEDENCE} registriert, ersetzt
 * {@code getRemoteAddr()} durch das <em>erste</em> — also vom Client frei waehlbare — Element der
 * XFF-Kette und blendet die {@code X-Forwarded-*}-Header danach aus. Ein Rate-Limiter dahinter
 * koennte prinzipiell nicht spoof-sicher sein.
 *
 * <p>Deshalb wird die von Spring Boot erzeugte {@code forwardedHeaderFilter}-Registrierung hier per
 * {@link BeanPostProcessor} um zehn Positionen nach hinten geschoben. Bewusst nicht per
 * gleichnamiger Ersatz-Bean: das haengt daran, dass die Auto-Configuration-Bean sich zurueckzieht,
 * und der Filter wuerde bei einer kuenftigen Boot-Aenderung im Zweifel doppelt oder gar nicht
 * registriert. Der BeanPostProcessor aendert nur den Order-Wert; Filter-Instanz, Dispatcher-Typen
 * und die {@code server.forward-headers-strategy}-Semantik bleiben unangetastet. Fuer alles hinter
 * dem Rate-Limiter — Security, JSF, URL-Erzeugung — ist das Verhalten damit unveraendert.
 *
 * <p>Resultierende Reihenfolge:
 * <pre>
 *   RateLimitFilter        HIGHEST_PRECEDENCE       (echte Peer-Adresse + rohe XFF-Kette)
 *   ForwardedHeaderFilter  HIGHEST_PRECEDENCE + 10  (wie bisher: Scheme/Host/RemoteAddr fixen)
 *   ...
 *   springSecurityFilterChain  -100
 * </pre>
 */
@Configuration
public class RateLimitFilterConfig {

    public static final int RATE_LIMIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;
    public static final int FORWARDED_HEADER_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(RATE_LIMIT_FILTER_ORDER);
        return registration;
    }

    /**
     * Schiebt Springs {@code ForwardedHeaderFilter} hinter den Rate-Limiter. Ohne diesen Eingriff
     * saehe der Rate-Limiter nur noch die vom Client selbst gesetzte XFF-Adresse.
     */
    @Bean
    static BeanPostProcessor forwardedHeaderFilterOrderPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FilterRegistrationBean<?> registration
                        && registration.getFilter() instanceof ForwardedHeaderFilter) {
                    registration.setOrder(FORWARDED_HEADER_FILTER_ORDER);
                }
                return bean;
            }
        };
    }
}
