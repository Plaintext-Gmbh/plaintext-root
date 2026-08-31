/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended tests for MyUserInfoBackingBean covering changePassword and sendMagicLinkToSelf.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyUserInfoBackingBeanExtendedTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private ISetupConfigService setupConfigService;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private MagicLinkService magicLinkService;

    /**
     * SECURITY (card 314, item 7): the PasswordEncoder is now injected (central bean with
     * cost factor 12) instead of being instantiated locally. As a @Spy, so that the tests can still
     * check against real BCrypt.
     */
    @org.mockito.Spy
    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private MyUserInfoBackingBean bean;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

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

    // ==================== changePassword Tests ====================

    @Test
    void changePassword_shouldShowError_whenCurrentPasswordEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setCurrentPassword(null);
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(userRepository, never()).save(any());
        }
    }

    @Test
    void changePassword_shouldShowError_whenCurrentPasswordBlank() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setCurrentPassword("  ");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void changePassword_shouldShowError_whenNewPasswordEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword(null);
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void changePassword_shouldShowError_whenConfirmPasswordEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword(null);

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void changePassword_shouldShowError_whenPasswordsMismatch() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword("newPass1");
            bean.setConfirmPassword("newPass2");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void changePassword_shouldShowError_whenUserNotFound() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            when(userRepository.findByUsername("test@example.com")).thenReturn(null);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void changePassword_shouldShowError_whenCurrentPasswordIncorrect() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

            MyUserEntity user = new MyUserEntity();
            user.setPassword(encoder.encode("correctPassword"));
            when(userRepository.findByUsername("test@example.com")).thenReturn(user);

            bean.setCurrentPassword("wrongPassword");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(userRepository, never()).save(any());
        }
    }

    @Test
    void changePassword_shouldSucceed_whenValid() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

            MyUserEntity user = new MyUserEntity();
            user.setPassword(encoder.encode("currentPass"));
            when(userRepository.findByUsername("test@example.com")).thenReturn(user);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(userRepository).save(user);
            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
            // Fields should be cleared
            assertNull(bean.getCurrentPassword());
            assertNull(bean.getNewPassword());
            assertNull(bean.getConfirmPassword());
        }
    }

    @Test
    void changePassword_shouldShowError_whenUsernameNA() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            // Set up auth without User principal to get "N/A"
            SecurityContextHolder.clearContext();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(context);

            bean.setCurrentPassword("currentPass");
            bean.setNewPassword("newPass");
            bean.setConfirmPassword("newPass");

            bean.changePassword();

            verify(facesContext).addMessage(eq("passwordMessages"), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    // ==================== sendMagicLinkToSelf Tests ====================

    @Test
    void sendMagicLinkToSelf_shouldShowInfo_whenSent() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            when(externalContext.getRequest()).thenReturn(httpRequest);

            setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            when(magicLinkService.generateAndSend(eq("test@example.com"), any())).thenReturn(true);

            bean.sendMagicLinkToSelf();

            verify(magicLinkService).generateAndSend(eq("test@example.com"), any());
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }
    }

    @Test
    void sendMagicLinkToSelf_shouldShowWarn_whenNotSent() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            when(externalContext.getRequest()).thenReturn(httpRequest);

            setupAuthentication(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            when(magicLinkService.generateAndSend(eq("test@example.com"), any())).thenReturn(false);

            bean.sendMagicLinkToSelf();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }
    }

    // ==================== Properties getter/setter Tests ====================

    @Test
    void getProperties_shouldReturnEmpty_whenNoAuth() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        List<String> props = bean.getProperties();
        assertTrue(props.isEmpty());
    }

    @Test
    void isAccountNonLocked_shouldReturnFalse_whenNoAuth() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(bean.isAccountNonLocked());
    }

    @Test
    void isCredentialsNonExpired_shouldReturnFalse_whenNoAuth() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(bean.isCredentialsNonExpired());
    }

    @Test
    void getUsername_shouldReturnName_whenPrincipalIsNotUser() {
        Authentication auth = new UsernamePasswordAuthenticationToken("simpleUsername", "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        assertEquals("simpleUsername", bean.getUsername());
    }
}
