/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for UserPreferencesBackingBean - computed properties,
 * updateFromRestApi, delegating getters, and ComponentTheme.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferencesBackingBeanExtendedTest {

    @Mock
    private UserPrefsSimpleStorage storage;

    @InjectMocks
    private UserPreferencesBackingBean bean;

    private UserPreference prefs;

    @BeforeEach
    void setUp() {
        prefs = new UserPreference();
        prefs.setUniqueId("test@example.com");
        ReflectionTestUtils.setField(bean, "prefs", prefs);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== Delegating Getters ====================

    @Test
    void getDarkMode_shouldDelegateToPrefs() {
        prefs.setDarkMode("dark");
        assertEquals("dark", bean.getDarkMode());
    }

    @Test
    void getDarkMode2_shouldDelegateToPrefs() {
        prefs.setDarkMode("light");
        assertEquals("light", bean.getDarkMode2());
    }

    @Test
    void isLightLogo_shouldDelegateToPrefs() {
        prefs.setLightLogo(true);
        assertTrue(bean.isLightLogo());
    }

    @Test
    void getComponentTheme_shouldDelegateToPrefs() {
        prefs.setComponentTheme("blue");
        assertEquals("blue", bean.getComponentTheme());
    }

    @Test
    void getMenuTheme_shouldDelegateToPrefs() {
        prefs.setMenuTheme("dark");
        assertEquals("dark", bean.getMenuTheme());
    }

    @Test
    void getTopbarTheme_shouldDelegateToPrefs() {
        prefs.setTopbarTheme("dark");
        assertEquals("dark", bean.getTopbarTheme());
    }

    @Test
    void getMenuMode_shouldDelegateToPrefs() {
        prefs.setMenuMode("layout-horizontal");
        assertEquals("layout-horizontal", bean.getMenuMode());
    }

    @Test
    void getInputStyle_shouldDelegateToPrefs() {
        prefs.setInputStyle("filled");
        assertEquals("filled", bean.getInputStyle());
    }

    @Test
    void isMenuStatic_shouldDelegateToPrefs() {
        prefs.setMenuStatic(true);
        assertTrue(bean.isMenuStatic());
    }

    // ==================== Computed Properties ====================

    @Test
    void getLayout_shouldReturnLayoutDarkMode() {
        prefs.setDarkMode("dark");
        assertEquals("layout-dark", bean.getLayout());
    }

    @Test
    void getLayout_shouldReturnLayoutLight() {
        prefs.setDarkMode("light");
        assertEquals("layout-light", bean.getLayout());
    }

    @Test
    void getTheme_shouldCombineComponentAndDarkMode() {
        prefs.setComponentTheme("blue");
        prefs.setDarkMode("dark");
        assertEquals("blue-dark", bean.getTheme());
    }

    @Test
    void getInputStyleClass_shouldReturnFilled() {
        prefs.setInputStyle("filled");
        assertEquals("ui-input-filled", bean.getInputStyleClass());
    }

    @Test
    void getInputStyleClass_shouldReturnEmpty_forOutlined() {
        prefs.setInputStyle("outlined");
        assertEquals("", bean.getInputStyleClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnLayoutStatic_forSidebarStatic() {
        prefs.setMenuMode("layout-sidebar");
        prefs.setMenuStatic(true);
        assertEquals("layout-static", bean.getMenuStaticClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnEmpty_forSidebarNonStatic() {
        prefs.setMenuMode("layout-sidebar");
        prefs.setMenuStatic(false);
        assertEquals("", bean.getMenuStaticClass());
    }

    @Test
    void getMenuStaticClass_shouldReturnEmpty_forHorizontalMode() {
        prefs.setMenuMode("layout-horizontal");
        prefs.setMenuStatic(true);
        assertEquals("", bean.getMenuStaticClass());
    }

    // ==================== updateFromRestApi ====================

    @Test
    void updateFromRestApi_shouldUpdateAllFields() {
        bean.updateFromRestApi("blue", "dark", "layout-horizontal",
                "dark", "dark", "filled", "false", null);

        assertEquals("blue", prefs.getComponentTheme());
        assertEquals("dark", prefs.getDarkMode());
        assertEquals("layout-horizontal", prefs.getMenuMode());
        assertEquals("dark", prefs.getTopbarTheme());
        assertEquals("dark", prefs.getMenuTheme());
        assertEquals("filled", prefs.getInputStyle());
        assertFalse(prefs.isMenuStatic());
    }

    @Test
    void updateFromRestApi_shouldSkipNullValues() {
        prefs.setComponentTheme("green");
        prefs.setDarkMode("light");

        bean.updateFromRestApi(null, null, null, null, null, null, null, null);

        assertEquals("green", prefs.getComponentTheme());
        assertEquals("light", prefs.getDarkMode());
    }

    @Test
    void updateFromRestApi_shouldSkipEmptyValues() {
        prefs.setComponentTheme("green");

        bean.updateFromRestApi("", "", "", "", "", "", "", null);

        assertEquals("green", prefs.getComponentTheme());
    }

    // ==================== save ====================

    @Test
    void save_shouldCallStorage() {
        bean.save();
        verify(storage).save(prefs);
    }

    @Test
    void save_shouldHandleException() {
        doThrow(new RuntimeException("DB error")).when(storage).save(any());

        // Should not throw
        assertDoesNotThrow(() -> bean.save());
    }

    // ==================== init with existing prefs ====================

    @Test
    void init_shouldLoadPrefsFromDB_whenExists() {
        User user = new User("test@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        UserPreference existingPrefs = new UserPreference();
        existingPrefs.setUniqueId("test@example.com");
        existingPrefs.setDarkMode("dark");
        existingPrefs.setTopbarTheme("dark");
        existingPrefs.setMenuTheme("dark");
        existingPrefs.setMenuStatic(true);
        when(storage.findByUniqueId("test@example.com")).thenReturn(existingPrefs);

        bean.init();

        assertEquals("dark", bean.getDarkMode());
        assertFalse(bean.getComponentThemes().isEmpty());
    }

    @Test
    void init_shouldCreateNewPrefs_whenNotExists() {
        User user = new User("new@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        when(storage.findByUniqueId("new@example.com")).thenReturn(null);

        bean.init();

        verify(storage).save(any(UserPreference.class));
    }

    @Test
    void init_shouldSyncThemes_whenOutOfSync() {
        User user = new User("test@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        UserPreference unsyncedPrefs = new UserPreference();
        unsyncedPrefs.setUniqueId("test@example.com");
        unsyncedPrefs.setDarkMode("dark");
        unsyncedPrefs.setTopbarTheme("light"); // out of sync
        unsyncedPrefs.setMenuTheme("light"); // out of sync
        unsyncedPrefs.setMenuStatic(true);
        when(storage.findByUniqueId("test@example.com")).thenReturn(unsyncedPrefs);

        bean.init();

        // Themes should be synced to darkMode
        assertEquals("dark", bean.getTopbarTheme());
        assertEquals("dark", bean.getMenuTheme());
    }

    @Test
    void init_shouldMigrateMenuStatic_whenFalse() {
        User user = new User("test@example.com", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, "password", user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        UserPreference oldPrefs = new UserPreference();
        oldPrefs.setUniqueId("test@example.com");
        oldPrefs.setDarkMode("light");
        oldPrefs.setTopbarTheme("light");
        oldPrefs.setMenuTheme("light");
        oldPrefs.setMenuStatic(false); // old default
        when(storage.findByUniqueId("test@example.com")).thenReturn(oldPrefs);

        bean.init();

        assertTrue(bean.isMenuStatic());
    }

    // ==================== toggleMenuStatic ====================

    @Test
    void toggleMenuStatic_shouldToggle() {
        prefs.setMenuStatic(true);
        bean.toggleMenuStatic();
        assertFalse(prefs.isMenuStatic());

        bean.toggleMenuStatic();
        assertTrue(prefs.isMenuStatic());
    }

    // ==================== ComponentTheme inner class ====================

    @Test
    void componentTheme_shouldStoreValues() {
        UserPreferencesBackingBean.ComponentTheme theme =
                new UserPreferencesBackingBean.ComponentTheme("Blue", "blue", "#2c84d8");

        assertEquals("Blue", theme.getName());
        assertEquals("blue", theme.getFile());
        assertEquals("#2c84d8", theme.getColor());
    }
}
