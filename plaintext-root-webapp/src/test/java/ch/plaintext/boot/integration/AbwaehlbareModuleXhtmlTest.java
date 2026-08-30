/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zweite Haelfte des Abwaehl-Nachweises: {@link SchlankerKontextTest} zeigt, dass der
 * <b>Spring-Kontext</b> ohne die abwaehlbaren Module startet — er rendert aber keine Seite. Eine
 * XHTML des Kerns, die {@code #{webhookBean.x}} schreibt, faellt ihm deshalb nicht auf; sie wuerde
 * erst beim Seitenaufruf einer schlanken App mit {@code PropertyNotFoundException} auffliegen.
 *
 * <p>Dieser Test schliesst die Luecke von der anderen Seite: Er sammelt die Bean-Namen der
 * abwaehlbaren Module (per Component-Scan, die Jars sind in diesem Lauf da) und prueft jede XHTML,
 * die <b>der Kern</b> ausliefert — {@code plaintext-root-webapp} und {@code plaintext-root-template}.
 * Wer so eine Bean dort anspricht, braucht im selben Dokument einen Null-Schutz
 * ({@code #{bean != null}} oder {@code empty bean}); heute hat genau eine Stelle einen, das
 * Benachrichtigungs-Glockchen in {@code includes/topbar.xhtml}.
 *
 * <p><b>Grenze, offen benannt:</b> Der Test sieht nur, <em>dass</em> ein Null-Schutz im Dokument
 * steht, nicht, <em>dass er die Stelle auch umschliesst</em>. Er ersetzt kein Nachdenken, aber er
 * verhindert die stille Variante — eine neue Referenz ganz ohne Schutz.
 *
 * @since 1.644.0
 */
class AbwaehlbareModuleXhtmlTest {

    /** Muss zur Liste in der pom.xml und in {@link SchlankerKontextTest} passen. */
    private static final List<String> ABWAEHLBARE_PAKETE = List.of(
            "ch.plaintext.webhooks",
            "ch.plaintext.notifications",
            "ch.plaintext.secrets",
            "ch.plaintext.modules");

    /** Nur die XHTML dieser beiden Artefakte sind "Kern" — die Module bringen ihre eigenen mit. */
    private static final List<String> KERN_ARTEFAKTE = List.of(
            "plaintext-root-webapp", "plaintext-root-template");

    @Test
    void kernXhtmlSprichtKeineBeanEinesAbwaehlbarenModulsUngeschuetztAn() throws IOException {
        Map<String, String> beanNamen = beanNamenDerAbwaehlbarenModule();
        assertFalse(beanNamen.isEmpty(),
                "Kein Bean-Name gefunden — dann prueft dieser Test nichts. Stimmen die Pakete noch?");

        List<Resource> kernSeiten = kernXhtml();
        assertFalse(kernSeiten.isEmpty(), "Keine Kern-XHTML gefunden — der Scan greift ins Leere.");

        List<String> verstoesse = new ArrayList<>();
        for (Resource seite : kernSeiten) {
            String inhalt = seite.getContentAsString(StandardCharsets.UTF_8);
            for (Map.Entry<String, String> bean : beanNamen.entrySet()) {
                String name = bean.getKey();
                if (!Pattern.compile("#\\{\\s*" + Pattern.quote(name) + "\\b").matcher(inhalt).find()) {
                    continue;
                }
                boolean geschuetzt = Pattern.compile(Pattern.quote(name) + "\\s*(!=|==)\\s*null").matcher(inhalt).find()
                        || Pattern.compile("empty\\s+" + Pattern.quote(name) + "\\b").matcher(inhalt).find();
                if (!geschuetzt) {
                    verstoesse.add(seite.getFilename() + " spricht '" + name + "' aus dem abwaehlbaren Modul "
                            + bean.getValue() + " ohne Null-Schutz an");
                }
            }
        }
        assertTrue(verstoesse.isEmpty(), "Kern-XHTML haengt ungeschuetzt an abwaehlbaren Modulen:\n  "
                + String.join("\n  ", verstoesse)
                + "\nEntweder Null-Schutz setzen (#{bean != null}) oder das Modul aus der Abwaehl-Liste "
                + "nehmen (pom.xml, SchlankerKontextTest, docs/MODULE_ABWAEHLEN.md).");
    }

    /** Bean-Name -&gt; Paket, fuer alle Spring-Beans der abwaehlbaren Module. */
    private static Map<String, String> beanNamenDerAbwaehlbarenModule() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Named.class));
        AnnotationBeanNameGenerator generator = new AnnotationBeanNameGenerator();
        SimpleBeanDefinitionRegistry registry = new SimpleBeanDefinitionRegistry();

        Map<String, String> namen = new LinkedHashMap<>();
        for (String paket : ABWAEHLBARE_PAKETE) {
            scanner.findCandidateComponents(paket).stream()
                    .filter(AnnotatedBeanDefinition.class::isInstance)
                    .forEach(bd -> namen.put(generator.generateBeanName(bd, registry), paket));
        }
        return namen;
    }

    /** Alle XHTML, die aus plaintext-root-webapp oder plaintext-root-template kommen. */
    private static List<Resource> kernXhtml() throws IOException {
        Resource[] alle = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/resources/**/*.xhtml");
        List<Resource> kern = new ArrayList<>();
        for (Resource r : alle) {
            String url = r.getURL().toString();
            if (KERN_ARTEFAKTE.stream().anyMatch(url::contains)) {
                kern.add(r);
            }
        }
        return kern;
    }
}
