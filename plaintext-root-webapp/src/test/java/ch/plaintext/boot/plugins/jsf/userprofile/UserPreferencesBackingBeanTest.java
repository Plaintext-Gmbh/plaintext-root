/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for UserPreferencesBackingBean - session-scoped user preferences management.
 */
@ExtendWith(MockitoExtension.class)
class UserPreferencesBackingBeanTest {

    @Mock
    private UserPrefsSimpleStorage storage;

    @InjectMocks
    private UserPreferencesBackingBean bean;

    private UserPreference existingPrefs;

    @BeforeEach
    void setUp() {
        // Setup security context with a user
        User user = new User("test@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setDarkMode("light");
        existingPrefs.setTopbarTheme("light");
        existingPrefs.setMenuTheme("light");
        existingPrefs.setMenuStatic(true);
    }

    @Test
    void init_shouldLoadExistingPreferences() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        assertEquals("light", bean.getDarkMode());
        assertEquals("green", bean.getComponentTheme());
        assertFalse(bean.getComponentThemes().isEmpty());
        assertEquals(10, bean.getComponentThemes().size());
    }

    @Test
    void init_shouldCreateNewPreferences_whenNoneExist() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(null);

        bean.init();

        verify(storage).save(any(UserPreference.class));
        assertNotNull(bean.getDarkMode());
    }

    @Test
    void init_shouldSyncTopbarThemeToMatchDarkMode() {
        existingPrefs.setDarkMode("dark");
        existingPrefs.setTopbarTheme("light"); // Mismatched
        existingPrefs.setMenuTheme("dark");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        assertEquals("dark", bean.getTopbarTheme());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void init_shouldSyncMenuThemeToMatchDarkMode() {
        existingPrefs.setDarkMode("dark");
        existingPrefs.setTopbarTheme("dark");
        existingPrefs.setMenuTheme("light"); // Mismatched
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        assertEquals("dark", bean.getMenuTheme());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void init_shouldMigrateMenuStaticToTrue() {
        existingPrefs.setMenuStatic(false); // Old default
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        assertTrue(bean.isMenuStatic());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void init_shouldPopulateComponentThemes() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        List<UserPreferencesBackingBean.ComponentTheme> themes = bean.getComponentThemes();
        assertEquals(10, themes.size());

        assertEquals("Blue", themes.get(0).getName());
        assertEquals("blue", themes.get(0).getFile());
        assertEquals("#2196F3", themes.get(0).getColor());

        assertEquals("Green", themes.get(1).getName());
        assertEquals("Yellow", themes.get(7).getName());
        assertEquals("Lime", themes.get(8).getName());
        assertEquals("Crimson", themes.get(9).getName());
    }

    @Test
    void save_shouldPersistPreferences() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        // Reset mock to clear init() calls
        reset(storage);

        bean.save();

        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void save_shouldHandleException() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();
        reset(storage);

        doThrow(new RuntimeException("DB error")).when(storage).save(any());

        // Should not throw
        assertDoesNotThrow(() -> bean.save());
    }

    @Test
    void updateFromRestApi_shouldUpdateProvidedFields() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        bean.updateFromRestApi("blue", "dark", "layout-horizontal",
                "dark", "dark", "filled", "false", null);

        assertEquals("blue", bean.getComponentTheme());
        assertEquals("dark", bean.getDarkMode());
        assertEquals("layout-horizontal", bean.getMenuMode());
        assertEquals("dark", bean.getTopbarTheme());
        assertEquals("dark", bean.getMenuTheme());
        assertEquals("filled", bean.getInputStyle());
        assertFalse(bean.isMenuStatic());
    }

    @Test
    void updateFromRestApi_shouldSkipNullFields() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        bean.updateFromRestApi(null, null, null, null, null, null, null, null);

        // Values should remain unchanged
        assertEquals("green", bean.getComponentTheme());
        assertEquals("light", bean.getDarkMode());
    }

    @Test
    void updateFromRestApi_shouldSkipEmptyFields() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        bean.updateFromRestApi("", "", "", "", "", "", "", null);

        // Values should remain unchanged
        assertEquals("green", bean.getComponentTheme());
        assertEquals("light", bean.getDarkMode());
    }

    @Test
    void getDelegatingGetters_shouldReturnCorrectValues() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("light", bean.getDarkMode());
        assertEquals("light", bean.getDarkMode2());
        assertFalse(bean.isLightLogo());
        assertEquals("green", bean.getComponentTheme());
        assertEquals("light", bean.getMenuTheme());
        assertEquals("light", bean.getTopbarTheme());
        assertEquals("layout-sidebar", bean.getMenuMode());
        assertEquals("outlined", bean.getInputStyle());
        assertTrue(bean.isMenuStatic());
    }

    @Test
    void getLayout_shouldCombineDarkMode() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("layout-light", bean.getLayout());
    }

    @Test
    void getLayout_shouldReturnDarkLayout() {
        existingPrefs.setDarkMode("dark");
        existingPrefs.setTopbarTheme("dark");
        existingPrefs.setMenuTheme("dark");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("layout-dark", bean.getLayout());
    }

    @Test
    void getTheme_shouldCombineComponentThemeAndDarkMode() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("green-light", bean.getTheme());
    }

    @Test
    void getInputStyleClass_shouldReturnFilledClass() {
        existingPrefs.setInputStyle("filled");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("ui-input-filled", bean.getInputStyleClass());
    }

    @Test
    void getInputStyleClass_shouldReturnEmptyForOutlined() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("", bean.getInputStyleClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnLayoutStatic_whenSidebarAndStatic() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("layout-static", bean.getMenuStaticClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnEmpty_whenNotSidebar() {
        existingPrefs.setMenuMode("layout-horizontal");
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        assertEquals("", bean.getMenuStaticClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnEmpty_whenNotStatic() {
        existingPrefs.setMenuStatic(false);
        // But init migrates it to true... So set after init
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();

        // After migration, menuStatic is true, so it returns layout-static
        // Let's set via updateFromRestApi
        bean.updateFromRestApi(null, null, null, null, null, null, "false", null);
        assertEquals("", bean.getMenuStaticClass());
    }

    @Test
    void setComponentTheme_shouldUpdateAndSave() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();
        reset(storage);

        bean.setComponentTheme("blue");

        assertEquals("blue", bean.getComponentTheme());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void setMenuStatic_shouldUpdateAndSave() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();
        reset(storage);

        bean.setMenuStatic(false);

        assertFalse(bean.isMenuStatic());
        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void toggleMenuStatic_shouldToggleAndSave() {
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);
        bean.init();
        reset(storage);

        assertTrue(bean.isMenuStatic());
        bean.toggleMenuStatic();
        assertFalse(bean.isMenuStatic());
        verify(storage).save(any(UserPreference.class));

        reset(storage);
        bean.toggleMenuStatic();
        assertTrue(bean.isMenuStatic());
    }

    @Test
    void componentTheme_innerClass_shouldWorkCorrectly() {
        UserPreferencesBackingBean.ComponentTheme theme =
                new UserPreferencesBackingBean.ComponentTheme("TestName", "testfile", "#FF0000");

        assertEquals("TestName", theme.getName());
        assertEquals("testfile", theme.getFile());
        assertEquals("#FF0000", theme.getColor());
    }
}
