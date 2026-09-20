/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import jakarta.servlet.DispatcherType;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1290 — das Filter-Inventar des <b>laufenden Containers</b>, und die Wache dagegen, dass
 * wieder ein Filter hinter dem {@code .html}-Forward auf {@code REQUEST} beschraenkt wird.
 *
 * <h2>Der Mechanismus</h2>
 *
 * <p>Jede Seite dieses Hauses wird als {@code .html} adressiert.
 * {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} steht auf
 * {@code Ordered.HIGHEST_PRECEDENCE + 30} und erreicht die Sicht mit einem
 * {@code RequestDispatcher.forward()} — <b>ohne</b> {@code chain.doFilter()}. Wer in der Kette
 * dahinter steht, wird auf dem REQUEST-Durchgang nie erreicht und laeuft nur noch, wenn er fuer
 * {@code FORWARD} angemeldet ist. In Karte 1280 machte genau das eine Zugangsbeschraenkung
 * wirkungslos: lautlos, ohne Fehler, ordentlich registriert, auf keiner einzigen Seite wirksam.</p>
 *
 * <h2>Warum aus dem Container gelesen wird und nicht aus den Bohnen</h2>
 *
 * <p>Spring Boot <i>leitet</i> die Dispatcher-Typen ab, wenn sie nicht gesetzt sind
 * ({@code AbstractFilterRegistrationBean#determineDispatcherTypes}: {@code EnumSet.allOf(...)}
 * fuer einen {@code OncePerRequestFilter}, sonst nur {@code EnumSet.of(REQUEST)}) — und es meldet
 * auch Bohnen an, die gar keinen {@code FilterRegistrationBean} haben (jede Bohne vom Typ
 * {@code Filter} landet auf {@code /*}). Beides sieht man den Konfigurationsklassen nicht an.
 * {@code StandardContext.findFilterMaps()} ist dagegen genau die Liste, nach der Tomcat die Kette
 * baut: Name, Adressmuster, Dispatcher-Typen — und in der Reihenfolge der Kette. Dass die
 * Reihenfolge dieses Feldes wirklich die Ausfuehrungsreihenfolge ist, misst
 * {@link FilterLaufMessungTest} im selben Zug nach.</p>
 *
 * <h2>Eine Eigenheit dieses Moduls, die man der Liste ansieht</h2>
 *
 * <p>{@code RootBootApplication} traegt ein ausdrueckliches
 * {@code @ComponentScan(basePackages = "ch.plaintext")}, und ein solcher Scan bringt den
 * {@code TypeExcludeFilter} von {@code @SpringBootApplication} nicht mit. Dadurch landen
 * {@code @TestConfiguration}-Klassen unterhalb von {@code ch.plaintext} in <b>jedem</b>
 * Testkontext dieses Moduls — im Inventar steht deshalb auch {@code karte652BearerFilter} aus
 * {@code BearerAccessDeniedChainTest}. Der hoert auf {@code /api/karte652/*}, kann also keine
 * Seite treffen und stoert die Aussage nicht; produktiv existiert er nicht.</p>
 *
 * <p>Diese Klasse laeuft bewusst <b>ohne</b> den Mitschreiber aus {@link FilterLaufMessungTest}:
 * der tauscht die Filterinstanz in der Registrierung aus, und Spring Boot erkennt die blosse
 * {@code Filter}-Bohne dann nicht mehr wieder und meldet sie ein zweites Mal an. Das Inventar
 * hier ist deshalb die unverfaelschte Produktivverdrahtung.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FilterDispatcherInventarTest {

    /** Der Rewrite, an dem sich alles entscheidet. */
    static final String REWRITE = "htmlRewriteFilter";

    /**
     * Filter, die <b>hinter</b> dem Rewrite auf {@code /*} hoeren und trotzdem bewusst ohne
     * {@code FORWARD} auskommen — mit Begruendung. Leer: nach Karte 1290 gibt es keinen solchen
     * Fall mehr. Ein neuer Eintrag ist eine Entscheidung, kein Versehen.
     */
    static final Map<String, String> BEWUSST_NUR_REQUEST_HINTER_DEM_REWRITE = Map.of();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "filterdispatcherinventartest");
    }

    @Autowired
    private ApplicationContext applicationContext;

    /** Ein Eintrag des Container-Inventars. */
    record Eintrag(int platz, String name, Set<String> muster, Set<String> typen) {

        boolean aufAllenPfaden() {
            return muster.contains("/*");
        }

        boolean mitForward() {
            return typen.contains(DispatcherType.FORWARD.name());
        }

        @Override
        public String toString() {
            return "%2d %-45s %-18s %s".formatted(platz, name, muster, typen);
        }
    }

    private List<Eintrag> inventar() {
        WebServer server = ((ServletWebServerApplicationContext) applicationContext).getWebServer();
        assertTrue(server instanceof TomcatWebServer,
                "Kein Tomcat — dieses Inventar liest Tomcats FilterMaps: " + server.getClass());
        Tomcat tomcat = ((TomcatWebServer) server).getTomcat();
        Context context = (Context) tomcat.getHost().findChildren()[0];

        List<Eintrag> eintraege = new ArrayList<>();
        int platz = 0;
        for (FilterMap map : context.findFilterMaps()) {
            Set<String> typen = new TreeSet<>();
            String[] dispatcher = map.getDispatcherNames();
            if (dispatcher == null || dispatcher.length == 0) {
                // Servlet-Spezifikation: ohne Angabe gilt REQUEST.
                typen.add(DispatcherType.REQUEST.name());
            } else {
                Collections.addAll(typen, dispatcher);
            }
            Set<String> muster = new TreeSet<>(Arrays.asList(map.getURLPatterns()));
            eintraege.add(new Eintrag(platz++, map.getFilterName(), muster, typen));
        }
        return eintraege;
    }

    @Test
    @DisplayName("Kein Filter hinter dem .html-Rewrite, der auf /* hoert und FORWARD nicht kennt")
    void keinFilterHinterDemRewriteOhneForward() {
        List<Eintrag> eintraege = inventar();

        System.out.println("### Filter-Inventar aus dem laufenden Container (Platz = Kettenreihenfolge)");
        eintraege.forEach(e -> System.out.println("    " + e));

        int platzDesRewrites = eintraege.stream()
                .filter(e -> REWRITE.equals(e.name()))
                .mapToInt(Eintrag::platz)
                .findFirst()
                .orElse(-1);
        assertTrue(platzDesRewrites >= 0,
                "Der " + REWRITE + " steht nicht im Inventar. Dann ist entweder der Messaufbau "
                        + "kaputt oder die ganze .html-Adressierung ist weg — in beiden Faellen "
                        + "sagt der Rest dieser Klasse nichts aus. Inventar: " + eintraege);

        List<String> verdaechtig = eintraege.stream()
                .filter(e -> e.platz() > platzDesRewrites)
                .filter(Eintrag::aufAllenPfaden)
                .filter(e -> !e.mitForward())
                .filter(e -> !BEWUSST_NUR_REQUEST_HINTER_DEM_REWRITE.containsKey(e.name()))
                .map(Eintrag::toString)
                .toList();

        assertTrue(verdaechtig.isEmpty(),
                "Diese Filter stehen HINTER dem " + REWRITE + " (Platz " + platzDesRewrites
                        + "), hoeren auf /* und kennen FORWARD nicht. Sie laufen damit auf KEINER "
                        + "Seite dieser Anwendung — genau der Defekt aus Karte 1280. Entweder "
                        + "setDispatcherTypes(REQUEST, FORWARD) setzen oder mit Begruendung in "
                        + "BEWUSST_NUR_REQUEST_HINTER_DEM_REWRITE eintragen: " + verdaechtig);
    }

    /**
     * Karte 1290: {@code rememberMeFilter} ist eine {@code Filter}-Bohne in
     * {@code PlaintextSecurityConfig} und wurde von Spring Boot deshalb <b>zusaetzlich</b> als
     * eigener Servlet-Filter auf {@code /*} angemeldet — am Ende der Kette und nur fuer
     * {@code REQUEST}. Die Wiedererkennung gehoert in die Sicherheitskette und steht dort auch;
     * die globale Anmeldung war nie beabsichtigt und ist seither abgeschaltet.
     */
    @Test
    @DisplayName("rememberMeFilter ist NICHT zusaetzlich als globaler Servlet-Filter angemeldet")
    void rememberMeFilterStehtNichtInDerServletkette() {
        List<String> treffer = inventar().stream()
                .map(Eintrag::name)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("rememberme"))
                .toList();

        assertTrue(treffer.isEmpty(),
                "Der RememberMeAuthenticationFilter steht als eigener Servlet-Filter in der "
                        + "Kette. Er gehoert ausschliesslich in die Sicherheitskette "
                        + "(.rememberMe(...)); global angemeldet laeuft er hinter dem Rewrite, "
                        + "also auf keiner Seite, und auf allen uebrigen Adressen erst NACH der "
                        + "Autorisierung (Karte 1290). Abschalten mit setEnabled(false): " + treffer);
    }
}
