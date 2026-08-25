/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Meldung Daniel, 25.08.2026: „Mein Konto" zeigte nach dem Impersonieren weiter den vorherigen
 * Benutzer. Ursache: {@code startImpersonation()} tauschte nur die {@code Authentication} und
 * liess die Sitzung unangetastet — der Kommentar dort versprach seit jeher ein Aufraeumen, das
 * im Code fehlte. Jede session-scoped Bean, die ihren Zustand einmalig im {@code @PostConstruct}
 * aufbaut, blieb damit beim alten Benutzer stehen.
 *
 * <p>Der heikle Teil ist nicht das Wegwerfen, sondern das <b>Nicht</b>-Wegwerfen: in derselben
 * Sitzung liegen der Spring-Security-Kontext, die Impersonation-Merker und der
 * JSF-Ansichtszustand. Wuerde die Aufraeumung pauschal leeren, waere der Benutzer abgemeldet oder
 * die laufende Ansicht zerstoert. Genau diese Grenze pruefen die beiden Faelle unten.
 */
class SessionBeansVerwerfenTest {

    private MockHttpServletRequest request;
    private PlaintextSecurityImpl security;

    /** Eine BeanFactory, in der genau die genannten Namen Scope „session" haben. */
    private static ObjectProvider<ConfigurableListableBeanFactory> factoryMit(String... sessionBeans) {
        ConfigurableListableBeanFactory factory = mock(ConfigurableListableBeanFactory.class);
        String[] alle = {"meinKontoBackingBean", "scopedTarget.userPreferencesBackingBean",
                "einSingleton", "einRequestBean"};
        when(factory.getBeanDefinitionNames()).thenReturn(alle);
        for (String name : alle) {
            BeanDefinition bd = mock(BeanDefinition.class);
            boolean sessionScope = java.util.Arrays.asList(sessionBeans).contains(name);
            when(bd.getScope()).thenReturn(sessionScope ? "session" : "singleton");
            when(factory.getBeanDefinition(name)).thenReturn(bd);
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<ConfigurableListableBeanFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        return provider;
    }

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        security = new PlaintextSecurityImpl(null, null, null, null,
                factoryMit("meinKontoBackingBean", "scopedTarget.userPreferencesBackingBean"));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("Beide Ablageformen einer session-scoped Bean werden verworfen")
    void sessionBeansWerdenVerworfen() {
        request.getSession().setAttribute("meinKontoBackingBean", "Daten von Daniel");
        request.getSession().setAttribute("scopedTarget.userPreferencesBackingBean", "Einstellungen");

        security.verwerfeSessionBeans();

        assertThat(request.getSession().getAttribute("meinKontoBackingBean"))
                .as("einfacher Bean-Name (Scope ohne Proxy)").isNull();
        assertThat(request.getSession().getAttribute("scopedTarget.userPreferencesBackingBean"))
                .as("scopedTarget-Name (Scope MIT Proxy)").isNull();
    }

    /**
     * Die eigentliche Zusicherung. Ohne sie waere {@code session.invalidate()} eine gruene,
     * aber katastrophale Loesung: der Benutzer waere nach dem Impersonieren abgemeldet.
     */
    @Test
    @DisplayName("Sicherheitskontext, Impersonation-Merker und JSF-Zustand bleiben unberuehrt")
    void fremdeAttributeBleibenStehen() {
        request.getSession().setAttribute("SPRING_SECURITY_CONTEXT", "der angemeldete Benutzer");
        request.getSession().setAttribute("impersonation.originalAuth", "der echte Admin");
        request.getSession().setAttribute("com.sun.faces.application.view.activeViewMaps", "Ansichten");
        request.getSession().setAttribute("einSingleton", "kein Session-Bean");
        request.getSession().setAttribute("meinKontoBackingBean", "Daten von Daniel");

        security.verwerfeSessionBeans();

        assertThat(request.getSession().getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
        assertThat(request.getSession().getAttribute("impersonation.originalAuth")).isNotNull();
        assertThat(request.getSession().getAttribute("com.sun.faces.application.view.activeViewMaps")).isNotNull();
        assertThat(request.getSession().getAttribute("einSingleton")).isNotNull();
        // Positivkontrolle im selben Fall: die Aufraeumung hat ueberhaupt etwas getan.
        assertThat(request.getSession().getAttribute("meinKontoBackingBean")).isNull();
    }

    @Test
    @DisplayName("Ohne Request-Kontext passiert nichts - und es fliegt nichts")
    void ohneRequestKontextKeinFehler() {
        RequestContextHolder.resetRequestAttributes();

        security.verwerfeSessionBeans();
    }

    @Test
    @DisplayName("Ohne BeanFactory passiert nichts - und es fliegt nichts")
    void ohneBeanFactoryKeinFehler() {
        PlaintextSecurityImpl ohne = new PlaintextSecurityImpl(null, null, null, null, null);
        request.getSession().setAttribute("meinKontoBackingBean", "Daten");

        ohne.verwerfeSessionBeans();

        assertThat(request.getSession().getAttribute("meinKontoBackingBean")).isNotNull();
    }
}
