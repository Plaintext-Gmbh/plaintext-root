/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.SecurityProvider;
import ch.plaintext.boot.plugins.config.UrlRewriteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registriert die wiederverwendbare Web-Infrastruktur.
 *
 * <p><b>Warum eine AutoConfiguration.</b> Die Klassen tragen {@code @Component}/{@code @Controller}
 * und wurden damit nur zu Beans, wenn die konsumierende Anwendung {@code ch.plaintext}
 * component-scannt. Fuer die root-App trifft das zu; eine Anwendung, die sich einzelne Module
 * nimmt, bekam sie stillschweigend nicht — sie startet ohne Fehlermeldung, und das
 * URL-Rewriting fehlt einfach. Genau diese Klasse von Fehler faellt erst im Betrieb auf.
 *
 * <p>Alle Beans sind {@link ConditionalOnMissingBean}; eine Anwendung kann also jede einzelne
 * durch eine eigene ersetzen, ohne dass hier etwas dazwischenfunkt.
 *
 * <p>Die Debug-Controller sind bewusst <b>nicht</b> hier registriert: sie legen interne Pfade
 * und Menuestrukturen offen und gehoeren deshalb hinter eine bewusste Entscheidung der
 * konsumierenden Anwendung (Component-Scan oder eigene {@code @Bean}-Deklaration).
 *
 * @author plaintext.ch
 * @since 1.494.0
 */
@Configuration
@ConditionalOnWebApplication
@Import(UrlRewriteConfig.class)
@Slf4j
public class WebAutoConfiguration {

    /**
     * Liest die Rollen des angemeldeten Benutzers aus dem Spring-Security-Kontext und beantwortet
     * damit die Sichtbarkeitsfragen des Menues.
     *
     * @return der Rollen-Anbieter
     */
    @Bean
    @ConditionalOnMissingBean(SecurityProvider.class)
    public SpringSecurityProvider springSecurityProvider() {
        log.info("Registering SpringSecurityProvider");
        return new SpringSecurityProvider();
    }
}
