/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import ch.plaintext.sessions.entity.UserSession;
import ch.plaintext.sessions.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionAuditServiceImplTest {

    @Mock
    private UserSessionRepository repository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private SessionAuditServiceImpl service;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        // entityManager is injected via @PersistenceContext (field injection)
    }

    @Test
    void updateOrCreateWithNullUserIdDoesNothing() {
        service.updateOrCreate(null, "sess-1", authentication, "ua");
        verifyNoInteractions(repository);
    }

    @Test
    void updateOrCreateWithNullSessionIdDoesNothing() {
        service.updateOrCreate(1L, null, authentication, "ua");
        verifyNoInteractions(repository);
    }

    @Test
    void updateOrCreateCallsUpsertForExistingSession() {
        when(authentication.getName()).thenReturn("testuser");
        Collection<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("PROPERTY_MANDAT_testMandat")
        );
        doReturn(authorities).when(authentication).getAuthorities();

        service.updateOrCreate(42L, "sess-1", authentication, "ua");

        verify(repository).upsertSession(eq(42L), eq("testuser"), eq("sess-1"),
                eq("testMandat"), eq("ua"), any(LocalDateTime.class));
    }

    @Test
    void updateOrCreateCallsUpsertWithCorrectArguments() {
        when(authentication.getName()).thenReturn("testuser");

        Collection<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("PROPERTY_MANDAT_testMandat")
        );
        doReturn(authorities).when(authentication).getAuthorities();

        service.updateOrCreate(42L, "sess-new", authentication, "TestAgent/1.0");

        verify(repository).upsertSession(eq(42L), eq("testuser"), eq("sess-new"),
                eq("testMandat"), eq("TestAgent/1.0"), any(LocalDateTime.class));
        verify(repository, never()).save(any(UserSession.class));
        verify(repository, never()).delete(any(UserSession.class));
    }

    @Test
    void updateOrCreateSetsUnknownMandatWhenNoMatchingAuthority() {
        when(authentication.getName()).thenReturn("testuser");

        Collection<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        doReturn(authorities).when(authentication).getAuthorities();

        service.updateOrCreate(42L, "sess-new", authentication, "ua");

        verify(repository).upsertSession(eq(42L), eq("testuser"), eq("sess-new"),
                eq("unknown"), eq("ua"), any(LocalDateTime.class));
    }

    @Test
    void updateOrCreateSetsUnknownUsernameWhenAuthenticationIsNull() {
        service.updateOrCreate(42L, "sess-new", null, "ua");

        verify(repository).upsertSession(eq(42L), eq("unknown"), eq("sess-new"),
                eq("unknown"), eq("ua"), any(LocalDateTime.class));
    }

    @Test
    void updateOrCreateUsesEmptyStringForNullUserAgent() {
        when(authentication.getName()).thenReturn("testuser");
        Collection<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("PROPERTY_MANDAT_testMandat")
        );
        doReturn(authorities).when(authentication).getAuthorities();

        service.updateOrCreate(42L, "sess-1", authentication, null);

        verify(repository).upsertSession(eq(42L), eq("testuser"), eq("sess-1"),
                eq("testMandat"), eq(""), any(LocalDateTime.class));
    }

    @Test
    void commitWithNullUserIdDoesNothing() {
        service.commit(null);
        verifyNoInteractions(repository);
    }

    @Test
    void commitDeactivatesAllActiveSessionsForUser() {
        UserSession s1 = new UserSession();
        s1.setId(1L);
        s1.setActive(true);

        UserSession s2 = new UserSession();
        s2.setId(2L);
        s2.setActive(true);

        when(repository.findByUserIdAndActive(42L, true)).thenReturn(List.of(s1, s2));
        when(repository.save(any(UserSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.commit(42L);

        assertThat(s1.getActive()).isFalse();
        assertThat(s2.getActive()).isFalse();
        verify(repository, times(2)).save(any(UserSession.class));
    }

    @Test
    void getAllActiveSessionsReturnsActiveSessions() {
        UserSession s1 = new UserSession();
        s1.setActive(true);
        when(repository.findAllByActive(true)).thenReturn(List.of(s1));

        List<UserSession> result = service.getAllActiveSessions();
        assertThat(result).hasSize(1);
    }

    @Test
    void getAllActiveSessionsReturnsEmptyListOnException() {
        when(repository.findAllByActive(true)).thenThrow(new RuntimeException("DB error"));

        List<UserSession> result = service.getAllActiveSessions();
        assertThat(result).isEmpty();
    }

    @Test
    void getActiveSessionsByMandatReturnsSessionsForMandat() {
        UserSession s1 = new UserSession();
        when(repository.findByMandatAndActive("testMandat", true)).thenReturn(List.of(s1));

        List<UserSession> result = service.getActiveSessionsByMandat("testMandat");
        assertThat(result).hasSize(1);
    }

    @Test
    void getActiveSessionsByMandatReturnsEmptyForNullMandat() {
        List<UserSession> result = service.getActiveSessionsByMandat(null);
        assertThat(result).isEmpty();
    }

    @Test
    void getActiveSessionsByMandatReturnsEmptyForEmptyMandat() {
        List<UserSession> result = service.getActiveSessionsByMandat("  ");
        assertThat(result).isEmpty();
    }

    @Test
    void forceLogoutDeactivatesSession() {
        UserSession session = new UserSession();
        session.setActive(true);
        session.setUsername("admin");

        when(repository.findBySessionId("sess-1")).thenReturn(List.of(session));
        when(repository.save(any(UserSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.forceLogout("sess-1");

        assertThat(session.getActive()).isFalse();
        verify(repository).save(session);
    }

    @Test
    void forceLogoutWithNullSessionIdDoesNothing() {
        service.forceLogout(null);
        verifyNoInteractions(repository);
    }

    @Test
    void forceLogoutWithEmptySessionIdDoesNothing() {
        service.forceLogout("  ");
        verifyNoInteractions(repository);
    }

    @Test
    void forceLogoutWhenSessionNotFoundDoesNotThrow() {
        when(repository.findBySessionId("nonexistent")).thenReturn(Collections.emptyList());

        service.forceLogout("nonexistent");

        verify(repository, never()).save(any());
    }
}
