/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Test class for MyRememberMeRepositoryRepository - Spring Security Remember-Me token persistence.
 */
@ExtendWith(MockitoExtension.class)
class MyRememberMeRepositoryRepositoryTest {

    @Mock
    private MyRememberMeRepository persistentLoginRepository;

    @InjectMocks
    private MyRememberMeRepositoryRepository rememberMeRepository;

    private PersistentRememberMeToken testToken;
    private MyRememberMe testRememberMe;
    private Date testDate;

    @BeforeEach
    void setUp() {
        testDate = new Date();

        // Setup test token
        testToken = new PersistentRememberMeToken(
                "testuser@example.com",
                "series123",
                "tokenValue456",
                testDate
        );

        // Setup test remember-me entity
        testRememberMe = new MyRememberMe();
        testRememberMe.setSeries("series123");
        testRememberMe.setUsername("testuser@example.com");
        testRememberMe.setToken("tokenValue456");
        testRememberMe.setLastUsed(testDate);
    }

    // ==================== createNewToken() Tests ====================

    @Test
    void createNewToken_shouldSaveNewTokenToRepository() {
        // Given: Repository will save the token
        when(persistentLoginRepository.save(any(MyRememberMe.class))).thenReturn(testRememberMe);

        // When
        rememberMeRepository.createNewToken(testToken);

        // Then: Verify save was called
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());

