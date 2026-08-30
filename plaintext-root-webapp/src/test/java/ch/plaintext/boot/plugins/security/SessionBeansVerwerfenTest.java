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
 * Report by Daniel, 25.08.2026: "Mein Konto" kept showing the previous user after impersonating.
 * Cause: {@code startImpersonation()} only exchanged the {@code Authentication} and
 * left the session untouched — the comment there had always promised a cleanup that
 * was missing from the code. Every session-scoped bean that builds up its state once in
 * {@code @PostConstruct} therefore stayed with the old user.
 *
 * <p>The delicate part is not the discarding but the <b>not</b> discarding: the same session holds
 * the Spring Security context, the impersonation markers and the
 * JSF view state. If the cleanup emptied everything indiscriminately, the user would be logged out or
 * the running view destroyed. Exactly that boundary is what the two cases below check.
 */
class SessionBeansVerwerfenTest {

    private MockHttpServletRequest request;
    private PlaintextSecurityImpl security;

    /** A BeanFactory in which exactly the named beans have scope "session". */
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
     * The actual guarantee. Without it {@code session.invalidate()} would be a green
     * but catastrophic solution: the user would be logged out after impersonating.
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
        // Positive control in the same case: the cleanup did anything at all.
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
