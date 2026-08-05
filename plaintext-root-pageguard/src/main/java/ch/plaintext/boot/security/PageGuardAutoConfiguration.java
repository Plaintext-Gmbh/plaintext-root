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
 * Registriert den Seiten-Zugriffsschutz.
 *
 * <p><b>Warum eine AutoConfiguration und keine Stereotypen.</b> Die Klassen trugen bis 1.491.0
 * {@code @Service} bzw. {@code @Component} und wurden nur dann zu Beans, wenn die konsumierende
 * App {@code ch.plaintext} component-scannte. Fuer die root-App traf das zu; eine App, die sich
 * nur einzelne Module aus root nimmt, bekam den Guard dagegen stillschweigend nicht — sie startet
 * ohne Fehlermeldung, und der Seitenschutz existiert einfach nicht. Genau diese Klasse von Fehler
 * faellt erst auf, wenn jemand eine URL direkt aufruft.
 *
 * <p>Ueber {@code AutoConfiguration.imports} laeuft die Registrierung unabhaengig vom
 * Component-Scan. Alle Beans sind {@link ConditionalOnMissingBean}, eine App kann also jede
 * einzelne ersetzen.
 *
 * <p>Der {@link PageAccessGuardFilter} wird hier bewusst <b>nicht</b> registriert: er gehoert an
 * eine bestimmte Stelle der Spring-Security-Kette (nach dem {@code AuthorizationFilter}, siehe
 * Klassen-Javadoc des Filters) und wird darum von der Security-Konfiguration der App
 * eingehaengt.
 *
 * @author plaintext.ch
 * @since 1.492.0
 */
@Configuration
@EnableConfigurationProperties(PageGuardProperties.class)
@Slf4j
public class PageGuardAutoConfiguration {

    /**
     * @param menuRegistry die Menue-Registry, aus der die Zugriffsregeln abgeleitet werden
     * @param properties   Konfiguration unter {@code plaintext.security.page-guard}
     * @return der Guard-Service
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
     * Der Startup-Report liest die ausgelieferten Facelets und meldet die ohne Zugriffsregel. Er
     * laesst sich mit {@code plaintext.security.page-guard.startup-report=false} abschalten, etwa
     * fuer Tests oder wenn der Scan bei sehr vielen Views ins Gewicht faellt.
     *
     * @param resourcePatternResolver Resolver fuer den Classpath-Scan der Views
     * @param pageAccessGuardService  der Guard-Service
     * @return der Startup-Report
     */
    /**
     * Die Backing-Bean, die das Template bei jedem {@code preRenderView} ueber
     * {@code #{pageAccessGuardBackingBean.checkPageAccess()}} aufruft. Sie trug bislang nur
     * {@code @Named}/{@code @RequestScoped} und existierte damit nur in Apps, die
     * {@code ch.plaintext} component-scannen — in einem Konsumenten ohne diesen Scan brach jede
     * mit dem Template ausgelieferte Seite mit "Target Unreachable, identifier
     * [pageAccessGuardBackingBean] resolved to null" ab (Inventar, 05.08.2026, settings.html).
     * Request-Scope wie am Stereotyp; das {@code @Autowired}-Feld injiziert Spring auch bei
     * {@code @Bean}-Instanzen.
     *
     * @return die Backing-Bean fuer den Template-Aufruf
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
