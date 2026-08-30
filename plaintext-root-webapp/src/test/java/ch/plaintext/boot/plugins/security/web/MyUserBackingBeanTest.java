/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MyUserBackingBean
 */
@ExtendWith(MockitoExtension.class)
class MyUserBackingBeanTest {

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
    private HttpServletRequest httpRequest;

    @Mock
    private UserMandateRepository userMandateRepo;

    @Mock
    private MagicLinkService magicLinkService;

    /** Forensics 23.08.2026: audit log for role changes and deletions. */
    @Mock
    private ch.plaintext.audit.DestructiveActionAuditService auditService;

    @InjectMocks
    private MyUserBackingBean backingBean;

    private MyUserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new MyUserEntity();
        testUser.setId(1L);
        testUser.setUsername("test@example.com");
        testUser.setPassword("$2a$10$hashedPassword");
        testUser.setMandat("test_mandat");

        lenient().when(plaintextSecurity.getMandat()).thenReturn("test_mandat");
        // Mock admin role so init() will call repo.findAll()
        lenient().when(plaintextSecurity.ifGranted("ROLE_admin")).thenReturn(true);
        lenient().when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(false);
    }

    @Test
    void testInit_ShouldLoadUsersAndRememberMes() {
        // Given
        List<MyUserEntity> usersList = new ArrayList<>();
        usersList.add(testUser);
        List<MyRememberMe> rememberMes = new ArrayList<>();

        when(repo.findAll()).thenReturn(usersList);
        when(rememberMeRepo.findAll()).thenReturn(rememberMes);

        // When
        backingBean.init();

        // Then
        assertNotNull(backingBean.getUsers());
        assertEquals(1, backingBean.getUsers().size(),
            "Expected 1 user to be loaded for mandate test_mandat");
        assertEquals(testUser, backingBean.getUsers().get(0));
        verify(repo, times(1)).findAll();
        verify(rememberMeRepo, times(1)).findAll();
    }

    /**
     * Forensics 23.08.2026, item 4: "New user" no longer creates a row. The empty entity that used to
     * be persisted immediately left an orphan row with an empty {@code username} behind on every
     * cancelled dialog.
     */
    @Test
    void testNewUser_ShouldNotPersistEmptyEntity() {
        // When
        backingBean.newUser();

        // Then
        assertNotNull(backingBean.getSelected());
        assertNull(backingBean.getSelected().getId(), "Die neue Entity darf noch keine ID haben");
        assertEquals("default", backingBean.getSelected().getMandat());
        verify(repo, never()).save(any(MyUserEntity.class));
        verify(repo, never()).findAll();
    }

    @Test
    void testSelect_ShouldSetPasswordField() {
        // Given
        backingBean.setSelected(testUser);

        // When
        backingBean.select();

        // Then
        assertEquals(testUser.getPassword(), backingBean.getMyUserPw());
    }

    //@Test
    void testDelete_ShouldDeleteUserAndReload() {
        // Given
        backingBean.setSelected(testUser);
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        // When
        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            backingBean.delete();
        }

        // Then
        assertNull(backingBean.getSelected());
        verify(repo, times(1)).delete(testUser);
        verify(repo, times(1)).findAll();
        verify(facesContext, times(1)).addMessage(isNull(), any(FacesMessage.class));
    }

    //@Test
    void testDeleteRememberMe_ShouldDeleteAndReload() {
        // Given
        MyRememberMe rememberMe = new MyRememberMe();
        backingBean.setSelectedRememberMe(rememberMe);
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        // When
        backingBean.deleteRememberMe();

        // Then
        verify(rememberMeRepo, times(1)).delete(rememberMe);
        verify(repo, times(1)).findAll();
        verify(rememberMeRepo, times(1)).findAll(); // once in init (called by deleteRememberMe)
    }

    @Test
    void testOnToggle_ShouldToggleRemlistColapsed() {
        // Given
        boolean initialState = backingBean.isRemlistcolapsed();

        // When
        backingBean.onToggle();

        // Then
        assertEquals(!initialState, backingBean.isRemlistcolapsed());

        // Toggle again
        backingBean.onToggle();
        assertEquals(initialState, backingBean.isRemlistcolapsed());
    }

    @Test
    void testSendMagicLink_ShouldSendAndShowInfo() {
        // Given
        backingBean.setSelected(testUser);

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            when(externalContext.getRequest()).thenReturn(httpRequest);
            when(magicLinkService.generateAndSend(eq("test@example.com"), any())).thenReturn(true);

            // When
            backingBean.sendMagicLink();

            // Then
            verify(magicLinkService).generateAndSend(eq("test@example.com"), any());
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }
    }

    @Test
    void testSendMagicLink_ShouldShowWarn_WhenNotSent() {
        // Given
        backingBean.setSelected(testUser);

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getExternalContext()).thenReturn(externalContext);
            when(externalContext.getRequest()).thenReturn(httpRequest);
            when(magicLinkService.generateAndSend(eq("test@example.com"), any())).thenReturn(false);

            // When
            backingBean.sendMagicLink();

            // Then
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }
    }

    @Test
    void testHasRememberMeEntries_WhenEntriesExist_ShouldReturnTrue() {
        // Given
        String username = "test@example.com";
        List<MyRememberMe> entries = Arrays.asList(new MyRememberMe());

        when(rememberMeRepo.findAllByUsername(username)).thenReturn(entries);

        // When
        boolean result = backingBean.hasRememberMeEntries(username);

        // Then
        assertTrue(result);
        verify(rememberMeRepo, times(1)).findAllByUsername(username);
    }

    @Test
    void testHasRememberMeEntries_WhenNoEntriesExist_ShouldReturnFalse() {
        // Given
        String username = "test@example.com";
        when(rememberMeRepo.findAllByUsername(username)).thenReturn(new ArrayList<>());

        // When
        boolean result = backingBean.hasRememberMeEntries(username);

        // Then
        assertFalse(result);
        verify(rememberMeRepo, times(1)).findAllByUsername(username);
    }

    @Test
    void testDeleteRememberMeForUser_ShouldDeleteAndReload() {
        // Given
        String username = "test@example.com";
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        // When
        backingBean.deleteRememberMeForUser(username);

        // Then
        verify(rememberMeRepo, times(1)).deleteAllByUsername(username);
        verify(repo, times(1)).findAll();
        verify(rememberMeRepo, times(1)).findAll();
    }

    /**
     * Card 318: when saving the additional tenants the delete has to go into the DB BEFORE the inserts
     * (with a flush in between). Otherwise Hibernate flushes the inserts first and a re-inserted
     * (username, mandat) that still exists as an old row violates the unique index uq_user_mandate
     * (DataIntegrityViolation, e.g. simon+guild42). Additionally guarantees that the home tenant and
     * duplicates are filtered out.
     */
    @Test
    void saveZusatzMandate_flushtDeleteVorInserts_undFiltertHeimatUndDuplikate() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setUsername("user@example.com");
        testUser.setMandat("lauftage2026"); // home tenant
        backingBean.setSelected(testUser);
        // Form list: guild42 (already exists in the DB), home lauftage2026 (skip),
        // guild42 again (duplicate, skip), plaintext (new).
        backingBean.setSelectedZusatzMandate(new ArrayList<>(
                Arrays.asList("guild42", "lauftage2026", "guild42", "plaintext")));

        backingBean.saveZusatzMandate();

        org.mockito.InOrder ordered = inOrder(userMandateRepo);
        ordered.verify(userMandateRepo).deleteByUsername("user@example.com");
        ordered.verify(userMandateRepo).flush(); // <-- the fix: forces the delete before the insert
        ordered.verify(userMandateRepo, times(2)).save(any()); // only guild42 + plaintext (home/duplicate filtered out)
        verifyNoMoreInteractions(userMandateRepo);
    }

    // ---- Card 307, K1: server-side role allowlist (an ADMIN must not make themselves/others ROOT) ----

    @Test
    void save_lehntRootRolleAb_wennAkteurNichtRootIst() {
        // Actor = ADMIN (setUp: ROLE_root=false, ROLE_admin=true). Attack: "root" into the role field.
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(Arrays.asList("admin", "root")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("admin", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        // The ROOT grant must NOT be persisted.
        verify(repo, never()).save(any(MyUserEntity.class));
        verify(facesContext).validationFailed();
    }

    @Test
    void save_erlaubtRootRolle_wennAkteurRootIst() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true); // Actor = ROOT
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(Arrays.asList("admin", "root")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("admin", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo).save(any(MyUserEntity.class));
        verify(facesContext, never()).validationFailed();
    }

    // ---- Separation of responsibilities: admin grants module roles, root grants administration rights ----

    @Test
    void save_erlaubtModulRolle_wennAkteurAdminIst() {
        // Module roles are NOT privileged roles — granting them is the admin's job.
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(Arrays.asList("user", "wiki", "finanzen")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo).save(any(MyUserEntity.class));
        verify(facesContext, never()).validationFailed();
    }

    @Test
    void save_lehntAdminRolleAb_wennAkteurNichtRootIst() {
        // Previously only "root" was privileged — so an admin could appoint further admins and
        // thereby circumvent their own restriction.
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(Arrays.asList("user", "admin")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo, never()).save(any(MyUserEntity.class));
        verify(facesContext).validationFailed();
    }

    @Test
    void save_erlaubtBestehendeAdminRolle_auchFuerNichtRoot() {
        // Existing data stays editable: the restriction only applies to granting anew.
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(Arrays.asList("admin", "wiki")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("admin", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo).save(any(MyUserEntity.class));
        verify(facesContext, never()).validationFailed();
    }

    // ---- Forensics 23.08.2026, K1: the REVOCATION side. The allowlist only checks what WAS submitted —
    // ---- what is missing went unseen so far. Exactly that is how an administrator account silently lost root/admin.

    /**
     * A non-root must not be able to revoke privileged roles at all: hard rejection,
     * nothing is saved.
     */
    @Test
    void save_lehntEntzugPrivilegierterRolleAb_wennAkteurNichtRootIst() {
        // Actor = ADMIN (setUp). The user has 'admin' persisted — the submission omits it.
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(List.of("user")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("admin", "user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo, never()).save(any(MyUserEntity.class));
        verify(facesContext).validationFailed();
        verify(facesContext).addMessage(isNull(), argThat(msg ->
                msg.getSeverity() == FacesMessage.SEVERITY_ERROR
                        && msg.getDetail().contains("admin")));
        assertFalse(backingBean.isRollenEntzugAusstehend(),
                "Ein Nicht-root bekommt keine Rueckfrage, sondern eine Ablehnung");
    }

    /**
     * The incident of that evening: a <b>root</b> actor revokes root/admin. Do not block, but
     * do not save without comment either — an explicit confirmation is required.
     */
    @Test
    void save_verlangtBestaetigung_wennRootPrivilegierteRollenEntzieht() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>()); // empty selection from the phone

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("root", "admin", "user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo, never()).save(any(MyUserEntity.class));
        verify(facesContext).validationFailed();
        assertTrue(backingBean.isRollenEntzugAusstehend());
        assertTrue(backingBean.getRollenEntzugFrage().contains("test@example.com"));
        assertTrue(backingBean.getRollenEntzugFrage().contains("admin"));
        assertTrue(backingBean.getRollenEntzugFrage().contains("root"));
    }

    /** After the confirmation by root it is saved — and the change lands in the audit log. */
    @Test
    void bestaetigterEntzug_speichertUndSchreibtAudit() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        when(plaintextSecurity.getUser()).thenReturn("root@root.root");
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(List.of("user")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("root", "admin", "user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();                                   // 1st attempt: query back
            assertTrue(backingBean.isRollenEntzugAusstehend());
            backingBean.bestaetigeRollenEntzugUndSpeichere();      // 2nd attempt: confirmed
        }

        verify(repo).save(any(MyUserEntity.class));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).logDestructiveAction(eq("UI"), eq("USER_ROLES_CHANGED"),
                eq("MyUserEntity"), eq("1"), detail.capture());
        assertTrue(detail.getValue().contains("entzogen: [admin, root]"), detail.getValue());
        assertTrue(detail.getValue().contains("root@root.root"), detail.getValue());
        assertFalse(backingBean.isRollenEntzugAusstehend(), "Bestaetigung darf nicht weitergelten");
    }

    /** "Cancel" saves nothing and clears the confirmation state. */
    @Test
    void brichRollenEntzugAb_speichertNichtsUndSetztZustandZurueck() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(List.of("user")));

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("root", "user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
            assertTrue(backingBean.isRollenEntzugAusstehend());
            backingBean.brichRollenEntzugAb();
        }

        verify(repo, never()).save(any(MyUserEntity.class));
        verify(auditService, never()).logDestructiveAction(any(), any(), any(), any(), any());
        assertFalse(backingBean.isRollenEntzugAusstehend());
    }

    /**
     * A tenant switch formally revokes the privileged role {@code PROPERTY_MANDAT_*} — but there
     * is a separate, visible field for that. A query back on every tenant switch would
     * devalue the warning, so it stays out of scope.
     */
    @Test
    void save_fragtNichtNach_beiReinemMandatswechsel() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>(List.of("user")));
        testUser.setMandat("neuer_mandant");

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo).save(any(MyUserEntity.class));
        assertFalse(backingBean.isRollenEntzugAusstehend());
    }

    // ---- Forensics 23.08.2026, K2: an incomplete submission must not mean a total loss ----

    /**
     * The roles hidden in the dialog ({@code PROPERTY_*}, tenant) never come back from the form.
     * Previously every save silently deleted them along the way.
     */
    @Test
    void setSelectedRolesList_bewahrtImDialogAusgeblendeteRollen() {
        testUser.setRoles(new HashSet<>(Arrays.asList(
                "user", "admin", "PROPERTY_QUERZUGRIFF", "PROPERTY_MANDAT_TEST_MANDAT")));
        backingBean.setSelected(testUser);

        backingBean.setSelectedRolesList(new ArrayList<>(List.of("user")));

        assertTrue(testUser.getRoles().contains("PROPERTY_QUERZUGRIFF"),
                "PROPERTY_-Rollen sind im Dialog unsichtbar und duerfen nicht mitgeloescht werden");
        assertTrue(testUser.getRoles().contains("PROPERTY_MANDAT_TEST_MANDAT"));
        assertTrue(testUser.getRoles().contains("user"));
        assertFalse(testUser.getRoles().contains("admin"),
                "Sichtbare, abgewaehlte Rollen bleiben abgewaehlt — darueber entscheidet der Entzugs-Schutz");
    }

    // ---- Forensics 23.08.2026, K3: a deletion is visible in production and in the audit log ----

    @Test
    void delete_schreibtAuditEintrag() {
        when(plaintextSecurity.getUser()).thenReturn("admin@example.com");
        testUser.setRoles(new HashSet<>(Arrays.asList("user", "PROPERTY_MANDAT_TEST_MANDAT")));
        backingBean.setSelected(testUser);
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.delete();
        }

        verify(repo).delete(testUser);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).logDestructiveAction(eq("UI"), eq("USER_DELETE"),
                eq("MyUserEntity"), eq("1"), detail.capture());
        assertTrue(detail.getValue().contains("test@example.com"), detail.getValue());
        assertTrue(detail.getValue().contains("admin@example.com"), detail.getValue());
    }

    @Test
    void delete_schreibtKeinAudit_wennLoeschenFehlschlaegt() {
        backingBean.setSelected(testUser);
        doThrow(new RuntimeException("DB weg")).when(repo).delete(any());
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.delete();
        }

        verify(auditService, never()).logDestructiveAction(any(), any(), any(), any(), any());
    }

    /** A save without a role change must not flood the audit log. */
    @Test
    void save_schreibtKeinAudit_ohneRollenaenderung() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setMandat("test_mandat");
        testUser.addRole("user");
        backingBean.setSelected(testUser);
        backingBean.setMyUserPw(testUser.getPassword());

        MyUserEntity persisted = new MyUserEntity();
        persisted.setId(1L);
        persisted.setUsername("test@example.com");
        persisted.setRoles(new HashSet<>(Arrays.asList("user", "PROPERTY_MANDAT_TEST_MANDAT")));
        when(repo.findById(1L)).thenReturn(Optional.of(persisted));
        when(repo.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findAll()).thenReturn(new ArrayList<>());
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        try (MockedStatic<FacesContext> facesContextMock = mockStatic(FacesContext.class)) {
            facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            backingBean.save();
        }

        verify(repo).save(any(MyUserEntity.class));
        verify(auditService, never()).logDestructiveAction(any(), any(), any(), any(), any());
    }

}
