/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.web;

import ch.plaintext.sessions.entity.UserSession;
import ch.plaintext.sessions.model.SessionAttribute;
import ch.plaintext.sessions.repository.UserSessionRepository;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionInspectorBackingBeanTest {

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private HttpSessionRegistry sessionRegistry;

    @Mock
    private HttpSession httpSession;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    private SessionInspectorBackingBean bean;

    private MockedStatic<FacesContext> facesContextMock;

    @BeforeEach
    void setUp() {
        bean = new SessionInspectorBackingBean(userSessionRepository, sessionRegistry);
        facesContextMock = mockStatic(FacesContext.class);
        facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
    }

    @AfterEach
    void tearDown() {
        facesContextMock.close();
    }

    @Test
    void constructorSetsFields() {
        assertThat(bean.getSessionAttributes()).isEmpty();
        assertThat(bean.getTotalSize()).isZero();
    }

    @Test
    void loadSessionAttributesWithSelectedSessionIdFromRegistry() {
        bean.setSelectedSessionId("sess-123");

        Enumeration<String> attrNames = Collections.enumeration(List.of("key1", "key2"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("key1")).thenReturn("value1");
        when(httpSession.getAttribute("key2")).thenReturn(42);

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(2);
        assertThat(bean.getFormattedTotalSize()).isNotNull();
    }

    @Test
    void loadSessionAttributesWithSessionNotInRegistry() {
        bean.setSelectedSessionId("nonexistent");
        when(sessionRegistry.getSession("nonexistent")).thenReturn(Optional.empty());

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getName()).isEqualTo("WARNUNG");
        assertThat(bean.getFormattedTotalSize()).isEqualTo("0 B");
    }

    @Test
    void loadSessionAttributesWithNullSelectedSessionIdUsesCurrentSession() {
        bean.setSelectedSessionId(null);

        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(httpSession);

        Enumeration<String> attrNames = Collections.enumeration(List.of("testKey"));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("testKey")).thenReturn("testVal");

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getName()).isEqualTo("testKey");
    }

    @Test
    void loadSessionAttributesWithNullSelectedSessionIdAndNoSession() {
        bean.setSelectedSessionId(null);

        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(null);

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getName()).isEqualTo("FEHLER");
        assertThat(bean.getFormattedTotalSize()).isEqualTo("0 B");
    }

    @Test
    void loadSessionAttributesWithEmptySelectedSessionIdAndNoSession() {
        bean.setSelectedSessionId("");

        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(null);

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getName()).isEqualTo("FEHLER");
    }

    @Test
    void loadSessionAttributesSortsLargestFirst() {
        bean.setSelectedSessionId("sess-123");

        Enumeration<String> attrNames = Collections.enumeration(List.of("small", "large"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("small")).thenReturn("a");
        when(httpSession.getAttribute("large")).thenReturn("a very long string value that should be larger in serialized form");

        bean.loadSessionAttributes();

        List<SessionAttribute> attrs = bean.getSessionAttributes();
        assertThat(attrs).hasSize(2);
        assertThat(attrs.get(0).getSizeInBytes()).isGreaterThanOrEqualTo(attrs.get(1).getSizeInBytes());
    }

    @Test
    void loadSessionAttributesHandlesNonSerializableObjects() {
        bean.setSelectedSessionId("sess-123");

        Object nonSerializable = new Object();
        Enumeration<String> attrNames = Collections.enumeration(List.of("obj"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("obj")).thenReturn(nonSerializable);

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getSizeInBytes()).isGreaterThan(0);
    }

    @Test
    void loadSessionAttributesHandlesNullAttributeValue() {
        bean.setSelectedSessionId("sess-123");

        Enumeration<String> attrNames = Collections.enumeration(List.of("nullKey"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("nullKey")).thenReturn(null);

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getSizeInBytes()).isZero();
    }

    @Test
    void onUserChangeCallsLoadSessionAttributes() {
        bean.setSelectedSessionId("nonexistent");
        when(sessionRegistry.getSession("nonexistent")).thenReturn(Optional.empty());

        bean.onUserChange();

        assertThat(bean.getSessionAttributes()).hasSize(1);
        assertThat(bean.getSessionAttributes().get(0).getName()).isEqualTo("WARNUNG");
    }

    @Test
    void refreshCallsLoadSessionAttributes() {
        bean.setSelectedSessionId("nonexistent");
        when(sessionRegistry.getSession("nonexistent")).thenReturn(Optional.empty());

        bean.refresh();

        assertThat(bean.getSessionAttributes()).hasSize(1);
    }

    @Test
    void userSessionInfoConstructorSetsFields() {
        SessionInspectorBackingBean.UserSessionInfo info =
                new SessionInspectorBackingBean.UserSessionInfo("abcdefghijklmnop", "admin");

        assertThat(info.getSessionId()).isEqualTo("abcdefghijklmnop");
        assertThat(info.getUsername()).isEqualTo("admin");
        assertThat(info.getDisplayName()).isEqualTo("admin (Session: abcdefgh...)");
    }

    @Test
    void userSessionInfoConstructorWithShortSessionId() {
        SessionInspectorBackingBean.UserSessionInfo info =
                new SessionInspectorBackingBean.UserSessionInfo("abc", "user");

        assertThat(info.getSessionId()).isEqualTo("abc");
        assertThat(info.getUsername()).isEqualTo("user");
        assertThat(info.getDisplayName()).isEqualTo("user (Session: abc...)");
    }

    @Test
    void loadSessionAttributesFormattedSizeKilobytes() {
        bean.setSelectedSessionId("sess-123");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("padding data to exceed 1024 bytes ");
        }
        String largeValue = sb.toString();

        Enumeration<String> attrNames = Collections.enumeration(List.of("large"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames);
        when(httpSession.getAttribute("large")).thenReturn(largeValue);

        bean.loadSessionAttributes();

        assertThat(bean.getFormattedTotalSize()).contains("KB");
    }

    @Test
    void loadActiveSessionsMapsRepositoryResults() {
        UserSession us1 = new UserSession();
        us1.setSessionId("sess-aaa");
        us1.setUsername("alice");

        UserSession us2 = new UserSession();
        us2.setSessionId("sess-bbb");
        us2.setUsername("bob");

        when(userSessionRepository.findAllByActive(true)).thenReturn(List.of(us1, us2));

        // FacesContext is mocked, getExternalContext returns mock for loadActiveSessions
        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-aaa");
        // loadSessionAttributes will be called, selectedSessionId will be "sess-aaa"
        when(sessionRegistry.getSession("sess-aaa")).thenReturn(Optional.of(httpSession));
        Enumeration<String> attrNames = Collections.enumeration(List.of());
        when(httpSession.getAttributeNames()).thenReturn(attrNames);

        bean.init();

        assertThat(bean.getActiveSessions()).hasSize(2);
        assertThat(bean.getActiveSessions().get(0).getUsername()).isEqualTo("alice");
        assertThat(bean.getActiveSessions().get(1).getUsername()).isEqualTo("bob");
    }

    @Test
    void loadActiveSessionsHandlesException() {
        when(userSessionRepository.findAllByActive(true)).thenThrow(new RuntimeException("DB error"));

        // loadSessionAttributes with null selectedSessionId will use FacesContext
        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(null);

        bean.init();

        assertThat(bean.getActiveSessions()).isEmpty();
    }

    @Test
    void loadSessionAttributesHandlesExceptionFromGetAttributeNames() {
        bean.setSelectedSessionId("sess-err");
        when(sessionRegistry.getSession("sess-err")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenThrow(new RuntimeException("Session invalidated"));

        bean.loadSessionAttributes();

        assertThat(bean.getSessionAttributes()).isEmpty();
    }

    @Test
    void loadSessionAttributesClearsExistingAttributesOnReload() {
        bean.setSelectedSessionId("sess-123");

        Enumeration<String> attrNames1 = Collections.enumeration(List.of("key1"));
        when(sessionRegistry.getSession("sess-123")).thenReturn(Optional.of(httpSession));
        when(httpSession.getAttributeNames()).thenReturn(attrNames1);
        when(httpSession.getAttribute("key1")).thenReturn("val1");

        bean.loadSessionAttributes();
        assertThat(bean.getSessionAttributes()).hasSize(1);

        // Second call should clear and reload
        Enumeration<String> attrNames2 = Collections.enumeration(List.of("key2", "key3"));
        when(httpSession.getAttributeNames()).thenReturn(attrNames2);
        when(httpSession.getAttribute("key2")).thenReturn("val2");
        when(httpSession.getAttribute("key3")).thenReturn("val3");

        bean.loadSessionAttributes();
        assertThat(bean.getSessionAttributes()).hasSize(2);
    }

    @Test
    void userSessionInfoSettersWork() {
        SessionInspectorBackingBean.UserSessionInfo info =
                new SessionInspectorBackingBean.UserSessionInfo("sess-id", "user");

        info.setSessionId("new-sess");
        info.setUsername("newuser");
        info.setDisplayName("custom display");

        assertThat(info.getSessionId()).isEqualTo("new-sess");
        assertThat(info.getUsername()).isEqualTo("newuser");
        assertThat(info.getDisplayName()).isEqualTo("custom display");
    }

    @Test
    void loadActiveSessionsWithEmptyList() {
        when(userSessionRepository.findAllByActive(true)).thenReturn(List.of());
        when(facesContext.getExternalContext()).thenReturn(externalContext);
        when(externalContext.getSession(false)).thenReturn(null);

        bean.init();

        assertThat(bean.getActiveSessions()).isEmpty();
    }
}
