/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.MenuRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Registers the page access guard.
 *
 * <p><b>Why an auto-configuration and not stereotypes.</b> Up to 1.491.0 the classes carried
 * {@code @Service} resp. {@code @Component} and only became beans if the consuming app
 * component-scanned {@code ch.plaintext}. That was the case for the root app; an app that only
 * picks individual modules out of root, on the other hand, silently did not get the guard — it
 * starts without an error message, and page protection simply does not exist. Exactly this class
 * of bug only surfaces once someone calls a URL directly.
 *
 * <p>Via {@code AutoConfiguration.imports} the registration runs independently of the component
 * scan. All beans are {@link ConditionalOnMissingBean}, so an app can replace every single one of
 * them.
 *
 * <p>The {@link PageAccessGuardFilter} is deliberately <b>not</b> registered here: it belongs at a
 * particular place in the Spring Security chain (after the {@code AuthorizationFilter}, see the
 * class javadoc of the filter) and is therefore hooked in by the app's security configuration.
 *
 * @author plaintext.ch
 * @since 1.492.0
 */
@Configuration
@EnableConfigurationProperties(PageGuardProperties.class)
@Slf4j
public class PageGuardAutoConfiguration {

    /**
     * @param menuRegistry the menu registry the access rules are derived from
     * @param properties   configuration under {@code plaintext.security.page-guard}
     * @return the guard service
     */
    @Bean
    @ConditionalOnMissingBean
    public PageAccessGuardService pageAccessGuardService(MenuRegistry menuRegistry,
                                                         PageGuardProperties properties) {
        log.info("Registering PageAccessGuardService (mode={}, enabled={})",
                properties.getMode(), properties.isEnabled());
        return new PageAccessGuardService(menuRegistry, properties);
    }

    /**
     * The startup report reads the shipped facelets and reports those without an access rule. It
     * can be switched off with {@code plaintext.security.page-guard.startup-report=false}, for
     * instance for tests or when the scan becomes noticeable with a very large number of views.
     *
     * @param resourcePatternResolver resolver for the classpath scan of the views
     * @param pageAccessGuardService  the guard service
     * @return the startup report
     */
    /**
     * The backing bean that the template calls on every {@code preRenderView} via
     * {@code #{pageAccessGuardBackingBean.checkPageAccess()}}. Until now it only carried
     * {@code @Named}/{@code @RequestScoped} and therefore only existed in apps that
     * component-scan {@code ch.plaintext} — in a consumer without that scan, every page shipped
     * with the template failed with "Target Unreachable, identifier
     * [pageAccessGuardBackingBean] resolved to null" (inventory, 05.08.2026, settings.html).
     * Request scope as on the stereotype; Spring injects the {@code @Autowired} field for
     * {@code @Bean} instances as well.
     *
     * @return the backing bean for the template call
     */
    @Bean
    @ConditionalOnMissingBean
    @RequestScope
    public PageAccessGuardBackingBean pageAccessGuardBackingBean() {
        return new PageAccessGuardBackingBean();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "plaintext.security.page-guard.startup-report",
            havingValue = "true", matchIfMissing = true)
    public PageAccessGuardStartupReport pageAccessGuardStartupReport(
            ResourcePatternResolver resourcePatternResolver,
            PageAccessGuardService pageAccessGuardService) {
        return new PageAccessGuardStartupReport(resourcePatternResolver, pageAccessGuardService);
    }
}
