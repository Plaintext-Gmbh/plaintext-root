/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken.config;

import ch.plaintext.apitoken.ApiTokenJtiRevocationChecker;
import ch.plaintext.apitoken.ApiTokenRevocationLookup;
import ch.plaintext.apitoken.JtiRevocationChecker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Karte 664: Der Standard-Checker darf eine App-eigene Blocklist nicht verdrängen — und vor allem
 * nicht neben ihr stehen.
 *
 * <p><b>Warum das ein eigener Test ist.</b> plaintext-schuetu bringt mit {@code RevokedTokenService}
 * seit Karte 484 eine eigene {@link JtiRevocationChecker}-Bean mit. Wäre der neue Checker ein
 * schlichtes {@code @Component}, hätte schuetu zwei Beans desselben Interface, und
 * {@code ObjectProvider.getIfAvailable()} in {@code McpBearerTokenFilterConfig} würde beim Start
 * mit {@code NoUniqueBeanDefinitionException} abbrechen. Ein Patch in root hätte damit eine andere
 * Anwendung lahmgelegt — ein Fehler, den man erst beim Rollout sieht.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class JtiRevocationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JtiRevocationAutoConfiguration.class));

    @Configuration
    static class MitLookup {
        @Bean
        ApiTokenRevocationLookup lookup() {
            return mock(ApiTokenRevocationLookup.class);
        }
    }

    /** Die Situation in plaintext-schuetu. */
    @Configuration
    static class MitEigenemChecker {
        @Bean
        ApiTokenRevocationLookup lookup() {
            return mock(ApiTokenRevocationLookup.class);
        }

        @Bean
        JtiRevocationChecker appEigenerChecker() {
            return jti -> true;
        }
    }

    @Test
    void ohneAppEigeneBeanGibtEsDenStandardChecker() {
        runner.withUserConfiguration(MitLookup.class).run(context -> {
            assertTrue(context.getBeanNamesForType(JtiRevocationChecker.class).length == 1,
                    "genau eine Bean, sonst wirft getIfAvailable() im Filter");
            assertInstanceOf(ApiTokenJtiRevocationChecker.class, context.getBean(JtiRevocationChecker.class));
        });
    }

    @Test
    void mitAppEigenerBeanBleibtEsBeiEINERBean() {
        runner.withUserConfiguration(MitEigenemChecker.class).run(context -> {
            assertEquals(1, context.getBeanNamesForType(JtiRevocationChecker.class).length,
                    "der Standard-Checker muss zuruecktreten — sonst startet schuetu nicht mehr");
            assertTrue(context.getBean(JtiRevocationChecker.class).isRevoked("egal"),
                    "es muss die App-eigene Bean uebrig bleiben, nicht der Standard");
        });
    }

    @Test
    void ohneLookupGibtEsGarKeineBeanStattEinesStartfehlers() {
        // Eine Anwendung, die das Modul nicht betreibt, soll sich verhalten wie vor Karte 664 —
        // und nicht beim Start an einer fehlenden Abhaengigkeit scheitern.
        runner.run(context -> assertEquals(0, context.getBeanNamesForType(JtiRevocationChecker.class).length));
    }

    /** Belegt nebenbei, dass der Standard-Checker tatsaechlich den Lookup benutzt. */
    @Test
    void standardCheckerFragtDenLookup() {
        var lookup = new ApiTokenRevocationLookup() {
            @Override
            public boolean isJtiRevoked(String jti) {
                return "gesperrt".equals(jti);
            }

            @Override
            public Optional<TokenZustand> findForValidation(String tokenHash) {
                return Optional.empty();
            }

            @Override
            public void markUsed(long id) {
                // im Test nicht benutzt
            }
        };
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertTrue(checker.isRevoked("gesperrt"));
    }
}
