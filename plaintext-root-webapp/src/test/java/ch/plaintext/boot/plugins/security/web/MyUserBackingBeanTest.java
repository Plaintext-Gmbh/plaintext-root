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

    /** Forensik 23.08.2026: Audit-Log fuer Rollenaenderungen und Loeschungen. */
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
     * Forensik 23.08.2026, Punkt 4: „Neuer Benutzer" legt KEINE Zeile mehr an. Die frueher sofort
     * persistierte Leer-Entity hinterliess bei jedem abgebrochenen Dialog eine Waisenzeile mit
     * leerem {@code username}.
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
     * Karte 318: Beim Speichern der Zusatz-Mandate muss der Delete VOR den Inserts in die DB (flush
     * dazwischen). Sonst flusht Hibernate die Inserts zuerst und ein re-inserter (username, mandat), der
     * noch als alte Zeile existiert, verletzt den Unique-Index uq_user_mandate (DataIntegrityViolation,
     * z. B. simon+guild42). Sichert zusätzlich ab, dass Heimat-Mandat und Duplikate gefiltert werden.
     */
    @Test
    void saveZusatzMandate_flushtDeleteVorInserts_undFiltertHeimatUndDuplikate() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setUsername("user@example.com");
        testUser.setMandat("lauftage2026"); // Heimat-Mandat
        backingBean.setSelected(testUser);
        // Formular-Liste: guild42 (existiert bereits in der DB), Heimat lauftage2026 (skip),
        // guild42 erneut (Duplikat, skip), plaintext (neu).
        backingBean.setSelectedZusatzMandate(new ArrayList<>(
                Arrays.asList("guild42", "lauftage2026", "guild42", "plaintext")));

        backingBean.saveZusatzMandate();

        org.mockito.InOrder ordered = inOrder(userMandateRepo);
        ordered.verify(userMandateRepo).deleteByUsername("user@example.com");
        ordered.verify(userMandateRepo).flush(); // <-- der Fix: erzwingt Delete vor Insert
        ordered.verify(userMandateRepo, times(2)).save(any()); // nur guild42 + plaintext (home/Dup gefiltert)
        verifyNoMoreInteractions(userMandateRepo);
    }

    // ---- Karte 307, K1: serverseitige Rollen-Allowlist (ADMIN darf sich/andere nicht zu ROOT machen) ----

    @Test
    void save_lehntRootRolleAb_wennAkteurNichtRootIst() {
        // Akteur = ADMIN (setUp: ROLE_root=false, ROLE_admin=true). Angriff: "root" ins Rollenfeld.
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

        // Der ROOT-Grant darf NICHT persistiert werden.
        verify(repo, never()).save(any(MyUserEntity.class));
        verify(facesContext).validationFailed();
    }

    @Test
    void save_erlaubtRootRolle_wennAkteurRootIst() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true); // Akteur = ROOT
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

    // ---- Zustaendigkeitstrennung: admin vergibt Modul-Rollen, root vergibt Verwaltungsrechte ----

    @Test
    void save_erlaubtModulRolle_wennAkteurAdminIst() {
        // Modul-Rollen sind KEINE privilegierten Rollen — sie zu vergeben ist admins Aufgabe.
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
        // Vorher war nur "root" privilegiert — ein admin konnte also weitere admins ernennen und
        // seine eigene Beschraenkung damit aushebeln.
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
        // Bestand bleibt editierbar: die Einschraenkung gilt nur fuer das NEU-Vergeben.
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

    // ---- Forensik 23.08.2026, K1: die ENTZUGSSEITE. Die Allowlist prueft nur, was uebermittelt WURDE —
    // ---- was fehlt, sah bisher niemand. Genau daran verlor ein Administratorkonto still root/admin.

    /**
     * Ein Nicht-root darf privilegierte Rollen ueberhaupt nicht entziehen: harte Ablehnung,
     * nichts wird gespeichert.
     */
    @Test
    void save_lehntEntzugPrivilegierterRolleAb_wennAkteurNichtRootIst() {
        // Akteur = ADMIN (setUp). Der Benutzer hat persistiert 'admin' — die Uebermittlung laesst sie weg.
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
     * Der Vorfall vom Abend: ein <b>root</b>-Akteur entzieht root/admin. Nicht blockieren, aber
     * nicht kommentarlos speichern — es wird eine ausdrueckliche Bestaetigung verlangt.
     */
    @Test
    void save_verlangtBestaetigung_wennRootPrivilegierteRollenEntzieht() {
        when(plaintextSecurity.ifGranted("ROLE_root")).thenReturn(true);
        testUser.setMandat("test_mandat");
        backingBean.setSelected(testUser);
        backingBean.setSelectedRolesList(new ArrayList<>()); // leere Auswahl vom Telefon

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

    /** Nach der Bestaetigung durch root wird gespeichert — und die Aenderung landet im Audit. */
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
            backingBean.save();                                   // 1. Anlauf: Rueckfrage
            assertTrue(backingBean.isRollenEntzugAusstehend());
            backingBean.bestaetigeRollenEntzugUndSpeichere();      // 2. Anlauf: bestaetigt
        }

        verify(repo).save(any(MyUserEntity.class));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).logDestructiveAction(eq("UI"), eq("USER_ROLES_CHANGED"),
                eq("MyUserEntity"), eq("1"), detail.capture());
        assertTrue(detail.getValue().contains("entzogen: [admin, root]"), detail.getValue());
        assertTrue(detail.getValue().contains("root@root.root"), detail.getValue());
        assertFalse(backingBean.isRollenEntzugAusstehend(), "Bestaetigung darf nicht weitergelten");
    }

    /** „Abbrechen" speichert nichts und raeumt den Bestaetigungszustand ab. */
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
     * Ein Mandatswechsel entzieht formal die privilegierte Rolle {@code PROPERTY_MANDAT_*} — dafuer
     * gibt es aber ein eigenes, sichtbares Feld. Eine Rueckfrage bei jedem Mandatswechsel wuerde
     * die Warnung entwerten, deshalb bleibt er aussen vor.
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

    // ---- Forensik 23.08.2026, K2: eine unvollstaendige Uebermittlung darf keinen Totalverlust bedeuten ----

    /**
     * Die im Dialog ausgeblendeten Rollen ({@code PROPERTY_*}, Mandat) kommen aus dem Formular nie
     * zurueck. Vorher loeschte sie jedes Speichern still mit.
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

    // ---- Forensik 23.08.2026, K3: Loeschung ist in Produktion sichtbar und im Audit ----

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

    /** Eine Speicherung ohne Rollenaenderung darf das Audit-Log nicht fluten. */
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
