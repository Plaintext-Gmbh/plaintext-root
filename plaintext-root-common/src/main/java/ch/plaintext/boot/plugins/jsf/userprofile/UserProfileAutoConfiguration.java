/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import ch.plaintext.boot.plugins.objstore.GenericEntityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * Registers the user profile - theme, colour choice, menu mode and language.
 *
 * <p><b>Why an AutoConfiguration.</b> The classes carry {@code @Component}
 * resp. {@code @Service} and therefore only became beans if the consuming
 * application scanned {@code ch.plaintext} as well. Whoever does not do that -
 * and that is the normal case, because such a scan also picks up everything
 * else from the product line - did not get the classes and copied them
 * instead. That is exactly what happened in the ESTV inventory; the same cause
 * was behind moving the objstore out (PR #65).
 *
 * <p>All beans are {@link ConditionalOnMissingBean}: an application that brings
 * a variant of its own keeps it. That is the path along which an application
 * can migrate step by step instead of swapping everything at once.
 *
 * <p><b>What this class does NOT do.</b> {@code SimpleStorableEntity} and
 * {@code SimpleStorableEntityRepository} from the objstore remain the
 * application's business: entities belong in its {@code @EntityScan},
 * repositories in its {@code @EnableJpaRepositories}. Setting both from within
 * an AutoConfiguration would switch off Spring Boot's own repository detection
 * and thereby take more away from the application than it gives. Concretely, a
 * consuming application needs:
 *
 * <pre>
 * &#64;EntityScan(basePackages = {"...", "ch.plaintext.boot.plugins.objstore"})
 * &#64;EnableJpaRepositories(basePackages = {"...", "ch.plaintext.boot.plugins.objstore"})
 * </pre>
 *
 * @author plaintext.ch
 * @since 1.549.0
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication
public class UserProfileAutoConfiguration {

    /**
     * The storage location of the settings. Depends on the objstore and thereby on
     * a {@code SimpleStorableEntityRepository} that the application contributes
     * through its {@code @EnableJpaRepositories} - see the class comment.
     */
    @Bean
    @ConditionalOnMissingBean
    public UserPrefsSimpleStorage userPrefsSimpleStorage() {
        log.debug("[UserProfileAutoConfiguration] UserPrefsSimpleStorage registriert");
        return new UserPrefsSimpleStorage();
    }

    /**
     * Application-wide, because the colour palette is the same for all users -
     * one instance per session would be waste without a gain.
     */
    @Bean("themeColorProvider")
    @ConditionalOnMissingBean
    @Scope(value = "application", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ThemeColorProvider themeColorProvider() {
        return new ThemeColorProvider();
    }

    /**
     * Session-scoped: the bean carries the settings of the logged-in user. The
     * proxy is needed so that it can also be injected where no session is
     * involved.
     */
    @Bean("userPreferencesBackingBean")
    @ConditionalOnMissingBean
    @Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public UserPreferencesBackingBean userPreferencesBackingBean() {
        return new UserPreferencesBackingBean();
    }

    /**
     * The generic storage service of the objstore. It carries {@code @Service} and
     * so far only came about through a {@code ch.plaintext} scan; here it is
     * registered explicitly, so that subclasses such as
     * {@link UserPrefsSimpleStorage} can use it without a scan.
     */
    @Bean
    @ConditionalOnMissingBean
    public GenericEntityService<?> genericEntityService() {
        return new GenericEntityService<>();
    }
}