        MyRememberMe saved = captor.getValue();
        assertEquals("series123", saved.getSeries());
        assertEquals("testuser@example.com", saved.getUsername());
        assertEquals("tokenValue456", saved.getToken());
        assertEquals(testDate, saved.getLastUsed());
    }

    @Test
    void createNewToken_shouldMapAllFieldsCorrectly() {
        // Given
        Date customDate = new Date(System.currentTimeMillis() - 10000);
        PersistentRememberMeToken customToken = new PersistentRememberMeToken(
                "custom@user.com",
                "customSeries",
                "customToken",
                customDate
        );

        // When
        rememberMeRepository.createNewToken(customToken);

        // Then
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());

        MyRememberMe saved = captor.getValue();
        assertEquals("customSeries", saved.getSeries());
        assertEquals("custom@user.com", saved.getUsername());
        assertEquals("customToken", saved.getToken());
        assertEquals(customDate, saved.getLastUsed());
    }

    @Test
    void createNewToken_shouldHandleSpecialCharactersInUsername() {
        // Given: Username with special characters
        PersistentRememberMeToken specialToken = new PersistentRememberMeToken(
                "user+test@example.com",
                "series123",
                "token123",
                testDate
        );

        // When
        rememberMeRepository.createNewToken(specialToken);

        // Then
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());
        assertEquals("user+test@example.com", captor.getValue().getUsername());
    }

    @Test
    void createNewToken_shouldHandleLongTokenValues() {
        // Given: Very long token value
        String longToken = "a".repeat(500);
        PersistentRememberMeToken tokenWithLongValue = new PersistentRememberMeToken(
                "user@example.com",
                "series123",
                longToken,
                testDate
        );

        // When
        rememberMeRepository.createNewToken(tokenWithLongValue);

        // Then
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());
        assertEquals(longToken, captor.getValue().getToken());
    }

    // ==================== updateToken() Tests ====================

    @Test
    void updateToken_shouldUpdateExistingToken() {
        // Given: Existing token in repository
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));
        when(persistentLoginRepository.save(any(MyRememberMe.class))).thenReturn(testRememberMe);

        Date newDate = new Date(System.currentTimeMillis() + 5000);
        String newTokenValue = "newTokenValue789";

        // When
        rememberMeRepository.updateToken("series123", newTokenValue, newDate);

        // Then: Verify token was updated
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());

        MyRememberMe updated = captor.getValue();
        assertEquals(newTokenValue, updated.getToken());
        assertEquals(newDate, updated.getLastUsed());
        assertEquals("series123", updated.getSeries()); // Series should not change
        assertEquals("testuser@example.com", updated.getUsername()); // Username should not change
    }

    @Test
    void updateToken_shouldDoNothingWhenSeriesNotFound() {
        // Given: No token found for series
        when(persistentLoginRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // When
        rememberMeRepository.updateToken("nonexistent", "newToken", new Date());

        // Then: Save should not be called
        verify(persistentLoginRepository, never()).save(any());
    }

    @Test
    void updateToken_shouldHandleNullLastUsedDate() {
        // Given
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));

        // When: Update with null date
        rememberMeRepository.updateToken("series123", "newToken", null);

        // Then
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());
        assertNull(captor.getValue().getLastUsed());
    }

    @Test
    void updateToken_shouldOnlyUpdateTokenAndDate() {
        // Given: Token with specific username and series
        MyRememberMe original = new MyRememberMe();
        original.setSeries("series123");
        original.setUsername("original@user.com");
        original.setToken("oldToken");
        original.setLastUsed(testDate);

        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(original));

        Date newDate = new Date();

        // When
        rememberMeRepository.updateToken("series123", "updatedToken", newDate);

        // Then: Only token and date should change
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());

        MyRememberMe updated = captor.getValue();
        assertEquals("series123", updated.getSeries()); // Unchanged
        assertEquals("original@user.com", updated.getUsername()); // Unchanged
        assertEquals("updatedToken", updated.getToken()); // Changed
        assertEquals(newDate, updated.getLastUsed()); // Changed
    }

    // ==================== getTokenForSeries() Tests ====================

    @Test
    void getTokenForSeries_shouldReturnTokenWhenExists() {
        // Given: Token exists in repository
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));

        // When
        PersistentRememberMeToken result = rememberMeRepository.getTokenForSeries("series123");

        // Then
        assertNotNull(result);
        assertEquals("testuser@example.com", result.getUsername());
        assertEquals("series123", result.getSeries());
        assertEquals("tokenValue456", result.getTokenValue());
        assertEquals(testDate, result.getDate());
    }

    @Test
    void getTokenForSeries_shouldReturnNullWhenNotFound() {
        // Given: No token for series
        when(persistentLoginRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // When
        PersistentRememberMeToken result = rememberMeRepository.getTokenForSeries("nonexistent");

        // Then
        assertNull(result);
    }

    @Test
    void getTokenForSeries_shouldHandleNullSeries() {
        // Given: Null series
        when(persistentLoginRepository.findById(null)).thenReturn(Optional.empty());

        // When
        PersistentRememberMeToken result = rememberMeRepository.getTokenForSeries(null);

        // Then
        assertNull(result);
        verify(persistentLoginRepository).findById(null);
    }

    @Test
    void getTokenForSeries_shouldMapAllFieldsCorrectly() {
        // Given: Token with all fields populated
        MyRememberMe fullToken = new MyRememberMe();
        fullToken.setSeries("fullSeries");
        fullToken.setUsername("full@user.com");
        fullToken.setToken("fullTokenValue");
        Date specificDate = new Date(1234567890000L);
        fullToken.setLastUsed(specificDate);

        when(persistentLoginRepository.findById("fullSeries")).thenReturn(Optional.of(fullToken));

        // When
        PersistentRememberMeToken result = rememberMeRepository.getTokenForSeries("fullSeries");

        // Then: All fields should be correctly mapped
        assertNotNull(result);
        assertEquals("full@user.com", result.getUsername());
        assertEquals("fullSeries", result.getSeries());
        assertEquals("fullTokenValue", result.getTokenValue());
        assertEquals(specificDate, result.getDate());
    }

    // ==================== removeUserTokens() Tests ====================

    @Test
    void removeUserTokens_shouldDeleteTokenWhenUserExists() {
        // Given: User token exists

        // When
        rememberMeRepository.removeUserTokens("testuser@example.com");

        // Then: deleteAllByUsername should be called
        verify(persistentLoginRepository).deleteAllByUsername("testuser@example.com");
    }

    @Test
    void removeUserTokens_shouldDoNothingWhenUserNotFound() {
        // Given: No token for user

        // When
        rememberMeRepository.removeUserTokens("nonexistent@user.com");

        // Then: deleteAllByUsername should still be called (no-op if no tokens exist)
        verify(persistentLoginRepository).deleteAllByUsername("nonexistent@user.com");
    }

    @Test
    void removeUserTokens_shouldHandleNullUsername() {
        // Given: Null username

        // When
        rememberMeRepository.removeUserTokens(null);

        // Then: Should handle gracefully
        verify(persistentLoginRepository).deleteAllByUsername(null);
    }

    @Test
    void removeUserTokens_shouldHandleEmptyUsername() {
        // Given: Empty username

        // When
        rememberMeRepository.removeUserTokens("");

        // Then
        verify(persistentLoginRepository).deleteAllByUsername("");
    }

    @Test
    void removeUserTokens_shouldHandleCaseExactly() {
        // Given: Case-sensitive username lookup

        // When
        rememberMeRepository.removeUserTokens("User@Example.Com");

        // Then: Should use exact case
        verify(persistentLoginRepository).deleteAllByUsername("User@Example.Com");
    }

    // ==================== Integration Scenarios ====================

    @Test
    void fullRememberMeFlow_shouldWorkEndToEnd() {
        // Scenario: Create, update, retrieve, and remove a token

        // 1. Create new token
        when(persistentLoginRepository.save(any(MyRememberMe.class))).thenReturn(testRememberMe);
        rememberMeRepository.createNewToken(testToken);
        verify(persistentLoginRepository, times(1)).save(any(MyRememberMe.class));

        // 2. Update token
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));
        Date newDate = new Date();
        rememberMeRepository.updateToken("series123", "updatedToken", newDate);
        verify(persistentLoginRepository, times(2)).save(any(MyRememberMe.class));

        // 3. Retrieve token
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));
        PersistentRememberMeToken retrieved = rememberMeRepository.getTokenForSeries("series123");
        assertNotNull(retrieved);

        // 4. Remove token
        rememberMeRepository.removeUserTokens("testuser@example.com");
        verify(persistentLoginRepository).deleteAllByUsername("testuser@example.com");
    }

    @Test
    void multipleTokensForDifferentUsers_shouldBeIndependent() {
        // Given: Tokens for different users
        MyRememberMe user1Token = new MyRememberMe();
        user1Token.setSeries("series1");
        user1Token.setUsername("user1@example.com");

        MyRememberMe user2Token = new MyRememberMe();
        user2Token.setSeries("series2");
        user2Token.setUsername("user2@example.com");

        // When: Remove user1's tokens
        rememberMeRepository.removeUserTokens("user1@example.com");

        // Then: Only user1's tokens should be deleted
        verify(persistentLoginRepository).deleteAllByUsername("user1@example.com");
    }

    @Test
    void concurrentTokenUpdates_shouldWorkCorrectly() {
        // Simulate concurrent updates (though not truly concurrent in test)
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));

        Date date1 = new Date(System.currentTimeMillis());
        Date date2 = new Date(System.currentTimeMillis() + 1000);

        // When: Two updates
        rememberMeRepository.updateToken("series123", "token1", date1);
        rememberMeRepository.updateToken("series123", "token2", date2);

        // Then: Both updates should have been processed
        verify(persistentLoginRepository, times(2)).findById("series123");
        verify(persistentLoginRepository, times(2)).save(any(MyRememberMe.class));
    }

    // ==================== Edge Cases ====================

    @Test
    void getTokenForSeries_shouldHandleEmptyString() {
        // Given
        when(persistentLoginRepository.findById("")).thenReturn(Optional.empty());

        // When
        PersistentRememberMeToken result = rememberMeRepository.getTokenForSeries("");

        // Then
        assertNull(result);
    }

    @Test
    void createNewToken_shouldHandleTokenWithNullDate() {
        // Given: Token with null date
        PersistentRememberMeToken nullDateToken = new PersistentRememberMeToken(
                "user@example.com",
                "series",
                "token",
                null
        );

        // When
        rememberMeRepository.createNewToken(nullDateToken);

        // Then
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());
        assertNull(captor.getValue().getLastUsed());
    }

    @Test
    void updateToken_shouldHandleEmptyTokenValue() {
        // Given
        when(persistentLoginRepository.findById("series123")).thenReturn(Optional.of(testRememberMe));

        // When: Update with empty token
        rememberMeRepository.updateToken("series123", "", testDate);

        // Then: Should accept empty string
        ArgumentCaptor<MyRememberMe> captor = ArgumentCaptor.forClass(MyRememberMe.class);
        verify(persistentLoginRepository).save(captor.capture());
        assertEquals("", captor.getValue().getToken());
    }
}
