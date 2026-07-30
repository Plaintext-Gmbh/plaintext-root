/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MyUserInfoBackingBean - user profile information display.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyUserInfoBackingBeanTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private ISetupConfigService setupConfigService;

    @Mock
    private MagicLinkService magicLinkService;

    @InjectMocks
    private MyUserInfoBackingBean bean;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setupAuthentication(List<SimpleGrantedAuthority> authorities) {
        User user = new User("test@example.com", "password", authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(user, "password", authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void getUsername_shouldReturnAuthenticatedUsername() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertEquals("test@example.com", bean.getUsername());
    }

    @Test
    void getUsername_shouldReturnNA_whenNoAuthentication() {
        SecurityContextHolder.clearContext();
        // Create a context with null auth
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertEquals("N/A", bean.getUsername());
    }

    @Test
    void getRoles_shouldReturnOnlyRolePrefixedAuthorities() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_42"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev")
        ));

        List<String> roles = bean.getRoles();

        assertEquals(2, roles.size());
        assertTrue(roles.contains("ROLE_USER"));
        assertTrue(roles.contains("ROLE_ADMIN"));
    }

    @Test
    void getRoles_shouldReturnEmpty_whenNoAuthentication() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        List<String> roles = bean.getRoles();

        assertTrue(roles.isEmpty());
    }

    @Test
    void getProperties_shouldReturnOnlyPropertyPrefixedAuthorities() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_42"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev")
        ));

        List<String> properties = bean.getProperties();

        assertEquals(2, properties.size());
        assertTrue(properties.contains("PROPERTY_MYUSERID_42"));
        assertTrue(properties.contains("PROPERTY_MANDAT_dev"));
    }

    @Test
    void getMandat_shouldReturnMandatFromProperties() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_production")
        ));

        assertEquals("production", bean.getMandat());
    }

    @Test
    void getMandat_shouldReturnNA_whenNoMandat() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertEquals("N/A", bean.getMandat());
    }

    @Test
    void getStartpage_shouldReturnStartpageFromProperties() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")
        ));

        assertEquals("dashboard.html", bean.getStartpage());
    }

    @Test
    void getStartpage_shouldReturnNA_whenNoStartpage() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertEquals("N/A", bean.getStartpage());
    }

    @Test
    void getStartpageOrDefault_shouldReturnDefault_whenNA() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertEquals("/index.html?faces-redirect=true", bean.getStartpageOrDefault());
    }

    @Test
    void getStartpageOrDefault_shouldAppendXhtml_whenNoExtension() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard")
        ));

        assertEquals("/dashboard.xhtml?faces-redirect=true", bean.getStartpageOrDefault());
    }

    @Test
    void getStartpageOrDefault_shouldAddLeadingSlash() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_kontakte.html")
        ));

        assertEquals("/kontakte.html?faces-redirect=true", bean.getStartpageOrDefault());
    }

    @Test
    void getStartpageOrDefault_shouldKeepXhtmlExtension() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_/kontakte.xhtml")
        ));

        assertEquals("/kontakte.xhtml?faces-redirect=true", bean.getStartpageOrDefault());
    }

    @Test
    void getMyUserId_shouldReturnUserIdFromProperties() {
        setupAuthentication(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_42")
        ));

        assertEquals("42", bean.getMyUserId());
    }

    @Test
    void getMyUserId_shouldReturnNA_whenNoUserId() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertEquals("N/A", bean.getMyUserId());
    }

    @Test
    void isAuthenticated_shouldReturnTrue_whenAuthenticated() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(bean.isAuthenticated());
    }

    @Test
    void isAuthenticated_shouldReturnFalse_whenNotAuthenticated() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(bean.isAuthenticated());
    }

    @Test
    void isAccountNonExpired_shouldReturnTrue_whenUserPrincipal() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(bean.isAccountNonExpired());
    }

    @Test
    void isAccountNonExpired_shouldReturnFalse_whenNoAuth() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(bean.isAccountNonExpired());
    }

    @Test
    void isAccountNonLocked_shouldReturnTrue_whenUserPrincipal() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(bean.isAccountNonLocked());
    }

    @Test
    void isCredentialsNonExpired_shouldReturnTrue_whenUserPrincipal() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(bean.isCredentialsNonExpired());
    }

    @Test
    void isEnabled_shouldReturnTrue_whenUserPrincipal() {
        setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        assertTrue(bean.isEnabled());
    }

    @Test
    void isEnabled_shouldReturnFalse_whenNoAuth() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(bean.isEnabled());
    }

    @Test
    void navigateToStartpage_shouldDelegateToPlaintextSecurity() {
        when(plaintextSecurity.getStartpageOrDefault()).thenReturn("/dashboard.html?faces-redirect=true");

        assertEquals("/dashboard.html?faces-redirect=true", bean.navigateToStartpage());
    }

    @Test
    void navigateToStartpage_shouldReturnDefault_whenSecurityNull() {
        ReflectionTestUtils.setField(bean, "plaintextSecurity", null);

        assertEquals("/index.html?faces-redirect=true", bean.navigateToStartpage());
    }

    @Test
    void toggleAdvancedMode_shouldToggle() {
        assertFalse(bean.isAdvancedMode());

        // Cannot test PrimeFaces.current() without JSF context, just test the field
        bean.setAdvancedMode(true);
        assertTrue(bean.isAdvancedMode());

        bean.setAdvancedMode(false);
        assertFalse(bean.isAdvancedMode());
    }
}
