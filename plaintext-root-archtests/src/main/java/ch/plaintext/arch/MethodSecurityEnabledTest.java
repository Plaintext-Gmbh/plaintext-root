/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 546 / Audit-Befund C-4: die Startklasse jeder Anwendung schaltet Spring-Method-Security ein.
 *
 * <p>Ohne {@code @EnableMethodSecurity} wird jedes {@code @PreAuthorize} <b>still ignoriert</b> —
 * die Annotation steht im Code, sieht nach einer Schranke aus und laesst jeden durch. Genau diese
 * Entscheidung hat der Bestand schon zweimal schriftlich getroffen, solange sie fehlte:
 * {@code PlaintextSecurityImpl} („wuerde also still wirkungslos annotiert") und
 * {@code I18nExportController} („die Annotation waere eine stille Attrappe"). Die
 * MCP-Scope-Schranken aller Anwendungen stehen als {@code @PreAuthorize} an den Werkzeugen und sind
 * durch {@link PlaintextMcpScopeVertragTest} gesichert — der prueft aber die <em>Annotation</em>,
 * nicht ihre <em>Wirkung</em>. Faellt der Schalter weg, bleibt jener Test gruen, und nur dieser
 * hier sieht es.
 *
 * <p><b>Karte 1298: eine Fassung statt fuenf.</b> Bis zum 22.09.2026 trugen root, app, guild,
 * schuetu und iot je eine Kopie, die sich nur im Namen der Startklasse unterschied (fuenf
 * verschiedene Pruefsummen, eine Logik). fwtool hatte keine — und fwtools Startklasse traegt
 * {@code @EnableMethodSecurity} tatsaechlich nicht, obwohl fwtool die root-Module mit ihren
 * {@code @PreAuthorize}-geschuetzten MCP-Werkzeugen ausliefert (BenutzerMcpTools, SettingsMcpTools,
 * SecretsMcpTools; {@code mcp-scope-vertrag.properties} in fwtool haelt 13 Werkzeuge fest). Diese
 * Fassung findet die Startklasse selbst: jede mit {@code @SpringBootApplication} annotierte Klasse
 * aus dem eigenen Reactor (Klassenverzeichnis, kein Jar — die {@code RootBootApplication} im
 * root-webapp-Jar eines Consumers ist dort nicht die Startklasse und zaehlt nicht).
 *
 * <p>Gelesen wird per Klassenname, nicht per Klassenreferenz: dieses Modul haengt nicht an
 * spring-security und spring-boot, und soll es fuer eine Annotation auch nicht.
 *
 * <p>Bootet keine DB.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class MethodSecurityEnabledTest {

    static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    static final String ENABLE_METHOD_SECURITY =
            "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity";

    @Test
    @DisplayName("jede @SpringBootApplication des Reactors traegt @EnableMethodSecurity")
    void mainKlasseAktiviertMethodSecurity() {
        JavaClasses klassen = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("ch");
        List<JavaClass> startklassen = startklassen(klassen, SPRING_BOOT_APPLICATION);
        assertFalse(startklassen.isEmpty(),
                "Keine mit @SpringBootApplication annotierte Klasse im eigenen Reactor gefunden (gesucht "
                        + "im Paket 'ch', ohne Jars und Testklassen). Dann prueft dieser Test nichts — "
                        + "liegt die Startklasse woanders, oder laeuft der Test im falschen Modul?");
        List<String> ohne = ohne(startklassen, ENABLE_METHOD_SECURITY);
        assertTrue(ohne.isEmpty(),
                "@EnableMethodSecurity muss aktiv sein, sonst werden @PreAuthorize-Gates still ignoriert. "
                        + "Startklasse(n) ohne den Schalter: " + ohne);
    }

    /**
     * Positivkontrolle: eine Startklasse ohne den Schalter wird gemeldet, eine mit ihm nicht. Ohne
     * sie waere ein Waechter, der Annotationen nie findet, von einem heilen nicht zu unterscheiden.
     * Die Beispiele tragen Stellvertreter-Annotationen (dieses Modul haengt bewusst nicht an Spring
     * Boot/Security); geprueft wird dieselbe Logik, die oben mit den echten Namen laeuft.
     */
    @Test
    @DisplayName("Positivkontrolle: Startklasse ohne den Schalter wird gemeldet")
    void positivkontrolle() {
        JavaClasses beispiele = new ClassFileImporter()
                .importClasses(OhneSchalter.class, MitSchalter.class, KeineStartklasse.class);
        List<JavaClass> startklassen = startklassen(beispiele, Start.class.getName());
        assertTrue(startklassen.size() == 2, "Genau die zwei Beispiel-Startklassen muessen erkannt werden: "
                + startklassen);
        List<String> ohne = ohne(startklassen, Schalter.class.getName());
        assertTrue(ohne.equals(List.of(OhneSchalter.class.getName())),
                "Genau die Startklasse ohne Schalter muss gemeldet werden, gemeldet wurde: " + ohne);
    }

    static List<JavaClass> startklassen(JavaClasses klassen, String startAnnotation) {
        return klassen.stream()
                .filter(k -> k.isAnnotatedWith(startAnnotation))
                .toList();
    }

    static List<String> ohne(List<JavaClass> startklassen, String schalter) {
        return startklassen.stream()
                .filter(k -> !k.isAnnotatedWith(schalter))
                .map(JavaClass::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Start {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Schalter {
    }

    @Start
    static class OhneSchalter {
    }

    @Start
    @Schalter
    static class MitSchalter {
    }

    @Schalter
    static class KeineStartklasse {
    }
}
