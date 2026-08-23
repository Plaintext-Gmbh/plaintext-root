/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.framework.PlaintextRoleRegistry;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    /** Rollen-Registry (Modul-Rollen-Registrierung): liefert die deklarierten Rollen. */
    @Mock
    private PlaintextRoleRegistry roleRegistry;

    /**
     * SECURITY (Karte 314, Punkt 7): der PasswordEncoder wird jetzt injiziert (zentrale Bean mit
     * Kostenfaktor 12) statt lokal instanziiert. Als @Spy, damit die Tests weiterhin gegen echtes
     * BCrypt pruefen koennen.
     */
    @org.mockito.Spy
    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * Forensik 23.08.2026: Audit-Log fuer Rollenaenderungen/Loeschungen. Muss als Mock dabei sein, weil ein
     * parametrisierter Konstruktor die Konstruktor-Injection von {@code @InjectMocks} aktiviert —
     * eine fehlende Abhaengigkeit kaeme sonst als {@code null} in der Bean an.
     */
    @Mock
    private ch.plaintext.audit.DestructiveActionAuditService auditService;

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

    // ==================== edit() (Zeilen-Button in der Benutzer-Liste) ====================

    @Test
    void edit_shouldSelectRowUserAndLoadDialogData() {
        when(userMandateRepo.findByUsername(testUser.getUsername())).thenReturn(new ArrayList<>());

        bean.edit(testUser);

        assertSame(testUser, bean.getSelected());
        assertEquals(testUser.getPassword(), bean.getMyUserPw());
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
    void getAvailableRoles_shouldIncludeDeclaredRolesFromRegistry() {
        when(roleRegistry.getDeclaredRoleNames()).thenReturn(new LinkedHashSet<>(List.of("admin", "editor")));
        when(repo.findAll()).thenReturn(List.of());

        Set<String> roles = bean.getAvailableRoles();

        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("editor"));
    }

    @Test
    void getAvailableRoles_shouldUnionRegistryAndDatabase() {
        when(roleRegistry.getDeclaredRoleNames()).thenReturn(new LinkedHashSet<>(List.of("admin")));
        MyUserEntity user = new MyUserEntity();
        user.addRole("legacyrole");
        when(repo.findAll()).thenReturn(List.of(user));

        Set<String> roles = bean.getAvailableRoles();

        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("legacyrole"), "Bestandsrolle ohne Deklaration muss sichtbar bleiben");
    }

    @Test
    void getAvailableRoles_shouldSurviveNullRegistry() throws Exception {
        java.lang.reflect.Field field = MyUserBackingBean.class.getDeclaredField("roleRegistry");
        field.setAccessible(true);
        field.set(bean, null);

        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        when(repo.findAll()).thenReturn(List.of(user));

        Set<String> roles = bean.getAvailableRoles();

        assertTrue(roles.contains("admin"));
    }

    // ==================== getSelectableRoles Tests ====================

    @Test
    void getSelectableRoles_shouldCarryDescriptionInLabel() {
        when(roleRegistry.getDeclaredRoleNames()).thenReturn(new LinkedHashSet<>(List.of("admin")));
        when(roleRegistry.getDescription("admin")).thenReturn("Administration");
        when(repo.findAll()).thenReturn(List.of());
        bean.setSelected(testUser);

        List<MyUserBackingBean.RoleOption> options = bean.getSelectableRoles();

        MyUserBackingBean.RoleOption admin = options.stream()
                .filter(o -> o.getName().equals("admin"))
                .findFirst().orElseThrow();
        assertEquals("admin — Administration", admin.getLabel());
    }

    @Test
    void getSelectableRoles_shouldKeepSelectedUndeclaredRoles() {
        when(roleRegistry.getDeclaredRoleNames()).thenReturn(new LinkedHashSet<>(List.of("admin")));
        when(repo.findAll()).thenReturn(List.of());

        testUser.addRole("vergessene_rolle");
        bean.setSelected(testUser);

        List<MyUserBackingBean.RoleOption> options = bean.getSelectableRoles();

        assertTrue(options.stream().anyMatch(o -> o.getName().equals("vergessene_rolle")),
                "Eine zugewiesene, aber nicht (mehr) deklarierte Rolle darf nicht verloren gehen");
        assertTrue(options.stream().anyMatch(o -> o.getName().equals("admin")));
    }

    @Test
    void getSelectableRoles_labelWithoutDescriptionIsPlainName() {
        when(roleRegistry.getDeclaredRoleNames()).thenReturn(new LinkedHashSet<>(List.of("plain")));
        when(roleRegistry.getDescription("plain")).thenReturn("");
        when(repo.findAll()).thenReturn(List.of());
        bean.setSelected(testUser);

        MyUserBackingBean.RoleOption plain = bean.getSelectableRoles().stream()
                .filter(o -> o.getName().equals("plain"))
                .findFirst().orElseThrow();
        assertEquals("plain", plain.getLabel());
    }

    // ==================== newUser() Tests ====================

    /**
     * Forensik 23.08.2026, Punkt 4: der Mandant wird weiterhin vorbelegt — aber die Entity bleibt transient,
     * bis {@code save()} sie wirklich schreibt.
     */
    @Test
    void newUser_shouldCreateWithDefaultMandate_withoutPersisting() {
        bean.newUser();

        assertNotNull(bean.getSelected());
        assertEquals("default", bean.getSelected().getMandat());
        assertNull(bean.getSelected().getId());
        verify(repo, never()).save(any(MyUserEntity.class));
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
