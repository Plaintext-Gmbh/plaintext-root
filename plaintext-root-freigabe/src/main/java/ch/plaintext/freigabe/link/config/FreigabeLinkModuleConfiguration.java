/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.freigabe.link.repository.FreigabeLinkRepository;
import ch.plaintext.freigabe.link.token.JpaLinkTokenStore;
import ch.plaintext.linktoken.LinkTokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Bindet die Freigabe-Links (Karte 1476) in jede Anwendung ein, die das Modul auf dem Klassenpfad hat. */
@AutoConfiguration
@ComponentScan("ch.plaintext.freigabe.link")
@EntityScan("ch.plaintext.freigabe.link")
@EnableJpaRepositories("ch.plaintext.freigabe.link")
public class FreigabeLinkModuleConfiguration {

    /**
     * Standard-Ablage der Token-Links von Draw.io, Diashows und Files (Karte 1559). Eine Anwendung mit eigener
     * Ablage (z.B. einer Konfigurationsliste im Git) registriert ein eigenes {@link LinkTokenStore}-Bean; dann
     * tritt dieses zurück.
     */
    @Bean
    @ConditionalOnMissingBean(LinkTokenStore.class)
    public LinkTokenStore linkTokenStore(FreigabeLinkRepository links, ObjectProvider<PlaintextSecurity> security) {
        return new JpaLinkTokenStore(links, security);
    }
}
