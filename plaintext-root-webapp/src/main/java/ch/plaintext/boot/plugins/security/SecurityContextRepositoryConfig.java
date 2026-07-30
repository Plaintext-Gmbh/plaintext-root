/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Stellt den {@link SecurityContextRepository} als eigenstaendige Bean bereit.
 *
 * <p>Bewusst aus {@link PlaintextSecurityConfig} herausgezogen: Der
 * {@link PlaintextAuthenticationSuccessHandler} braucht das Repository (zum Persistieren
 * eines leeren Contexts im TOTP-Gate), und {@code PlaintextSecurityConfig} braucht wiederum
 * den SuccessHandler. Laege die Repository-Bean weiterhin in {@code PlaintextSecurityConfig},
 * entstuende eine Konstruktor-Zyklus-Abhaengigkeit. Diese kleine, zustandslose Config bricht
 * den Zyklus.
 */
@Configuration
public class SecurityContextRepositoryConfig {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
