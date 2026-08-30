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
 * Nachweis, dass der Spring-Kontext von plaintext-root <b>ohne</b> die abwaehlbaren Admin-Module
 * startet — also genau das, was eine App tut, die sie per {@code <exclusions>} aus
 * {@code plaintext-root-webapp} herauswirft.
 *
 * <p><b>Warum ein eigener Testlauf.</b> Der Test laeuft NICHT in der normalen Surefire-Ausfuehrung,
 * sondern in der zweiten Ausfuehrung {@code kontext-ohne-abwaehlbare-module} (siehe pom.xml dieses
 * Moduls). Die nimmt den vier Modul-Jars per {@code classpathDependencyExcludes} den Platz auf dem
 * Test-Classpath weg. Damit ist die Abwesenheit echt und nicht simuliert: keine
 * {@code AutoConfiguration.imports}, keine Klassen, keine Flyway-Migrationen, kein
 * Component-Scan-Treffer. Ein {@code spring.autoconfigure.exclude} haette das NICHT bewiesen — die
 * Beans dieser Module haengen in root und in allen vier Apps ohnehin am
 * {@code @ComponentScan("ch.plaintext")} der Boot-Klasse und nicht an ihrer AutoConfiguration.
 *
 * <p><b>Wozu er gut ist.</b> Er ist zugleich die Sperre: Wer im Kern (plaintext-root-webapp oder
 * einem plaintext-root-*-Modul) eine Klasse aus einem dieser Pakete importiert oder eine ihrer
 * Beans hart injiziert, laesst diesen Lauf mit {@code NoClassDefFoundError} bzw.
 * {@code NoSuchBeanDefinitionException} scharf werden — nicht erst den PROD-Start einer App, die
 * das Modul abgewaehlt hat.
 *
 * <p>Die Liste haelt {@code docs/MODULE_ABWAEHLEN.md} nach; sie steht doppelt (hier und in der
 * pom.xml), weil Maven Plugin-Konfiguration nicht aus Java lesen kann. {@link #listeStimmtUeberein}
 * prueft die Deckung.
 *
 * @since 1.644.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SchlankerKontextTest {

    /** Wurzelpakete der abwaehlbaren Module — Implementierung, nicht die Vertraege in -interfaces. */
    private static final List<String> ABGEWAEHLTE_PAKETE = List.of(
            "ch.plaintext.webhooks.",
            "ch.plaintext.notifications.",
            "ch.plaintext.secrets.",
            "ch.plaintext.modules.");

    /** Je eine Klasse aus jedem abgewaehlten Modul-Jar; muss beim Laden fehlschlagen. */
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
     * Vorbedingung: Die Jars sind wirklich weg. Laeuft der Test versehentlich in der normalen
     * Surefire-Ausfuehrung mit, faellt er hier auf — statt gruen zu sein und nichts zu beweisen.
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
                // genau so soll es sein
            }
        }
    }

    /** Der Kern steht: Kontext hochgefahren, Menue-Registry und Benutzer-Repository da. */
    @Test
    void kontextStartetOhneDieAbgewaehltenModule() {
        assertNotNull(kontext, "Kontext wurde nicht hochgefahren");
        assertNotNull(kontext.getBean(MenuRegistry.class), "Menue-Registry fehlt");
        assertNotNull(kontext.getBean(MyUserRepository.class), "Benutzer-Repository fehlt");
    }

    /** Und es ist wirklich keine Bean der abgewaehlten Module durchgerutscht. */
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
     * Flyway laeuft mit dem verkleinerten Migrationssatz durch. Auf einer <b>frischen</b> Datenbank
     * ist das der Normalfall; eine bestehende Datenbank braucht zusaetzlich
     * {@code spring.flyway.validate-on-migrate: false} (root setzt das seit dem Wegfall von
     * plaintext-root-email ohnehin) — siehe docs/MODULE_ABWAEHLEN.md.
     */
    @Test
    void flywayLaeuftOhneDieMigrationenDerAbgewaehltenModule() {
        assertTrue(flyway.info().applied().length > 0, "Es wurde keine Migration angewandt");
        assertEquals(0, flyway.info().pending().length, "Nach dem Start darf nichts offen sein");
    }

    /**
     * Haelt die Liste hier und die {@code classpathDependencyExcludes} der pom.xml zusammen: Die
     * pom reicht die tatsaechlich ausgeschlossenen Artefakte als Systemproperty herein.
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
