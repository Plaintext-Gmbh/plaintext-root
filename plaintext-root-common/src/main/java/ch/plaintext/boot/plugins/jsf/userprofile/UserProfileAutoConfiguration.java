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
 * Registriert das Benutzerprofil - Theme, Farbwahl, Menuemodus und Sprache.
 *
 * <p><b>Warum eine AutoConfiguration.</b> Die Klassen tragen {@code @Component}
 * bzw. {@code @Service} und wurden damit nur zu Beans, wenn die konsumierende
 * Anwendung {@code ch.plaintext} mitscannt. Wer das nicht tut - und das ist der
 * Normalfall, weil ein solcher Scan auch alles andere aus der Produktlinie
 * einsammelt - bekam die Klassen nicht und hat sie stattdessen kopiert. Genau
 * das ist im Inventar der ESTV passiert; dieselbe Ursache lag der Auslagerung
 * des objstore zugrunde (PR #65).
 *
 * <p>Alle Beans sind {@link ConditionalOnMissingBean}: eine Anwendung, die eine
 * eigene Variante mitbringt, behaelt sie. Das ist der Weg, auf dem eine
 * Anwendung schrittweise umsteigen kann, statt alles auf einmal zu tauschen.
 *
 * <p><b>Was diese Klasse NICHT erledigt.</b> {@code SimpleStorableEntity} und
 * {@code SimpleStorableEntityRepository} aus dem objstore bleiben Sache der
 * Anwendung: Entities gehoeren in deren {@code @EntityScan}, Repositories in
 * deren {@code @EnableJpaRepositories}. Beides aus einer AutoConfiguration
 * heraus zu setzen wuerde Spring Boots eigene Repository-Erkennung abschalten
 * und der Anwendung damit mehr wegnehmen als geben. Konkret braucht eine
 * konsumierende Anwendung:
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
     * Der Ablageort der Einstellungen. Haengt am objstore und damit an einem
     * {@code SimpleStorableEntityRepository}, das die Anwendung ueber ihren
     * {@code @EnableJpaRepositories} beisteuert - siehe Klassenkommentar.
     */
    @Bean
    @ConditionalOnMissingBean
    public UserPrefsSimpleStorage userPrefsSimpleStorage() {
        log.debug("[UserProfileAutoConfiguration] UserPrefsSimpleStorage registriert");
        return new UserPrefsSimpleStorage();
    }

    /**
     * Anwendungsweit, weil die Farbpalette fuer alle Benutzer dieselbe ist -
     * eine Instanz je Sitzung waere Verschwendung ohne Gewinn.
     */
    @Bean("themeColorProvider")
    @ConditionalOnMissingBean
    @Scope(value = "application", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public ThemeColorProvider themeColorProvider() {
        return new ThemeColorProvider();
    }

    /**
     * Sitzungsgebunden: die Bean traegt die Einstellungen des angemeldeten
     * Benutzers. Der Proxy ist noetig, damit sie sich auch dort injizieren
     * laesst, wo keine Sitzung im Spiel ist.
     */
    @Bean("userPreferencesBackingBean")
    @ConditionalOnMissingBean
    @Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public UserPreferencesBackingBean userPreferencesBackingBean() {
        return new UserPreferencesBackingBean();
    }

    /**
     * Der generische Ablagedienst des objstore. Er traegt {@code @Service} und
     * kam bisher nur ueber einen {@code ch.plaintext}-Scan zustande; hier wird
     * er ausdruecklich registriert, damit Ableitungen wie
     * {@link UserPrefsSimpleStorage} ihn ohne Scan verwenden koennen.
     */
    @Bean
    @ConditionalOnMissingBean
    public GenericEntityService<?> genericEntityService() {
        return new GenericEntityService<>();
    }
}
