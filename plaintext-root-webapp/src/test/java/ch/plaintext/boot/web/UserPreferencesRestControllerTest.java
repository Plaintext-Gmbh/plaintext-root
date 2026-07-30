/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for UserPreferencesRestController - REST API for user preferences.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferencesRestControllerTest {

    @Mock
    private UserPrefsSimpleStorage storage;

    @Mock
    private UserPreferencesBackingBean userPreferencesBackingBean;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private UserPreferencesRestController controller;

    @BeforeEach
    void setUp() {
        User user = new User("test@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void savePreferences_shouldReturnOk_withValidData() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "blue", "dark", "layout-horizontal",
                "dark", "dark", "filled", "false", null, response);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("OK", result.getBody());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void savePreferences_shouldCreateNewPrefs_whenNoneExist() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(null);

        ResponseEntity<String> result = controller.savePreferences(
                "green", null, null, null, null, null, null, null, response);

        assertEquals(200, result.getStatusCode().value());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void savePreferences_shouldOnlyUpdateProvidedValues() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setComponentTheme("green");
        existingPrefs.setDarkMode("light");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "blue", null, null, null, null, null, null, null, response);

        assertEquals(200, result.getStatusCode().value());
        // Only componentTheme should be updated
        assertEquals("blue", existingPrefs.getComponentTheme());
        assertEquals("light", existingPrefs.getDarkMode()); // unchanged
    }

    @Test
    void savePreferences_shouldSkipEmptyValues() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setComponentTheme("green");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "", "", "", "", "", "", "", null, response);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("green", existingPrefs.getComponentTheme()); // unchanged
    }

    @Test
    void savePreferences_shouldReturn401_whenNotAuthenticated() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        ResponseEntity<String> result = controller.savePreferences(
                "blue", null, null, null, null, null, null, null, response);

        assertEquals(401, result.getStatusCode().value());
    }

    @Test
    void savePreferences_shouldUpdateSessionBean() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        controller.savePreferences(
                "blue", "dark", "layout-horizontal",
                "dark", "dark", "filled", "true", null, response);

        verify(userPreferencesBackingBean).updateFromRestApi(
                "blue", "dark", "layout-horizontal",
                "dark", "dark", "filled", "true", null);
    }

    @Test
    void savePreferences_shouldSaveThemeToCookie_whenDarkModeProvided() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        controller.savePreferences(
                null, "dark", null, null, null, null, null, null, response);

        verify(response).addCookie(any());
    }

    @Test
    void savePreferences_shouldSaveColorCookie_whenComponentThemeProvided() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        controller.savePreferences(
                "blue", null, null, null, null, null, null, null, response);

        // Color cookie should be set for componentTheme
        verify(response).addCookie(any());
    }

    @Test
    void savePreferences_shouldReturn500_onException() {
        when(storage.findByUniqueId("test@example.com")).thenThrow(new RuntimeException("DB error"));

        ResponseEntity<String> result = controller.savePreferences(
                "blue", null, null, null, null, null, null, null, response);

        assertEquals(500, result.getStatusCode().value());
        assertTrue(result.getBody().contains("ERROR"));
    }

    @Test
    void savePreferences_shouldSetMenuStatic() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        controller.savePreferences(
                null, null, null, null, null, null, "true", null, response);

        assertTrue(existingPrefs.isMenuStatic());
    }

    @Test
    void savePreferences_shouldSetMenuStaticFalse() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setMenuStatic(true);
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        controller.savePreferences(
                null, null, null, null, null, null, "false", null, response);

        assertFalse(existingPrefs.isMenuStatic());
    }

    @Test
    void savePreferences_shouldSaveCustomColor() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "custom", null, null, null, null, null, null, "#FF5733", response);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("custom", existingPrefs.getComponentTheme());
        assertEquals("#FF5733", existingPrefs.getCustomColor());
    }

    @Test
    void savePreferences_shouldClearCustomColor_whenEmpty() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setCustomColor("#FF5733");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "green", null, null, null, null, null, null, "", response);

        assertEquals(200, result.getStatusCode().value());
        assertNull(existingPrefs.getCustomColor());
    }

    @Test
    void savePreferences_shouldRejectInvalidHexColor() {
        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        ResponseEntity<String> result = controller.savePreferences(
                "custom", null, null, null, null, null, null, "not-a-color", response);

        assertEquals(400, result.getStatusCode().value());
        assertTrue(result.getBody().contains("Invalid hex color"));
    }
}
