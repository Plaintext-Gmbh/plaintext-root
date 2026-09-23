/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.testsupport.EmbeddedPg;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1290 — misst an echten HTTP-Anfragen, welche Filter auf einer Seite <b>wirklich
 * laufen</b>. {@link FilterDispatcherInventarTest} sagt, was angemeldet ist; diese Klasse sagt,
 * was ausgefuehrt wird.
 *
 * <h2>Positivkontrolle — der Kern dieser Karte</h2>
 *
 * <p>Eine leere Trefferliste belegt sonst nur, dass die Messung nicht funktioniert hat. Deshalb
 * laufen zwei <b>Sonden</b> mit: beide auf {@code /*}, beide hinter dem Rewrite angemeldet,
 * unterschieden <b>allein</b> durch die Dispatcher-Typen.</p>
 *
 * <ul>
 *   <li>{@link #SONDE_REQUEST_UND_FORWARD} <b>muss</b> auf {@code /login.html} erscheinen —
 *       ein Filter, von dem bekannt ist, dass er laufen muss, erscheint als laufend.</li>
 *   <li>{@link #SONDE_NUR_REQUEST} darf auf {@code /login.html} <b>nicht</b> erscheinen —
 *       der Defekt aus Karte 1280, im selben Lauf nachgestellt.</li>
 *   <li>Auf einer Adresse <b>ohne</b> {@code .html} muessen <b>beide</b> erscheinen. Ohne diese
 *       dritte Probe belegte das Fehlen oben nur, dass die erste Sonde falsch verdrahtet ist.</li>
 * </ul>
 *
 * <p>Erst wenn diese drei Aussagen stehen, sind die Messwerte fuer die echten Filter darunter
 * etwas wert.</p>
 *
 * <h2>Was der Messaufbau am Messgegenstand veraendert — und was nicht</h2>
 *
 * <p>Vor jeden {@code FilterRegistrationBean} wird ein Mitschreiber gehaengt. Spring Boot leitet
 * die Dispatcher-Typen aus der Filterklasse ab, wenn sie nicht gesetzt sind; ein Mitschreiber,
 * der selbst nur ein einfacher {@code Filter} ist, wuerde diese Ableitung veraendern. Der
 * abgeleitete Wert wird darum <b>vor</b> dem Umhaengen gelesen und danach explizit gesetzt.</p>
 *
 * <p>Nicht zu vermeiden ist, dass Spring Boot die blosse {@code Filter}-Bohne hinter einer so
 * umgehaengten Registrierung nicht mehr wiedererkennt und ein zweites Mal anmeldet. Deshalb
 * steht das <b>Inventar</b> in einer eigenen Klasse ohne Mitschreiber; hier zaehlt nur, was
 * ausgefuehrt wurde.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", FilterLaufMessungTest.PROFIL})
class FilterLaufMessungTest {

    /**
     * Der Messaufbau haengt an einem eigenen Profil.
     *
     * <p><b>Warum:</b> {@code RootBootApplication} traegt ein ausdrueckliches
     * {@code @ComponentScan(basePackages = "ch.plaintext")}. Ein solcher Scan bringt den
     * {@code TypeExcludeFilter} von {@code @SpringBootApplication} nicht mit — damit werden
     * {@code @TestConfiguration}-Klassen unterhalb von {@code ch.plaintext} in JEDEN
     * Testkontext dieses Moduls gezogen, nicht nur in den ihrer eigenen Testklasse. Ohne das
     * Profil liefen der Mitschreiber und die beiden Sonden in allen Integrationstests mit, und
     * der Mitschreiber liess Spring Boot die blossen {@code Filter}-Bohnen ein zweites Mal
     * anmelden — {@code rateLimitFilter} zaehlte dann doppelt und
     * {@code TotpLoginIntegrationTest} lief in HTTP 429.</p>
     */
    static final String PROFIL = "filterlaufmessung";

    /** Sondenplatz: hinter dem Rewrite ({@code HIGHEST_PRECEDENCE + 30}), vor der Sicherheitskette (-100). */
    static final int SONDEN_ORDER = -200;

    static final String SONDE_NUR_REQUEST = "sondeNurRequest";
    static final String SONDE_REQUEST_UND_FORWARD = "sondeRequestUndForward";

    /** Eine Seite ohne Anmeldung — {@code /login.html} ist permitAll. */
    static final String SEITE = "/login.html";

    /** Eine Adresse ohne {@code .html}: kein Rewrite, kein forward. {@code /nosec/**} ist permitAll. */
    static final String OHNE_HTML = "/nosec/gibt-es-nicht-1290";

    /** Mitschrift: {@code beanName|DISPATCHERTYPE|uri}, in Ausfuehrungsreihenfolge. */
    static final List<String> LAEUFE = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "filterlaufmessungtest");
    }

    @LocalServerPort
    private int port;

    // ------------------------------------------------------------------ Messaufbau

    @TestConfiguration(proxyBeanMethods = false)
    @Profile(PROFIL)
    static class Messaufbau {

        @Bean
        static BeanPostProcessor filterMitschreiber() {
            return new BeanPostProcessor() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof FilterRegistrationBean<?> registrierung)) {
                        return bean;
                    }
                    Filter original = registrierung.getFilter();
                    if (original == null || original instanceof Mitschreiber) {
                        return bean;
                    }
                    // Erst lesen, dann umhaengen: sonst misst der Aufbau sich selbst.
                    EnumSet<DispatcherType> typen = registrierung.determineDispatcherTypes();
                    ((FilterRegistrationBean) registrierung).setFilter(new Mitschreiber(beanName, original));
                    registrierung.setDispatcherTypes(typen);
                    return bean;
                }
            };
        }

        @Bean
        FilterRegistrationBean<Filter> sondeNurRequest() {
            return sonde(DispatcherType.REQUEST);
        }

        @Bean
        FilterRegistrationBean<Filter> sondeRequestUndForward() {
            return sonde(DispatcherType.REQUEST, DispatcherType.FORWARD);
        }

        private FilterRegistrationBean<Filter> sonde(DispatcherType erster, DispatcherType... weitere) {
            Filter durchlass = new Filter() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    chain.doFilter(request, response);
                }
            };
            FilterRegistrationBean<Filter> registrierung = new FilterRegistrationBean<>(durchlass);
            registrierung.addUrlPatterns("/*");
            registrierung.setDispatcherTypes(erster, weitere);
            registrierung.setOrder(SONDEN_ORDER);
            return registrierung;
        }
    }

    /** Notiert jeden Durchlauf und reicht unveraendert weiter. */
    record Mitschreiber(String name, Filter delegat) implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String uri = request instanceof HttpServletRequest http ? http.getRequestURI() : "?";
            LAEUFE.add(name + "|" + request.getDispatcherType() + "|" + uri);
            delegat.doFilter(request, response, chain);
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            delegat.init(filterConfig);
        }

        @Override
        public void destroy() {
            delegat.destroy();
        }
    }

    // ------------------------------------------------------------------ Messung

    private List<String> hole(String pfad) {
        LAEUFE.clear();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        ResponseEntity<String> antwort =
                template.exchange("http://localhost:" + port + pfad, HttpMethod.GET, null, String.class);
        List<String> mitschrift = new ArrayList<>(LAEUFE);
        System.out.println("### " + pfad + " -> HTTP " + antwort.getStatusCode().value());
        mitschrift.forEach(z -> System.out.println("    " + z));
        return mitschrift;
    }

    private static boolean lief(List<String> mitschrift, String name, DispatcherType typ) {
        return mitschrift.stream().anyMatch(z -> z.startsWith(name + "|" + typ + "|"));
    }

    private static boolean lief(List<String> mitschrift, String name) {
        return mitschrift.stream().anyMatch(z -> z.startsWith(name + "|"));
    }

    // ------------------------------------------------------------------ Positivkontrolle

    @Test
    @DisplayName("Positivkontrolle: dieselbe Sonde laeuft mit FORWARD und ohne ihn nicht")
    void positivkontrolleDerMessung() {
        List<String> seite = hole(SEITE);

        assertTrue(lief(seite, "htmlRewriteFilter", DispatcherType.REQUEST),
                "Der Rewrite selbst wurde nicht mitgeschrieben — dann misst dieser Aufbau gar "
                        + "nichts, und jede leere Trefferliste unten waere wertlos. "
                        + "Mitschrift: " + seite);

        assertTrue(lief(seite, SONDE_REQUEST_UND_FORWARD, DispatcherType.FORWARD),
                "Die Sonde mit REQUEST+FORWARD lief auf " + SEITE + " nicht mit. Damit ist der "
                        + "Messaufbau kaputt, nicht der Befund. Mitschrift: " + seite);

        assertFalse(lief(seite, SONDE_NUR_REQUEST),
                "Die Sonde mit NUR REQUEST lief auf " + SEITE + " mit — dann gibt es den "
                        + "Mechanismus aus Karte 1280 hier nicht (mehr) und die Aussagen dieser "
                        + "Klasse sind neu zu bewerten. Mitschrift: " + seite);

        List<String> ohneHtml = hole(OHNE_HTML);

        assertTrue(lief(ohneHtml, SONDE_NUR_REQUEST, DispatcherType.REQUEST),
                "Die Sonde mit NUR REQUEST lief auch auf einer Adresse ohne .html nicht — sie ist "
                        + "schlicht falsch verdrahtet, und ihr Fehlen oben belegt nichts. "
                        + "Mitschrift: " + ohneHtml);
        assertTrue(lief(ohneHtml, SONDE_REQUEST_UND_FORWARD, DispatcherType.REQUEST),
                "Mitschrift: " + ohneHtml);
    }

    /**
     * Die Reihenfolge, in der {@code FilterDispatcherInventarTest} die Filter auflistet, ist
     * Tomcats {@code findFilterMaps()}-Reihenfolge. Dass das wirklich die Ausfuehrungsreihenfolge
     * ist — und die Aussage „steht hinter dem Rewrite" damit traegt —, wird hier nachgemessen.
     */
    @Test
    @DisplayName("Der Rewrite laeuft nach der Drossel und vor allem, was ihm folgt")
    void reihenfolgeDerKetteStimmtMitDemInventarUeberein() {
        List<String> seite = hole(SEITE);
        List<String> namen = seite.stream().map(z -> z.substring(0, z.indexOf('|'))).toList();

        int drossel = namen.indexOf("rateLimitFilterRegistration");
        int rewrite = namen.indexOf("htmlRewriteFilter");
        int sonde = namen.indexOf(SONDE_REQUEST_UND_FORWARD);

        assertTrue(drossel >= 0 && rewrite > drossel,
                "Die Drossel muss VOR dem Rewrite laufen (HIGHEST_PRECEDENCE gegen +30). "
                        + "Mitschrift: " + seite);
        assertTrue(sonde > rewrite,
                "Die Sonde (order -200) muss NACH dem Rewrite laufen. Mitschrift: " + seite);
    }

    // ------------------------------------------------------------------ Die einzelnen Filter

    @Test
    @DisplayName("RateLimitFilter: laeuft auf einer .html-Seite — gemessen, nicht vermutet")
    void rateLimitFilterLaeuftAufDerSeite() {
        List<String> seite = hole(SEITE);

        assertTrue(lief(seite, "rateLimitFilterRegistration", DispatcherType.REQUEST),
                "Die Drossel lief auf " + SEITE + " nicht. Sie steht auf HIGHEST_PRECEDENCE und "
                        + "muss die .html-Adresse vor dem Rewrite sehen; laeuft sie nicht, ist "
                        + "die Begrenzung auf Seiten wirkungslos (Karte 303/1290). "
                        + "Mitschrift: " + seite);

        assertFalse(lief(seite, "rateLimitFilterRegistration", DispatcherType.FORWARD),
                "Die Drossel lief zusaetzlich auf dem FORWARD — dann zaehlt jede Seite doppelt "
                        + "gegen das Kontingent. Genau darum steht dort REQUEST allein. "
                        + "Mitschrift: " + seite);
    }

    @Test
    @DisplayName("WatchTokenSitzungFilter: laeuft seit Karte 1280 auf der Seite — auf dem FORWARD")
    void watchTokenFilterLaeuftAufDerSeite() {
        List<String> seite = hole(SEITE);

        assertTrue(lief(seite, "watchTokenSitzungFilterRegistration", DispatcherType.FORWARD),
                "Der Filter aus Karte 1280 lief auf " + SEITE + " nicht — die Einsperrung des "
                        + "Handy-Links greift damit wieder auf keiner Seite. Mitschrift: " + seite);
    }

    @Test
    @DisplayName("MaintenanceModeFilter: laeuft auf einer .html-Seite (Karte 1290)")
    void maintenanceModeFilterLaeuftAufDerSeite() {
        List<String> seite = hole(SEITE);

        assertTrue(lief(seite, "maintenanceModeFilterRegistration"),
                "Der Wartungsmodus lief auf " + SEITE + " nicht mit. Dann laesst ein "
                        + "eingeschalteter Wartungsmodus jede Seite der Anwendung weiterhin durch "
                        + "und sperrt nur Adressen ohne .html (Karte 1290). "
                        + "Mitschrift: " + seite);
    }

    @Test
    @DisplayName("SessionTrackingFilter: laeuft auf einer .html-Seite (Karte 1290)")
    void sessionTrackingFilterLaeuftAufDerSeite() {
        List<String> seite = hole(SEITE);

        assertTrue(lief(seite, "sessionTrackingFilterRegistration"),
                "Die Sitzungsaufzeichnung lief auf " + SEITE + " nicht mit. Dann sieht sie nur "
                        + "Anfragen ohne .html — statische Dateien und REST — und das "
                        + "Sitzungsregister, die Grundlage fuer das zwangsweise Beenden einer "
                        + "Sitzung, bekommt eine Sitzung nur zufaellig zu Gesicht (Karte 1290). "
                        + "Mitschrift: " + seite);
    }
}
