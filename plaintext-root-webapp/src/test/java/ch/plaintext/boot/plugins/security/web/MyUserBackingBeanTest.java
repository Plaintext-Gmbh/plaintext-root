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

    @Test
    void testNewUser_ShouldCreateAndSaveUser() {
        // Given
        MyUserEntity newUser = new MyUserEntity();
        newUser.setId(2L);

        when(repo.save(any(MyUserEntity.class))).thenReturn(newUser);
        when(repo.findAll()).thenReturn(Arrays.asList(newUser));
        when(rememberMeRepo.findAll()).thenReturn(new ArrayList<>());

        // When
        backingBean.newUser();

        // Then
        assertEquals(newUser, backingBean.getSelected());
        verify(repo, times(1)).save(any(MyUserEntity.class));
        verify(repo, times(2)).findAll(); // Called by both init() and extractRolesFromDatabase()
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

}
