/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
 * Card 664: The default checker must not displace an app's own blocklist — and above all must
 * not sit alongside it.
 *
 * <p><b>Why this is a test of its own.</b> With {@code RevokedTokenService}, plaintext-schuetu
 * has brought its own {@link JtiRevocationChecker} bean since card 484. If the new checker were a
 * plain {@code @Component}, schuetu would have two beans of the same interface, and
 * {@code ObjectProvider.getIfAvailable()} in {@code McpBearerTokenFilterConfig} would abort at
 * startup with {@code NoUniqueBeanDefinitionException}. A patch in root would thereby have
 * paralysed another application — an error that only shows up at rollout.</p>
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

    /** The situation in plaintext-schuetu. */
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
        // An application that does not run the module should behave as it did before card 664 —
        // and not fail at startup on a missing dependency.
        runner.run(context -> assertEquals(0, context.getBeanNamesForType(JtiRevocationChecker.class).length));
    }

    /** Proves along the way that the default checker really does use the lookup. */
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
                // not used in the test
            }
        };
        var checker = new ApiTokenJtiRevocationChecker(lookup);

        assertTrue(checker.isRevoked("gesperrt"));
    }
}
