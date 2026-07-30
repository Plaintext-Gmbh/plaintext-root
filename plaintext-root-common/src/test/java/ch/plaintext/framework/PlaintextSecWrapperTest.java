/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaintextSecWrapperTest {

    @Mock
    private PlaintextAuthentication sec;

    @Mock
    private Environment environment;

    @InjectMocks
    private PlaintextSecWrapper wrapper;

    @BeforeEach
    void setUp() {
        // Simulate @PostConstruct to set the static instance
        try {
            java.lang.reflect.Method init = PlaintextSecWrapper.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(wrapper);
        } catch (Exception e) {
            // If reflection fails, the test can still proceed for non-static methods
        }
    }

    // -------------------------------------------------------------------------
    // isSecurityEnabled
    // -------------------------------------------------------------------------

    @Test
    void isSecurityEnabled_withNosecurityProfile_returnsFalse() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertFalse(wrapper.isSecurityEnabled());
    }

    @Test
    void isSecurityEnabled_withOtherProfile_andSecPresent_returnsTrue() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        assertTrue(wrapper.isSecurityEnabled());
    }

    @Test
    void isSecurityEnabled_withNoSecBean_returnsFalse() throws Exception {
        // Set sec to null via reflection
        java.lang.reflect.Field secField = PlaintextSecWrapper.class.getDeclaredField("sec");
        secField.setAccessible(true);
        secField.set(wrapper, null);

        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        assertFalse(wrapper.isSecurityEnabled());
    }

    // -------------------------------------------------------------------------
    // isAutenticated
    // -------------------------------------------------------------------------

    @Test
    void isAutenticated_securityDisabled_returnsTrue() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertTrue(wrapper.isAutenticated());
    }

    @Test
    void isAutenticated_securityEnabled_delegatesToSec() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(sec.isAutenticated()).thenReturn(true);
        assertTrue(wrapper.isAutenticated());
    }

    @Test
    void isAutenticated_securityEnabled_notAuthenticated() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(sec.isAutenticated()).thenReturn(false);
        assertFalse(wrapper.isAutenticated());
    }

    // -------------------------------------------------------------------------
    // hasRole
    // -------------------------------------------------------------------------

    @Test
    void hasRole_securityDisabled_returnsTrue() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertTrue(wrapper.hasRole("ADMIN"));
    }

    @Test
    void hasRole_securityEnabled_delegatesToSec() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(sec.hasRole("ADMIN")).thenReturn(true);
        assertTrue(wrapper.hasRole("ADMIN"));
    }

    @Test
    void hasRole_securityEnabled_noRole() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(sec.hasRole("ADMIN")).thenReturn(false);
        assertFalse(wrapper.hasRole("ADMIN"));
    }

    @Test
    void hasRole_securityEnabled_throwsException_returnsFalse() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(sec.hasRole("ADMIN")).thenThrow(new RuntimeException("test error"));
        assertFalse(wrapper.hasRole("ADMIN"));
    }

    // -------------------------------------------------------------------------
    // getAllUsers
    // -------------------------------------------------------------------------

    @Test
    void getAllUsers_securityDisabled_returnsEmptyList() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertTrue(wrapper.getAllUsers().isEmpty());
    }

    @Test
    void getAllUsers_securityEnabled_delegatesToSec() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(sec.getAllUsers()).thenReturn(List.of(mockUser));

        List<PlaintextUser> result = wrapper.getAllUsers();
        assertEquals(1, result.size());
        assertSame(mockUser, result.get(0));
    }

    // -------------------------------------------------------------------------
    // getAllUsersOrigMap
    // -------------------------------------------------------------------------

    @Test
    void getAllUsersOrigMap_securityDisabled_returnsEmptyMap() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertTrue(wrapper.getAllUsersOrigMap().isEmpty());
    }

    @Test
    void getAllUsersOrigMap_securityEnabled_returnsMappedByOrigId() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});

        PlaintextUser user1 = mock(PlaintextUser.class);
        when(user1.getOrigId()).thenReturn(10L);
        PlaintextUser user2 = mock(PlaintextUser.class);
        when(user2.getOrigId()).thenReturn(20L);
        when(sec.getAllUsers()).thenReturn(List.of(user1, user2));

        Map<Long, PlaintextUser> result = wrapper.getAllUsersOrigMap();
        assertEquals(2, result.size());
        assertSame(user1, result.get(10L));
        assertSame(user2, result.get(20L));
    }

    // -------------------------------------------------------------------------
    // getAllUsersEmailMap
    // -------------------------------------------------------------------------

    @Test
    void getAllUsersEmailMap_securityDisabled_returnsEmptyMap() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"nosecurity"});
        assertTrue(wrapper.getAllUsersEmailMap().isEmpty());
    }

    @Test
    void getAllUsersEmailMap_securityEnabled_returnsMappedByEmail() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});

        PlaintextUser user1 = mock(PlaintextUser.class);
        when(user1.getMail()).thenReturn("a@test.com");
        PlaintextUser user2 = mock(PlaintextUser.class);
        when(user2.getMail()).thenReturn("b@test.com");
        when(sec.getAllUsers()).thenReturn(List.of(user1, user2));

        Map<String, PlaintextUser> result = wrapper.getAllUsersEmailMap();
        assertEquals(2, result.size());
        assertSame(user1, result.get("a@test.com"));
        assertSame(user2, result.get("b@test.com"));
    }

    // -------------------------------------------------------------------------
    // getUsername
    // -------------------------------------------------------------------------

    @Test
    void getUsername_withSecAndUsername_returnsUsername() {
        when(sec.getUsername()).thenReturn("admin");
        assertEquals("admin", wrapper.getUsername());
    }

    @Test
    void getUsername_withSecNullUsername_returnsNoUser() {
        when(sec.getUsername()).thenReturn(null);
        assertEquals("no-user", wrapper.getUsername());
    }

    @Test
    void getUsername_withNoSecBean_returnsNoUser() throws Exception {
        java.lang.reflect.Field secField = PlaintextSecWrapper.class.getDeclaredField("sec");
        secField.setAccessible(true);
        secField.set(wrapper, null);
        assertEquals("no-user", wrapper.getUsername());
    }

    // -------------------------------------------------------------------------
    // getUser() (no-arg)
    // -------------------------------------------------------------------------

    @Test
    void getUser_noArg_withNoSecBean_returnsNull() throws Exception {
        java.lang.reflect.Field secField = PlaintextSecWrapper.class.getDeclaredField("sec");
        secField.setAccessible(true);
        secField.set(wrapper, null);
        assertNull(wrapper.getUser());
    }

    @Test
    void getUser_noArg_withSec_delegatesWithFalse() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(sec.getUser(false)).thenReturn(mockUser);
        assertSame(mockUser, wrapper.getUser());
        verify(sec).getUser(false);
    }

    // -------------------------------------------------------------------------
    // getAllRoles
    // -------------------------------------------------------------------------

    @Test
    void getAllRoles_combinesFromAllProviders() throws Exception {
        PlaintextRoleProvider provider1 = mock(PlaintextRoleProvider.class);
        when(provider1.getRoles()).thenReturn(Set.of("ROLE_A", "ROLE_B"));

        PlaintextRoleProvider provider2 = mock(PlaintextRoleProvider.class);
        when(provider2.getRoles()).thenReturn(Set.of("ROLE_B", "ROLE_C"));

        java.lang.reflect.Field rpField = PlaintextSecWrapper.class.getDeclaredField("roleProviders");
        rpField.setAccessible(true);
        rpField.set(wrapper, List.of(provider1, provider2));

        Set<String> result = wrapper.getAllRoles();
        assertEquals(3, result.size());
        assertTrue(result.contains("ROLE_A"));
        assertTrue(result.contains("ROLE_B"));
        assertTrue(result.contains("ROLE_C"));
    }

    // -------------------------------------------------------------------------
    // getAllRolesAsList
    // -------------------------------------------------------------------------

    @Test
    void getAllRolesAsList_filtersByQuery() throws Exception {
        PlaintextRoleProvider provider = mock(PlaintextRoleProvider.class);
        when(provider.getRoles()).thenReturn(Set.of("ROLE_ADMIN", "ROLE_USER", "ROLE_AUDITOR"));

        java.lang.reflect.Field rpField = PlaintextSecWrapper.class.getDeclaredField("roleProviders");
        rpField.setAccessible(true);
        rpField.set(wrapper, List.of(provider));

        List<String> result = wrapper.getAllRolesAsList("admin");
        assertEquals(1, result.size());
        assertTrue(result.contains("ROLE_ADMIN"));
    }

    @Test
    void getAllRolesAsList_caseInsensitive() throws Exception {
        PlaintextRoleProvider provider = mock(PlaintextRoleProvider.class);
        when(provider.getRoles()).thenReturn(Set.of("ROLE_ADMIN"));

        java.lang.reflect.Field rpField = PlaintextSecWrapper.class.getDeclaredField("roleProviders");
        rpField.setAccessible(true);
        rpField.set(wrapper, List.of(provider));

        List<String> result = wrapper.getAllRolesAsList("ADMIN");
        assertEquals(1, result.size());
        assertTrue(result.contains("ROLE_ADMIN"));
    }

    @Test
    void getAllRolesAsList_noMatch_returnsQuery() throws Exception {
        PlaintextRoleProvider provider = mock(PlaintextRoleProvider.class);
        when(provider.getRoles()).thenReturn(Set.of("ROLE_ADMIN"));

        java.lang.reflect.Field rpField = PlaintextSecWrapper.class.getDeclaredField("roleProviders");
        rpField.setAccessible(true);
        rpField.set(wrapper, List.of(provider));

        List<String> result = wrapper.getAllRolesAsList("UNKNOWN");
        assertEquals(1, result.size());
        assertEquals("UNKNOWN", result.get(0));
    }

    // -------------------------------------------------------------------------
    // getMandat (static)
    // -------------------------------------------------------------------------

    @Test
    void getMandat_withNoUser_returnsNU() {
        // instance is set, but getUser returns null
        when(sec.getUser(false)).thenReturn(null);
        assertEquals("NU", PlaintextSecWrapper.getMandat());
    }

    @Test
    void getMandat_withUser_returnsUserMandat() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(mockUser.getMandat()).thenReturn("testMandat");
        when(sec.getUser(false)).thenReturn(mockUser);
        assertEquals("testMandat", PlaintextSecWrapper.getMandat());
    }

    // -------------------------------------------------------------------------
    // setMandant (static)
    // -------------------------------------------------------------------------

    @Test
    void setMandant_setsOnUser() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(sec.getUser(false)).thenReturn(mockUser);
        PlaintextSecWrapper.setMandant("newMandat");
        verify(mockUser).setMandat("newMandat");
    }

    // -------------------------------------------------------------------------
    // saveUser (with template)
    // -------------------------------------------------------------------------

    @Test
    void saveUser_newUser_withTemplate_doesNotThrow() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(mockUser.getId()).thenReturn(0L);
        assertDoesNotThrow(() -> wrapper.saveUser(mockUser, "template"));
    }

    @Test
    void saveUser_newUser_withoutTemplate_doesNotThrow() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(mockUser.getId()).thenReturn(0L);
        assertDoesNotThrow(() -> wrapper.saveUser(mockUser, null));
    }

    @Test
    void saveUser_existingUser_doesNotThrow() {
        PlaintextUser mockUser = mock(PlaintextUser.class);
        when(mockUser.getId()).thenReturn(5L);
        assertDoesNotThrow(() -> wrapper.saveUser(mockUser, "template"));
    }

    // -------------------------------------------------------------------------
    // getPicture
    // -------------------------------------------------------------------------

    @Test
    void getPicture_returnsEmptyString() {
        assertEquals("", wrapper.getPicture());
    }
}
