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
 * Registers the {@link RateLimitFilter} at the right place in the servlet filter chain.
 *
 * <p>SECURITY (card 303): the filter previously was just a {@code @Component} without an order.
 * Spring Boot registers such filters with {@code Ordered.LOWEST_PRECEDENCE}, whereas the
 * {@code springSecurityFilterChain} runs at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}. That way the rate limiting ran
 * <em>behind</em> security and the limits for {@code POST /login} as well as {@code POST /ott/generate}
 * could not take effect at all.
 *
 * <p>Why all the way to the front and not just barely ahead of security: the filter has to see the
 * <b>real</b> peer address and the <b>unfiltered</b> {@code X-Forwarded-For} chain in order to be able
 * to determine the bucket key spoof-safely (see {@link ClientIpResolver}). Spring's
 * {@code ForwardedHeaderFilter} (activated via {@code server.forward-headers-strategy=FRAMEWORK})
 * is registered by Spring Boot at {@code Ordered.HIGHEST_PRECEDENCE}, replaces
 * {@code getRemoteAddr()} with the <em>first</em> — that is, freely chosen by the client — element of
 * the XFF chain and hides the {@code X-Forwarded-*} headers afterwards. A rate limiter behind it
 * could not possibly be spoof-safe.
 *
 * <p>Therefore the {@code forwardedHeaderFilter} registration created by Spring Boot is moved back
 * by ten positions here via a {@link BeanPostProcessor}. Deliberately not via a
 * replacement bean of the same name: that would depend on the auto-configuration bean backing off,
 * and on a future Boot change the filter would in case of doubt be registered twice or not at
 * all. The BeanPostProcessor only changes the order value; the filter instance, the dispatcher types
 * and the {@code server.forward-headers-strategy} semantics remain untouched. For everything behind
 * the rate limiter — security, JSF, URL generation — the behaviour is therefore unchanged.
 *
 * <p>Resulting order:
 * <pre>
 *   RateLimitFilter        HIGHEST_PRECEDENCE       (real peer address + raw XFF chain)
 *   ForwardedHeaderFilter  HIGHEST_PRECEDENCE + 10  (as before: fix scheme/host/remoteAddr)
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
     * Moves Spring's {@code ForwardedHeaderFilter} behind the rate limiter. Without this
     * intervention the rate limiter would only see the XFF address set by the client itself.
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
