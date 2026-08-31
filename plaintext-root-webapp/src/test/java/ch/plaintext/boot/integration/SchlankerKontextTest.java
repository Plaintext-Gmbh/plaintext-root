/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proof that the Spring context of plaintext-root starts <b>without</b> the deselectable admin
 * modules — that is, exactly what an app does that throws them out of
 * {@code plaintext-root-webapp} via {@code <exclusions>}.
 *
 * <p><b>Why a test run of its own.</b> The test does NOT run in the normal Surefire execution,
 * but in the second execution {@code kontext-ohne-abwaehlbare-module} (see the pom.xml of this
 * module). That one takes the place on the test classpath away from the four module jars via
 * {@code classpathDependencyExcludes}. The absence is thereby real and not simulated: no
 * {@code AutoConfiguration.imports}, no classes, no Flyway migrations, no
 * component scan hit. A {@code spring.autoconfigure.exclude} would NOT have proven that — the
 * beans of these modules hang off the {@code @ComponentScan("ch.plaintext")} of the boot class in
 * root and in all four apps anyway, and not off their AutoConfiguration.
 *
 * <p><b>What it is good for.</b> It is at the same time the barrier: whoever imports a class from
 * one of these packages in the core (plaintext-root-webapp or a plaintext-root-* module) or
 * hard-injects one of their beans makes this run trip with a {@code NoClassDefFoundError} resp.
 * {@code NoSuchBeanDefinitionException} — instead of only the PROD start of an app that has
 * deselected the module.
 *
 * <p>The list is tracked by {@code docs/OPTIONAL_MODULES.md}; it exists twice (here and in the
 * pom.xml), because Maven cannot read plugin configuration out of Java. {@link #listeStimmtUeberein}
 * checks that the two agree.
 *
 * @since 1.644.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SchlankerKontextTest {

    /** Root packages of the deselectable modules — implementation, not the contracts in -interfaces. */
    private static final List<String> ABGEWAEHLTE_PAKETE = List.of(
            "ch.plaintext.webhooks.",
            "ch.plaintext.notifications.",
            "ch.plaintext.secrets.",
            "ch.plaintext.modules.");

    /** One class from each deselected module jar; loading it has to fail. */
    private static final List<String> ABGEWAEHLTE_KLASSEN = List.of(
            "ch.plaintext.webhooks.config.WebhooksModuleConfiguration",
            "ch.plaintext.notifications.config.NotificationsModuleConfiguration",
            "ch.plaintext.secrets.config.SecretsModuleConfiguration",
            "ch.plaintext.modules.config.ModulesModuleConfiguration");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "schlankerkontexttest");
    }

    @Autowired
    private ApplicationContext kontext;

    @Autowired
    private Flyway flyway;

    /**
     * Precondition: the jars really are gone. If the test accidentally runs along in the normal
     * Surefire execution, it stands out here — instead of being green and proving nothing.
     */
    @Test
    void modulJarsSindNichtAufDemClasspath() {
        for (String klasse : ABGEWAEHLTE_KLASSEN) {
            try {
                Class.forName(klasse);
                fail("'" + klasse + "' ist auf dem Classpath. Dieser Test beweist nur etwas in der "
                        + "Surefire-Ausfuehrung 'kontext-ohne-abwaehlbare-module' (pom.xml), die die "
                        + "Modul-Jars per classpathDependencyExcludes entfernt.");
            } catch (ClassNotFoundException | NoClassDefFoundError erwartet) {
                // exactly as it should be
            }
        }
    }

    /** The core stands: context started, menu registry and user repository present. */
    @Test
    void kontextStartetOhneDieAbgewaehltenModule() {
        assertNotNull(kontext, "Kontext wurde nicht hochgefahren");
        assertNotNull(kontext.getBean(MenuRegistry.class), "Menue-Registry fehlt");
        assertNotNull(kontext.getBean(MyUserRepository.class), "Benutzer-Repository fehlt");
    }

    /** And really no bean of the deselected modules has slipped through. */
    @Test
    void keineBeanAusDenAbgewaehltenModulen() {
        for (String name : kontext.getBeanDefinitionNames()) {
            Class<?> typ = kontext.getType(name);
            if (typ == null) {
                continue;
            }
            String fqn = typ.getName();
            assertFalse(ABGEWAEHLTE_PAKETE.stream().anyMatch(fqn::startsWith),
                    "Bean '" + name + "' (" + fqn + ") stammt aus einem abgewaehlten Modul");
        }
    }

    /**
     * Flyway runs through with the reduced set of migrations. On a <b>fresh</b> database
     * that is the normal case; an existing database additionally needs
     * {@code spring.flyway.validate-on-migrate: false} (root sets that anyway since
     * plaintext-root-email was dropped) — see docs/OPTIONAL_MODULES.md.
     */
    @Test
    void flywayLaeuftOhneDieMigrationenDerAbgewaehltenModule() {
        assertTrue(flyway.info().applied().length > 0, "Es wurde keine Migration angewandt");
        assertEquals(0, flyway.info().pending().length, "Nach dem Start darf nichts offen sein");
    }

    /**
     * Keeps the list here and the {@code classpathDependencyExcludes} of the pom.xml together: the
     * pom hands the actually excluded artifacts in as a system property.
     */
    @Test
    void listeStimmtUeberein() {
        String ausPom = System.getProperty("plaintext.abgewaehlte.module");
        assertNotNull(ausPom, "Systemproperty 'plaintext.abgewaehlte.module' fehlt — "
                + "die Surefire-Ausfuehrung in der pom.xml reicht sie herein");
        List<String> artefakte = Arrays.stream(ausPom.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertEquals(ABGEWAEHLTE_KLASSEN.size(), artefakte.size(),
                "pom.xml und Testliste nennen unterschiedlich viele Module: " + artefakte);
    }
}
