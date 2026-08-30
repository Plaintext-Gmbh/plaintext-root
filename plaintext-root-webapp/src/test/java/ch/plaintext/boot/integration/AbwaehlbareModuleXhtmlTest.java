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
 * Second half of the deselection proof: {@link SchlankerKontextTest} shows that the
 * <b>Spring context</b> starts without the deselectable modules — but it renders no page. An
 * XHTML of the core that writes {@code #{webhookBean.x}} therefore does not stand out to it; it would
 * only blow up with a {@code PropertyNotFoundException} when a page of a slim app is opened.
 *
 * <p>This test closes the gap from the other side: it collects the bean names of the
 * deselectable modules (via component scan, the jars are present in this run) and checks every XHTML
 * that <b>the core</b> ships — {@code plaintext-root-webapp} and {@code plaintext-root-template}.
 * Whoever addresses such a bean there needs a null guard in the same document
 * ({@code #{bean != null}} or {@code empty bean}); today exactly one place has one, the
 * notification bell in {@code includes/topbar.xhtml}.
 *
 * <p><b>A limit, named openly:</b> the test only sees <em>that</em> a null guard stands in the
 * document, not <em>that it also encloses the place in question</em>. It replaces no thinking, but it
 * prevents the silent variant — a new reference without any guard at all.
 *
 * @since 1.644.0
 */
class AbwaehlbareModuleXhtmlTest {

    /** Must match the list in the pom.xml and in {@link SchlankerKontextTest}. */
    private static final List<String> ABWAEHLBARE_PAKETE = List.of(
            "ch.plaintext.webhooks",
            "ch.plaintext.notifications",
            "ch.plaintext.secrets",
            "ch.plaintext.modules");

    /** Only the XHTML of these two artifacts are "core" — the modules bring their own. */
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
                + "nehmen (pom.xml, SchlankerKontextTest, docs/OPTIONAL_MODULES.md).");
    }

    /** Bean name -&gt; package, for all Spring beans of the deselectable modules. */
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

    /** All XHTML that come from plaintext-root-webapp or plaintext-root-template. */
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
