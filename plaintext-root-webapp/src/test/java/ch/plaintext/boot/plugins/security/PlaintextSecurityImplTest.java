/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import ch.plaintext.boot.plugins.security.impersonation.ImpersonationAudit;
import ch.plaintext.boot.plugins.security.impersonation.ImpersonationAuditRepository;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test class for PlaintextSecurityImpl - the core security component.
 */
@ExtendWith(MockitoExtension.class)
class PlaintextSecurityImplTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private MandateMenuConfigRepository mandateMenuConfigRepository;

    @Mock
    private UserMandateRepository userMandateRepo;

    @Mock
    private ImpersonationAuditRepository impersonationAuditRepository;

    @InjectMocks
    private PlaintextSecurityImpl plaintextSecurity;

    @Mock
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        // Setup security context mock
        SecurityContextHolder.setContext(securityContext);

        // Stub mandateMenuConfigRepository to return empty list by default
        lenient().when(mandateMenuConfigRepository.findAll()).thenReturn(Collections.emptyList());

        // Manually trigger PostConstruct using reflection
        try {
            java.lang.reflect.Method initMethod = PlaintextSecurityImpl.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(plaintextSecurity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize PlaintextSecurityImpl", e);
        }
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Sets a mock HttpServletRequest WITH an existing session (for getCurrentSession()). */
    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    // ==================== Multi-tenant / switchActiveMandat Tests =====================

    private void authWith(String username, String... authorities) {
        List<GrantedAuthority> list = new ArrayList<>();
        for (String a : authorities) {
            list.add(new SimpleGrantedAuthority(a));
        }
        when(securityContext.getAuthentication())
                .thenReturn(new UsernamePasswordAuthenticationToken(username, "password", list));
    }

    @Test
    void canSwitchMandat_shouldBeTrue_forRoot_withMultipleMandate() {
        authWith("root@x.ch", "ROLE_ROOT", "PROPERTY_MANDAT_alpha");
        MyUserEntity alpha = new MyUserEntity();
        alpha.setMandat("alpha");
        MyUserEntity beta = new MyUserEntity();
        beta.setMandat("beta");
        when(userRepository.findAll()).thenReturn(List.of(alpha, beta));

        assertTrue(plaintextSecurity.isCanSwitchMandat());
    }

    @Test
    void canSwitchMandat_shouldBeFalse_forRoot_withOnlyOneMandatInInstance() {
        // Bugfix (Daniel 21.07.2026): the switcher should disappear, not merely be disabled,
        // when there is only ONE tenant instance-wide -- also for ROOT (previously ALWAYS visible for ROOT).
        authWith("root@x.ch", "ROLE_ROOT", "PROPERTY_MANDAT_alpha");
        // userRepository.findAll() returns an empty list by default -> getAllMandate()
        // falls back to exactly {"default"} (see PlaintextSecurityImpl.getAllMandate()).

        assertFalse(plaintextSecurity.isCanSwitchMandat());
    }

    @Test
    void getAllowedMandate_shouldIncludeHomeAndExtras_forNonRoot() {
        authWith("u@x.ch", "ROLE_USER", "PROPERTY_MANDAT_alpha");
        UserMandate um = new UserMandate();
        um.setUsername("u@x.ch");
        um.setMandat("beta");
        um.setActive(true);
        when(userMandateRepo.findByUsernameAndActiveTrue("u@x.ch")).thenReturn(List.of(um));

        Set<String> allowed = plaintextSecurity.getAllowedMandate();

        assertTrue(allowed.contains("alpha"), "Heimat-Mandant enthalten");
        assertTrue(allowed.contains("beta"), "Zusatz-Mandant enthalten");
        assertEquals(2, allowed.size());
        assertTrue(plaintextSecurity.isCanSwitchMandat());
    }

    @Test
    void canSwitchMandat_shouldBeFalse_forSingleMandantUser() {
        authWith("u@x.ch", "ROLE_USER", "PROPERTY_MANDAT_alpha");
        // userMandateRepo returns an empty list by default -> home tenant only
        assertFalse(plaintextSecurity.isCanSwitchMandat());
    }

    @Test
    void switchActiveMandat_shouldSwapAuthority_whenAllowed() {
        authWith("u@x.ch", "ROLE_USER", "PROPERTY_MANDAT_alpha");
        UserMandate um = new UserMandate();
        um.setUsername("u@x.ch");
        um.setMandat("beta");
        um.setActive(true);
        when(userMandateRepo.findByUsernameAndActiveTrue("u@x.ch")).thenReturn(List.of(um));

        plaintextSecurity.switchActiveMandat("beta");

        // Check the result in the holder (the switch sets a new context instead of calling
        // setAuthentication on the old context).
        Authentication updated = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(updated.getAuthorities().stream()
                .anyMatch(x -> "PROPERTY_MANDAT_beta".equals(x.getAuthority())), "neuer Mandant beta gesetzt");
        assertTrue(updated.getAuthorities().stream()
                .noneMatch(x -> "PROPERTY_MANDAT_alpha".equals(x.getAuthority())), "alter Mandant alpha entfernt");
    }

    @Test
    void switchActiveMandat_shouldDoNothing_whenNotAllowed() {
        authWith("u@x.ch", "ROLE_USER", "PROPERTY_MANDAT_alpha");
        // 'gamma' is not permitted (not ROOT, no additional tenant)
        plaintextSecurity.switchActiveMandat("gamma");

        verify(securityContext, never()).setAuthentication(any());
    }

    @Test
    void switchActiveMandat_persistiertInDb_undSichertVorigesMandat() {
        // "remember permanently": the chosen tenant is persisted in the DB role (robust against
        // remember-me/a new session), the previous home tenant stays as a switchable UserMandate.
        authWith("u@x.ch", "ROLE_USER", "PROPERTY_MANDAT_alpha", "PROPERTY_MYUSERID_5");
        UserMandate beta = new UserMandate();
        beta.setUsername("u@x.ch");
        beta.setMandat("beta");
        beta.setActive(true);
        when(userMandateRepo.findByUsernameAndActiveTrue("u@x.ch")).thenReturn(List.of(beta));
        when(userMandateRepo.findByUsername("u@x.ch")).thenReturn(List.of(beta)); // alpha NOT yet a UserMandate
        MyUserEntity user = new MyUserEntity();
        user.setId(5L);
        user.setMandat("alpha");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        plaintextSecurity.switchActiveMandat("beta");

        // DB tenant role persisted to beta
        verify(userRepository).save(argThat(u -> "beta".equals(u.getMandat())));
        // previous home tenant (alpha) preserved as a switchable UserMandate
        verify(userMandateRepo).save(argThat(um -> "alpha".equals(um.getMandat()) && um.isActive()));
    }

    @Test
    void getUsernamesWithMandatAccess_includesHomeAndAdditional() {
        MyUserEntity home = new MyUserEntity();
        home.setUsername("home@x.ch");
        home.setMandat("event");
        when(userRepository.findAll()).thenReturn(List.of(home));
        UserMandate um = new UserMandate();
        um.setUsername("extra@x.ch");
        um.setMandat("event");
        um.setActive(true);
        when(userMandateRepo.findByMandatAndActiveTrue("event")).thenReturn(List.of(um));

        List<String> users = plaintextSecurity.getUsernamesWithMandatAccess("event");

        assertTrue(users.contains("home@x.ch"), "Heimat-Mandant-Benutzer enthalten");
        assertTrue(users.contains("extra@x.ch"), "Zusatz-Mandant-Benutzer enthalten");
        assertEquals(2, users.size());
    }

    // ==================== getMandat() Tests ====================

    @Test
    void getMandat_shouldReturnMandatFromRoles_whenMandatRoleExists() {
        // Given: User has a mandat role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        String mandat = plaintextSecurity.getMandat();

        // Then
        assertEquals("dev", mandat);
    }

    @Test
    void getMandat_shouldReturnDefault_whenNoMandatRoleExists() {
        // Given: User has no mandat role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        String mandat = plaintextSecurity.getMandat();

        // Then
        assertEquals("default", mandat);
    }

    @Test
    void getMandat_shouldReturnNoAuth_whenAuthenticationIsNull() {
        // Given: No authentication
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        String mandat = plaintextSecurity.getMandat();

        // Then
        assertEquals("NO_AUTH", mandat);
    }

    @Test
    void getMandat_shouldHandleMandatWithMultipleUnderscores() {
        // Given: Mandat with multiple underscores
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MANDAT_some_complex_mandat")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        String mandat = plaintextSecurity.getMandat();

        // Then
        assertEquals("mandat", mandat); // Last part after split
    }

    @Test
    void getMandat_shouldBeCaseInsensitive() {
        // Given: Uppercase MANDAT role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MANDAT_PRODUCTION")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        String mandat = plaintextSecurity.getMandat();

        // Then
        assertEquals("production", mandat);
    }

    // ==================== getAllMandate() Tests ====================

    @Test
    void getAllMandate_shouldReturnAllUniqueMandateFromUsers() {
        // Given: Multiple users with different mandates
        MyUserEntity user1 = new MyUserEntity();
        user1.setMandat("dev");

        MyUserEntity user2 = new MyUserEntity();
        user2.setMandat("prod");

        MyUserEntity user3 = new MyUserEntity();
        user3.setMandat("dev"); // Duplicate

        List<MyUserEntity> users = Arrays.asList(user1, user2, user3);
        when(userRepository.findAll()).thenReturn(users);

        // When
        Set<String> mandante = plaintextSecurity.getAllMandate();

        // Then
        assertEquals(2, mandante.size());
        assertTrue(mandante.contains("dev"));
        assertTrue(mandante.contains("prod"));
    }

    @Test
    void getAllMandate_shouldIgnoreEmptyAndNullMandates() {
        // Given: Users with empty/null mandates
        MyUserEntity user1 = new MyUserEntity();
        user1.setMandat("dev");

        MyUserEntity user2 = new MyUserEntity();
        user2.setMandat(null);

        MyUserEntity user3 = new MyUserEntity();
        user3.setMandat("  ");

        MyUserEntity user4 = new MyUserEntity();
        user4.setMandat("");

        List<MyUserEntity> users = Arrays.asList(user1, user2, user3, user4);
        when(userRepository.findAll()).thenReturn(users);

        // When
        Set<String> mandante = plaintextSecurity.getAllMandate();

        // Then
        assertEquals(1, mandante.size());
        assertTrue(mandante.contains("dev"));
    }

    @Test
    void getAllMandate_shouldReturnDefaultWhenNoUsersFound() {
        // Given: Empty user list
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        // When
        Set<String> mandante = plaintextSecurity.getAllMandate();

        // Then
        assertEquals(1, mandante.size());
        assertTrue(mandante.contains("default"));
    }

    @Test
    void getAllMandate_shouldReturnDefaultOnDatabaseError() {
        // Given: Database error
        when(userRepository.findAll()).thenThrow(new RuntimeException("Database error"));

        // When
        Set<String> mandante = plaintextSecurity.getAllMandate();

        // Then
        assertEquals(1, mandante.size());
        assertTrue(mandante.contains("default"));
    }

    @Test
    void getAllMandate_shouldNormalizeMandatesToLowercase() {
        // Given: Users with mixed case mandates
        MyUserEntity user1 = new MyUserEntity();
        user1.setMandat("DEV");

        MyUserEntity user2 = new MyUserEntity();
        user2.setMandat("Dev");

        MyUserEntity user3 = new MyUserEntity();
        user3.setMandat("dev");

        List<MyUserEntity> users = Arrays.asList(user1, user2, user3);
        when(userRepository.findAll()).thenReturn(users);

        // When
        Set<String> mandante = plaintextSecurity.getAllMandate();

        // Then
        assertEquals(1, mandante.size()); // All should be normalized to "dev"
        assertTrue(mandante.contains("dev"));
    }

    // ==================== setMandat() Tests ====================

    @Test
    void setMandat_shouldUpdateSecurityContextAndDatabase() {
        // Given: Authenticated user with existing mandat
        List<GrantedAuthority> authorities = new ArrayList<>(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_old"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123")
        ));
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        user.setMandat("old");
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(MyUserEntity.class))).thenReturn(user);

        // When
        plaintextSecurity.setMandat("new");

        // Then: Verify database update
        verify(userRepository).findById(123L);
        verify(userRepository).save(argThat(u -> "new".equals(u.getMandat())));

        // Then: Verify security context update (new context in the holder)
        Authentication updated = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(updated.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MANDAT_new")));
    }

    @Test
    void setMandat_shouldRemoveOldMandatRole() {
        // Given: User with existing mandat
        List<GrantedAuthority> authorities = new ArrayList<>(Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_old1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_old2"), // Multiple old mandats
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123")
        ));
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        // When
        plaintextSecurity.setMandat("new");

        // Then: Verify old mandat roles are removed (exactly one tenant role remains)
        Authentication updated = SecurityContextHolder.getContext().getAuthentication();
        long mandatCount = updated.getAuthorities().stream()
                .filter(a -> a.getAuthority().toLowerCase().contains("mandat"))
                .count();
        assertEquals(1, mandatCount);
    }

    @Test
    void setMandat_shouldDoNothingWhenNoAuthentication() {
        // Given: No authentication
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        plaintextSecurity.setMandat("new");

        // Then: Nothing should be saved
        verify(userRepository, never()).save(any());
        verify(securityContext, never()).setAuthentication(any());
    }

    @Test
    void setMandat_shouldNotPersistWhenUserNotFound() {
        // Given: Authentication exists but user not in DB
        List<GrantedAuthority> authorities = new ArrayList<>(Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_999")
        ));
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        plaintextSecurity.setMandat("new");

        // Then: Should update context but not save to DB
        verify(userRepository).findById(999L);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setMandat_shouldNormalizeMandatToLowercase() {
        // Given
        List<GrantedAuthority> authorities = new ArrayList<>(Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123")
        ));
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        // When
        plaintextSecurity.setMandat("UPPERCASE");

        // Then: Should be stored as lowercase (in the new holder context)
        Authentication updated = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(updated.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MANDAT_uppercase")));
    }

    // ==================== getId() Tests ====================

    @Test
    void getId_shouldReturnUserIdFromRoles() {
        // Given: User with myuserid role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_12345")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        Long id = plaintextSecurity.getId();

        // Then
        assertEquals(12345L, id);
    }

    @Test
    void getId_shouldReturnMinusOneWhenNoUserIdRole() {
        // Given: User without myuserid role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        Long id = plaintextSecurity.getId();

        // Then
        assertEquals(-1L, id);
    }

    @Test
    void getId_shouldExtractOnlyDigitsFromRole() {
        // Given: Role with mixed alphanumeric
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_abc123xyz")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        Long id = plaintextSecurity.getId();

        // Then
        assertEquals(123L, id);
    }

    @Test
    void getId_shouldReturnMinusOneOnError() {
        // Given: Null authentication
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        Long id = plaintextSecurity.getId();

        // Then
        assertEquals(-1L, id);
    }

    @Test
    void getId_shouldBeCaseInsensitive() {
        // Given: Uppercase role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_999")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        Long id = plaintextSecurity.getId();

        // Then
        assertEquals(999L, id);
    }

    // ==================== getUser() Tests ====================

    @Test
    void getUser_shouldReturnUsername() {
        // Given: Authenticated user
        Authentication auth = new UsernamePasswordAuthenticationToken("john.doe@example.com", "password", java.util.Collections.emptyList());
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        String username = plaintextSecurity.getUser();

        // Then
        assertEquals("john.doe@example.com", username);
    }

    @Test
    void getUser_shouldReturnErrorOnException() {
        // Given: Null authentication
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        String username = plaintextSecurity.getUser();

        // Then
        assertEquals("SYSTEM", username);
    }

    // ==================== getMandatForUser() Tests ====================

    @Test
    void getMandatForUser_shouldReturnMandatFromDatabase() {
        // Given: User exists in database
        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        user.setMandat("production");
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        // When
        String mandat = plaintextSecurity.getMandatForUser(123L);

        // Then
        assertEquals("production", mandat);
    }

    @Test
    void getMandatForUser_shouldReturnNullWhenUserNotFound() {
        // Given: User doesn't exist
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        String mandat = plaintextSecurity.getMandatForUser(999L);

        // Then
        assertNull(mandat);
    }

    @Test
    void getMandatForUser_shouldReturnNullOnDatabaseError() {
        // Given: Database error
        when(userRepository.findById(any())).thenThrow(new RuntimeException("Database error"));

        // When
        String mandat = plaintextSecurity.getMandatForUser(123L);

        // Then
        assertNull(mandat);
    }

    // ==================== getUsernameForUser() / getEmailForUser() Tests (card 596) ====================

    @Test
    void getUsernameForUser_shouldReturnUsernameFromDatabase() {
        // Given: User exists in database
        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        user.setUsername("daniel@plaintext.ch");
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        // When
        String username = plaintextSecurity.getUsernameForUser(123L);

        // Then
        assertEquals("daniel@plaintext.ch", username);
    }

    @Test
    void getUsernameForUser_shouldReturnNullWhenUserNotFound() {
        // Given: User doesn't exist
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        String username = plaintextSecurity.getUsernameForUser(999L);

        // Then
        assertNull(username);
    }

    @Test
    void getUsernameForUser_shouldReturnNullOnDatabaseError() {
        // Given: Database error
        when(userRepository.findById(any())).thenThrow(new RuntimeException("Database error"));

        // When
        String username = plaintextSecurity.getUsernameForUser(123L);

        // Then: fail-soft — the caller should be able to carry on working, not abort
        assertNull(username);
    }

    /**
     * Card 596: the regular case — the user name IS the mail address (the self-registration
     * sets it that way, the password reset sends to it).
     */
    @Test
    void getEmailForUser_shouldReturnAddressWhenUsernameIsMail() {
        MyUserEntity user = new MyUserEntity();
        user.setId(123L);
        user.setUsername("owner@plaintext.ch");
        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        assertEquals(Optional.of("owner@plaintext.ch"), plaintextSecurity.getEmailForUser(123L));
    }

    /**
     * Card 596: the case this is about — legacy data without a mail form. It does NOT throw and
     * does NOT return the unusable value, but an empty one: the caller finishes the count
     * anyway and logs the unreachable owner as a finding.
     */
    @Test
    void getEmailForUser_shouldBeEmptyForLegacyUsername() {
        MyUserEntity user = new MyUserEntity();
        user.setId(5L);
        user.setUsername("plafferma");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        assertTrue(plaintextSecurity.getEmailForUser(5L).isEmpty());
    }

    @Test
    void getEmailForUser_shouldBeEmptyWhenUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertTrue(plaintextSecurity.getEmailForUser(999L).isEmpty());
    }

    // ==================== ifGranted() Tests ====================

    @Test
    void ifGranted_shouldReturnTrueWhenRoleExists() {
        // Given: User has the role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_USER")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When & Then
        assertTrue(plaintextSecurity.ifGranted("ADMIN"));
        assertTrue(plaintextSecurity.ifGranted("USER"));
    }

    @Test
    void ifGranted_shouldReturnFalseWhenRoleDoesNotExist() {
        // Given: User doesn't have the role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When & Then
        assertFalse(plaintextSecurity.ifGranted("ADMIN"));
    }

    @Test
    void ifGranted_shouldHandleRoleWithOrWithoutPrefix() {
        // Given: User has role
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When & Then
        assertTrue(plaintextSecurity.ifGranted("ADMIN"));
        assertTrue(plaintextSecurity.ifGranted("ROLE_ADMIN"));
    }

    @Test
    void ifGranted_shouldReturnFalseForNullRole() {
        // Given: Any authentication (no need to stub as null check happens first)
        // When & Then
        assertFalse(plaintextSecurity.ifGranted(null));
    }

    @Test
    void ifGranted_shouldBeCaseInsensitive() {
        // Given: User has role in uppercase
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When & Then
        assertTrue(plaintextSecurity.ifGranted("admin"));
        assertTrue(plaintextSecurity.ifGranted("Admin"));
        assertTrue(plaintextSecurity.ifGranted("ADMIN"));
    }

    // ==================== getAuthentication() Tests ====================

    @Test
    void getAuthentication_shouldReturnCurrentAuthentication() {
        // Given: Authentication exists
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password");
        when(securityContext.getAuthentication()).thenReturn(auth);

        // When
        Authentication result = plaintextSecurity.getAuthentication();

        // Then
        assertSame(auth, result);
    }

    @Test
    void getAuthentication_shouldReturnNullWhenNoAuthentication() {
        // Given: No authentication
        when(securityContext.getAuthentication()).thenReturn(null);

        // When
        Authentication result = plaintextSecurity.getAuthentication();

        // Then
        assertNull(result);
    }

    // ==================== startImpersonation() / stopImpersonation() Tests ====================

    @Test
    void startImpersonation_deniedForNonRoot_noAuditRecorded() {
        requestWithSession();
        authWith("admin@x.ch", "ROLE_ADMIN", "PROPERTY_MYUSERID_1");

        plaintextSecurity.startImpersonation(2L);

        verify(impersonationAuditRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void startImpersonation_asRoot_switchesAuthAndRecordsAudit() {
        requestWithSession();
        authWith("root@x.ch", "ROLE_ROOT", "PROPERTY_MYUSERID_1");

        MyUserEntity target = new MyUserEntity();
        target.setId(2L);
        target.setUsername("target@x.ch");
        target.setRoles(new HashSet<>(List.of("USER")));
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        plaintextSecurity.startImpersonation(2L);

        // The authentication in the context was switched to the target user (securityContext is a mock without
        // field backing of its own -- intercept setAuthentication(...) instead of reading back via getAuthentication())
        ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);
        verify(securityContext).setAuthentication(authCaptor.capture());
        assertEquals("target@x.ch", authCaptor.getValue().getName());

        ArgumentCaptor<ImpersonationAudit> captor = ArgumentCaptor.forClass(ImpersonationAudit.class);
        verify(impersonationAuditRepository).save(captor.capture());
        ImpersonationAudit saved = captor.getValue();
        assertEquals(1L, saved.getAdminUserId());
        assertEquals("root@x.ch", saved.getAdminUsername());
        assertEquals(2L, saved.getTargetUserId());
        assertEquals("target@x.ch", saved.getTargetUsername());
        assertNotNull(saved.getStartedAt());
        assertNull(saved.getEndedAt());
    }

    @Test
    void stopImpersonation_closesOpenAuditEntry() {
        MockHttpServletRequest request = requestWithSession();
        request.getSession().setAttribute("impersonation.originalUserId", 1L);
        request.getSession().setAttribute("impersonation.originalAuth",
                new UsernamePasswordAuthenticationToken("root@x.ch", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_ROOT"))));

        ImpersonationAudit openEntry = new ImpersonationAudit();
        openEntry.setAdminUserId(1L);
        when(impersonationAuditRepository.findFirstByAdminUserIdAndEndedAtIsNullOrderByStartedAtDesc(1L))
                .thenReturn(Optional.of(openEntry));

        plaintextSecurity.stopImpersonation();

        ArgumentCaptor<ImpersonationAudit> captor = ArgumentCaptor.forClass(ImpersonationAudit.class);
        verify(impersonationAuditRepository).save(captor.capture());
        assertNotNull(captor.getValue().getEndedAt());
    }
}
