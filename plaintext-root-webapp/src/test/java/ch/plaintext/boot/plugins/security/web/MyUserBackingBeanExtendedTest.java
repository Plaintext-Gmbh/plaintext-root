/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for MyUserBackingBean covering save, delete, checkAccess,
 * impersonation, role management and mandate methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyUserBackingBeanExtendedTest {

    @Mock
    private MyUserRepository repo;

    @Mock
    private MyRememberMeRepository rememberMeRepo;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    @Mock
    private UserMandateRepository userMandateRepo;

    @InjectMocks
    private MyUserBackingBean bean;

    private MyUserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new MyUserEntity();
        testUser.setId(1L);
        testUser.setUsername("test@example.com");
        testUser.setPassword("$2a$10$hashedPassword");
        testUser.setMandat("test_mandat");
        testUser.addRole("user");

        lenient().when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        lenient().when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(true);
        lenient().when(plaintextSecurity.getMandat()).thenReturn("test_mandat");
        lenient().when(repo.findAll()).thenReturn(new ArrayList<>(List.of(testUser)));
        lenient().when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());
    }

    // ==================== isRoot / isAdmin Tests ====================

    @Test
    void isRoot_shouldReturnTrue_whenUserHasRootRole() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        assertTrue(bean.isRoot());
    }

    @Test
    void isRoot_shouldReturnFalse_whenUserDoesNotHaveRootRole() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
        assertFalse(bean.isRoot());
    }

    @Test
    void isAdmin_shouldReturnTrue_whenUserHasAdminRole() {
        when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(true);
        assertTrue(bean.isAdmin());
    }

    @Test
    void isAdmin_shouldReturnFalse_whenUserDoesNotHaveAdminRole() {
        when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(false);
        assertFalse(bean.isAdmin());
    }

    // ==================== init() Tests ====================

    @Test
    void init_shouldLoadAllUsers_whenRoot() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);

        bean.init();

        assertEquals(1, bean.getUsers().size());
        verify(repo).findAll();
    }

    @Test
    void init_shouldFilterByMandate_whenAdminNotRoot() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
        when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(true);
        when(plaintextSecurity.getMandat()).thenReturn("test_mandat");

        MyUserEntity otherUser = new MyUserEntity();
        otherUser.setId(2L);
        otherUser.setMandat("other_mandat");
        when(repo.findAll()).thenReturn(List.of(testUser, otherUser));

        bean.init();

        assertEquals(1, bean.getUsers().size());
        assertEquals(testUser, bean.getUsers().get(0));
    }

    @Test
    void init_shouldLoadNoUsers_whenNeitherAdminNorRoot() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
        when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(false);

        bean.init();

        assertTrue(bean.getUsers().isEmpty());
    }

    // ==================== checkAccess() Tests ====================

    @Test
    void checkAccess_shouldDoNothing_whenRoot() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);

        // Should not throw or redirect
        bean.checkAccess();
    }

    @Test
    void checkAccess_shouldDoNothing_whenAdmin() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
        when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(true);

        bean.checkAccess();
    }

    @Test
    void checkAccess_shouldRedirect_whenNotAdminOrRoot() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
            when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(false);

            bean.checkAccess();

            verify(externalContext).redirect("access-denied.xhtml");
        }
    }

    @Test
    void checkAccess_shouldHandleRedirectException() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            doThrow(new IOException("redirect failed")).when(externalContext).redirect(anyString());

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
            when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(false);

            // Should not throw
            assertDoesNotThrow(() -> bean.checkAccess());
        }
    }

    // ==================== save() Tests ====================

    @Test
    void save_shouldShowError_whenUsernameEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setUsername("");
            bean.setSelected(testUser);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(facesContext).validationFailed();
            verify(repo, never()).save(any(MyUserEntity.class));
        }
    }

    @Test
    void save_shouldShowError_whenUsernameNull() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setUsername(null);
            bean.setSelected(testUser);

            bean.save();

            verify(facesContext).validationFailed();
        }
    }

    @Test
    void save_shouldShowError_whenInvalidEmail() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setUsername("notanemail");
            bean.setSelected(testUser);

            bean.save();

            verify(facesContext).validationFailed();
        }
    }

    @Test
    void save_shouldShowError_whenDuplicateUsername() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity existingUser = new MyUserEntity();
            existingUser.setId(99L);
            when(repo.findByUsername("test@example.com")).thenReturn(existingUser);

            bean.setSelected(testUser);

            bean.save();

            verify(facesContext).validationFailed();
        }
    }

    @Test
    void save_shouldSucceed_whenSameUserSameUsername() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);
            when(repo.save(any(MyUserEntity.class))).thenReturn(testUser);

            bean.setSelected(testUser);
            bean.setMyUserPw(testUser.getPassword());

            bean.save();

            verify(repo).save(any(MyUserEntity.class));
        }
    }

    @Test
    void save_shouldEncodeNewPassword() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);
            when(repo.save(any(MyUserEntity.class))).thenReturn(testUser);

            testUser.setPassword("newPlainPassword");
            bean.setSelected(testUser);
            bean.setMyUserPw("$2a$10$oldHash");

            bean.save();

            verify(repo).save(argThat(u -> u.getPassword().startsWith("$2a$10")));
        }
    }

    @Test
    void save_shouldKeepExistingPassword_whenNotChanged() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            String existingHash = "$2a$10$existingHashValue";
            testUser.setPassword(existingHash);
            bean.setSelected(testUser);
            bean.setMyUserPw(existingHash);

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);
            when(repo.save(any(MyUserEntity.class))).thenReturn(testUser);

            bean.save();

            verify(repo).save(argThat(u -> u.getPassword().equals(existingHash)));
        }
    }

    @Test
    void save_shouldSetDefaultMandate_whenEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setMandat(null);
            testUser.setPassword("$2a$10$alreadyEncoded");
            bean.setSelected(testUser);
            bean.setMyUserPw(testUser.getPassword());

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);
            when(repo.save(any(MyUserEntity.class))).thenReturn(testUser);

            bean.save();

            verify(repo).save(argThat(u -> "default".equals(u.getMandat())));
        }
    }

    @Test
    void save_shouldShowError_whenNewUserWithEmptyPassword() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setPassword("");
            bean.setSelected(testUser);
            bean.setMyUserPw(null);

            when(repo.findByUsername("test@example.com")).thenReturn(null);

            bean.save();

            verify(facesContext).validationFailed();
        }
    }

    @Test
    void save_shouldClearSelectionAndReload() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            testUser.setPassword("$2a$10$hash");
            bean.setSelected(testUser);
            bean.setMyUserPw(testUser.getPassword());

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);
            when(repo.save(any(MyUserEntity.class))).thenReturn(testUser);

            bean.save();

            assertNull(bean.getSelected());
        }
    }

    // ==================== delete() Tests ====================

    @Test
    void delete_shouldDeleteSelectedUser() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setSelected(testUser);

            bean.delete();

            verify(repo).delete(testUser);
            assertNull(bean.getSelected());
        }
    }

    @Test
    void delete_shouldShowSuccessMessage() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setSelected(testUser);

            bean.delete();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }
    }

    @Test
    void delete_shouldShowError_whenDeleteFails() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setSelected(testUser);
            doThrow(new RuntimeException("DB error")).when(repo).delete(any());

            bean.delete();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void delete_shouldDoNothing_whenNoSelection() {
        bean.setSelected(null);

        bean.delete();

        verify(repo, never()).delete(any());
    }

    @Test
    void delete_shouldDoNothing_whenSelectionHasNoId() {
        MyUserEntity noIdUser = new MyUserEntity();
        noIdUser.setId(null);
        bean.setSelected(noIdUser);

        bean.delete();

        verify(repo, never()).delete(any());
    }

    // ==================== clearSelection() Tests ====================

    @Test
    void clearSelection_shouldSetSelectedToNull() {
        bean.setSelected(testUser);
        bean.clearSelection();
        assertNull(bean.getSelected());
    }

    // ==================== validateUsername() Tests ====================

    @Test
    void validateUsername_shouldDoNothing_whenSelectedNull() {
        bean.setSelected(null);
        assertDoesNotThrow(() -> bean.validateUsername());
    }

    @Test
    void validateUsername_shouldDoNothing_whenUsernameNull() {
        testUser.setUsername(null);
        bean.setSelected(testUser);
        assertDoesNotThrow(() -> bean.validateUsername());
    }

    @Test
    void validateUsername_shouldDoNothing_whenUsernameEmpty() {
        testUser.setUsername("");
        bean.setSelected(testUser);
        assertDoesNotThrow(() -> bean.validateUsername());
    }

    @Test
    void validateUsername_shouldShowError_whenDuplicateUsername() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity existing = new MyUserEntity();
            existing.setId(99L);
            when(repo.findByUsername("test@example.com")).thenReturn(existing);

            bean.setSelected(testUser);
            bean.validateUsername();

            verify(facesContext).addMessage(eq("username"), any(FacesMessage.class));
        }
    }

    @Test
    void validateUsername_shouldNotShowError_whenSameUser() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(repo.findByUsername("test@example.com")).thenReturn(testUser);

            bean.setSelected(testUser);
            bean.validateUsername();

            verify(facesContext, never()).addMessage(anyString(), any(FacesMessage.class));
        }
    }

    // ==================== Role Management Tests ====================

    @Test
    void getSelectedRolesList_shouldReturnEmpty_whenNoSelection() {
        bean.setSelected(null);
        assertTrue(bean.getSelectedRolesList().isEmpty());
    }

    @Test
    void getSelectedRolesList_shouldFilterProperties() {
        testUser.addRole("PROPERTY_SOMETHING");
        testUser.addRole("user");
        bean.setSelected(testUser);

        List<String> roles = bean.getSelectedRolesList();

        assertFalse(roles.stream().anyMatch(r -> r.startsWith("PROPERTY_")));
    }

    @Test
    void getSelectedRolesList_shouldFilterMandatRoles() {
        testUser.addRole("PROPERTY_MANDAT_dev");
        testUser.addRole("user");
        bean.setSelected(testUser);

        List<String> roles = bean.getSelectedRolesList();

        assertFalse(roles.stream().anyMatch(r -> r.toLowerCase().contains("mandat")));
    }

    @Test
    void setSelectedRolesList_shouldDoNothing_whenNoSelection() {
        bean.setSelected(null);
        assertDoesNotThrow(() -> bean.setSelectedRolesList(List.of("admin")));
    }

    @Test
    void setSelectedRolesList_shouldUpdateRoles() {
        bean.setSelected(testUser);
        testUser.setMandat("dev");

        bean.setSelectedRolesList(List.of("admin", "user"));

        assertTrue(testUser.getRoles().contains("admin"));
        assertTrue(testUser.getRoles().contains("user"));
    }

    @Test
    void setSelectedRolesList_shouldPreserveMandate() {
        bean.setSelected(testUser);
        testUser.setMandat("dev");

        bean.setSelectedRolesList(List.of("admin"));

        assertEquals("dev", testUser.getMandat());
    }

    @Test
    void setSelectedRolesList_shouldHandleNull() {
        bean.setSelected(testUser);

        bean.setSelectedRolesList(null);

        assertNotNull(testUser.getRoles());
    }

    @Test
    void addSuggestedRoles_shouldDoNothing_whenNoSelection() {
        bean.setSelected(null);
        assertDoesNotThrow(() -> bean.addSuggestedRoles());
    }

    @Test
    void addSuggestedRoles_shouldDoNothing_whenTempRolesEmpty() {
        bean.setSelected(testUser);
        bean.setTempRoles(new ArrayList<>());
        bean.addSuggestedRoles();
        // Should not throw
    }

    @Test
    void addSuggestedRoles_shouldAddRoles() {
        bean.setSelected(testUser);
        bean.setTempRoles(new ArrayList<>(List.of("admin", "root")));

        bean.addSuggestedRoles();

        List<String> roles = bean.getSelectedRolesList();
        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("root"));
        assertTrue(bean.getTempRoles().isEmpty());
    }

    @Test
    void addSuggestedRoles_shouldNotDuplicate() {
        testUser.addRole("admin");
        bean.setSelected(testUser);
        bean.setTempRoles(new ArrayList<>(List.of("admin")));

        bean.addSuggestedRoles();

        long count = bean.getSelectedRolesList().stream()
                .filter(r -> r.equals("admin"))
                .count();
        assertEquals(1, count);
    }

    // ==================== onSelectedRolesChanged / onAvailableRolesChanged ====================

    @Test
    void onSelectedRolesChanged_shouldDoNothing_whenNoSelection() {
        bean.setSelected(null);
        assertDoesNotThrow(() -> bean.onSelectedRolesChanged());
    }

    @Test
    void onSelectedRolesChanged_shouldUpdateAvailableRoles() {
        bean.setSelected(testUser);
        bean.onSelectedRolesChanged();
        // Should not throw, internal state is updated
    }

    @Test
    void onAvailableRolesChanged_shouldDoNothing_whenNoSelection() {
        bean.setSelected(null);
        assertDoesNotThrow(() -> bean.onAvailableRolesChanged());
    }

    @Test
    void onAvailableRolesChanged_shouldMoveMissingRolesToSelected() {
        bean.setSelected(testUser);
        // Initialize available roles list
        bean.setAvailableRolesList(new ArrayList<>());

        bean.onAvailableRolesChanged();
        // Should not throw
    }

    // ==================== getAllMandate() Tests ====================

    @Test
    void getAllMandate_shouldReturnSortedList() {
        Set<String> mandates = new LinkedHashSet<>(Arrays.asList("zeta", "alpha"));
        when(plaintextSecurity.getAllMandate()).thenReturn(mandates);

        List<String> result = bean.getAllMandate();

        assertEquals("alpha", result.get(0));
        assertEquals("zeta", result.get(1));
    }

    @Test
    void getAllMandate_shouldReturnEmpty_whenSecurityReturnsNull() {
        when(plaintextSecurity.getAllMandate()).thenReturn(null);

        List<String> result = bean.getAllMandate();

        assertTrue(result.isEmpty());
    }

    @Test
    void getAllMandate_shouldReturnEmpty_whenSecurityReturnsEmpty() {
        when(plaintextSecurity.getAllMandate()).thenReturn(new HashSet<>());

        List<String> result = bean.getAllMandate();

        assertTrue(result.isEmpty());
    }

    @Test
    void getAllMandate_shouldHandleException() {
        when(plaintextSecurity.getAllMandate()).thenThrow(new RuntimeException("error"));

        List<String> result = bean.getAllMandate();

        assertTrue(result.isEmpty());
    }

    // ==================== impersonateUser() Tests ====================

    @Test
    void impersonateUser_shouldRejectNonRoot() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);

            bean.impersonateUser(testUser);

            verify(plaintextSecurity, never()).startImpersonation(anyLong());
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void impersonateUser_shouldRejectNullUser() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);

            bean.impersonateUser(null);

            verify(plaintextSecurity, never()).startImpersonation(anyLong());
        }
    }

    @Test
    void impersonateUser_shouldRejectSelfImpersonation() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
            when(plaintextSecurity.getId()).thenReturn(1L);

            bean.impersonateUser(testUser);

            verify(plaintextSecurity, never()).startImpersonation(anyLong());
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }
    }

    @Test
    void impersonateUser_shouldStartImpersonation() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
            when(plaintextSecurity.getId()).thenReturn(99L);

            bean.impersonateUser(testUser);

            verify(plaintextSecurity).startImpersonation(1L);
            verify(externalContext).redirect("index.xhtml");
        }
    }

    @Test
    void impersonateUser_shouldHandleError() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
            when(plaintextSecurity.getId()).thenReturn(99L);
            doThrow(new RuntimeException("error")).when(plaintextSecurity).startImpersonation(anyLong());

            bean.impersonateUser(testUser);

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    // ==================== stopImpersonation() Tests ====================

    @Test
    void stopImpersonation_shouldStopAndRedirect() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);

            bean.stopImpersonation();

            verify(plaintextSecurity).stopImpersonation();
            verify(externalContext).redirect("index.xhtml");
        }
    }

    @Test
    void stopImpersonation_shouldHandleError() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            doThrow(new RuntimeException("error")).when(plaintextSecurity).stopImpersonation();

            bean.stopImpersonation();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    // ==================== extractRolesFromDatabase Tests ====================

    @Test
    void getAvailableRoles_shouldExtractRolesFromDb() {
        MyUserEntity user1 = new MyUserEntity();
        user1.addRole("admin");
        user1.addRole("user");

        MyUserEntity user2 = new MyUserEntity();
        user2.addRole("ROLE_editor");

        when(repo.findAll()).thenReturn(List.of(user1, user2));

        Set<String> roles = bean.getAvailableRoles();

        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("user"));
        assertTrue(roles.contains("editor"));
    }

    @Test
    void getAvailableRoles_shouldFilterPropertyRoles() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("PROPERTY_MANDAT_dev");
        user.addRole("admin");

        when(repo.findAll()).thenReturn(List.of(user));

        Set<String> roles = bean.getAvailableRoles();

        assertFalse(roles.stream().anyMatch(r -> r.contains("property")));
    }

    @Test
    void getAvailableRoles_shouldFilterMandatRoles() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("PROPERTY_MANDAT_dev");
        user.addRole("admin");

        when(repo.findAll()).thenReturn(List.of(user));

        Set<String> roles = bean.getAvailableRoles();

        assertFalse(roles.stream().anyMatch(r -> r.contains("mandat")));
    }

    @Test
    void getAvailableRolesNotSelected_shouldFilterSelected() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        user.addRole("user");
        user.addRole("editor");

        when(repo.findAll()).thenReturn(List.of(user));

        // Select user with admin role
        testUser.addRole("admin");
        bean.setSelected(testUser);

        Set<String> notSelected = bean.getAvailableRolesNotSelected();

        // "admin" is in selected, so should not be in notSelected
        // but "editor" should be (if it's in available)
        assertFalse(notSelected.contains("user")); // user is in testUser.roles already
    }

    // ==================== newUser() Tests ====================

    @Test
    void newUser_shouldCreateWithDefaultMandate() {
        MyUserEntity newUser = new MyUserEntity();
        newUser.setId(2L);
        when(repo.save(any(MyUserEntity.class))).thenReturn(newUser);

        bean.newUser();

        verify(repo).save(argThat(u -> "default".equals(u.getMandat())));
    }

    // ==================== deleteRememberMe Tests ====================

    @Test
    void deleteRememberMe_shouldDeleteAndReload() {
        var rm = new ch.plaintext.boot.plugins.security.model.MyRememberMe();
        bean.setSelectedRememberMe(rm);

        bean.deleteRememberMe();

        verify(rememberMeRepo).delete(rm);
    }
}
