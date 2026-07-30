/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;

import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended tests for MyRememberMeRepositoryRepository - all CRUD operations.
 */
@ExtendWith(MockitoExtension.class)
class MyRememberMeRepositoryRepositoryExtendedTest {

    @Mock
    private MyRememberMeRepository persistentLoginRepository;

    @InjectMocks
    private MyRememberMeRepositoryRepository repository;

    @Test
    void createNewToken_shouldSaveLogin() {
        Date now = new Date();
        PersistentRememberMeToken token = new PersistentRememberMeToken(
                "user@test.com", "series123", "tokenValue", now);

        repository.createNewToken(token);

        verify(persistentLoginRepository).save(argThat(login ->
                "series123".equals(login.getSeries()) &&
                "user@test.com".equals(login.getUsername()) &&
                "tokenValue".equals(login.getToken()) &&
                now.equals(login.getLastUsed())));
    }

    @Test
    void updateToken_shouldUpdateExistingLogin() {
        MyRememberMe existing = new MyRememberMe();
        existing.setSeries("series123");
        existing.setUsername("user@test.com");
        existing.setToken("oldToken");
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(existing));

        Date now = new Date();
        repository.updateToken("series123", "newToken", now);

        verify(persistentLoginRepository).save(argThat(login ->
                "newToken".equals(login.getToken()) &&
                now.equals(login.getLastUsed())));
    }

    @Test
    void updateToken_shouldDoNothing_whenNotFound() {
        when(persistentLoginRepository.findById("unknown")).thenReturn(Optional.empty());

        repository.updateToken("unknown", "newToken", new Date());

        verify(persistentLoginRepository, never()).save(any());
    }

    @Test
    void getTokenForSeries_shouldReturnToken_whenExists() {
        MyRememberMe login = new MyRememberMe();
        login.setSeries("series123");
        login.setUsername("user@test.com");
        login.setToken("tokenValue");
        login.setLastUsed(new Date());
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(login));

        PersistentRememberMeToken result = repository.getTokenForSeries("series123");

        assertNotNull(result);
        assertEquals("user@test.com", result.getUsername());
        assertEquals("series123", result.getSeries());
        assertEquals("tokenValue", result.getTokenValue());
    }

    @Test
    void getTokenForSeries_shouldReturnNull_whenNotFound() {
        when(persistentLoginRepository.findById("unknown")).thenReturn(Optional.empty());

        assertNull(repository.getTokenForSeries("unknown"));
    }

    @Test
    void removeUserTokens_shouldDeleteAll() {
        repository.removeUserTokens("user@test.com");

        verify(persistentLoginRepository).deleteAllByUsername("user@test.com");
    }
}
