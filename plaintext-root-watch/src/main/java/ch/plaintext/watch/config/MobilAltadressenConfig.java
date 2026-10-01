/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.config;

import ch.plaintext.watch.mobil.MobilAltadressenFilter;
import ch.plaintext.watch.page.WatchPageRegistry;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MobilAltadressenFilter} (card 1387) ahead of the {@code .html} rewrite.
 *
 * <p>{@code REQUEST} only: the old addresses are typed, saved or linked — they arrive as a
 * request, never as a forward. The registry is handed over as an {@code ObjectProvider} so that
 * registering the filter does not pull the page beans, and with them JPA, into the early phase in
 * which the servlet container collects its filters.</p>
 */
@Configuration
public class MobilAltadressenConfig {

    @Bean
    public FilterRegistrationBean<MobilAltadressenFilter> mobilAltadressenFilterRegistration(
            ObjectProvider<WatchPageRegistry> registry) {
        FilterRegistrationBean<MobilAltadressenFilter> registration =
                new FilterRegistrationBean<>(new MobilAltadressenFilter(registry));
        registration.addUrlPatterns("/watch/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(MobilAltadressenFilter.ORDER);
        return registration;
    }
}
