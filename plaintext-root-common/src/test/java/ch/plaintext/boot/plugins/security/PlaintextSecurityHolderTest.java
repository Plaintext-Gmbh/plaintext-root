/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.PlaintextSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaintextSecurityHolderTest {

    @Mock
    private PlaintextSecurity mockSecurity;

    @Mock
    private Authentication mockAuthentication;

    private PlaintextSecurityHolder holder;

    @BeforeEach
    void setUp() {
        holder = new PlaintextSecurityHolder();
        holder.setDelegate(mockSecurity);
    }

    // -------------------------------------------------------------------------
    // getMandat
    // -------------------------------------------------------------------------

    @Test
    void getMandat_delegatesToSecurity() {
        when(mockSecurity.getMandat()).thenReturn("testMandat");
        assertEquals("testMandat", PlaintextSecurityHolder.getMandat());
        verify(mockSecurity).getMandat();
    }

    // -------------------------------------------------------------------------
    // getId
    // -------------------------------------------------------------------------

    @Test
    void getId_delegatesToSecurity() {
        when(mockSecurity.getId()).thenReturn(99L);
        assertEquals(99L, PlaintextSecurityHolder.getId());
        verify(mockSecurity).getId();
    }

    // -------------------------------------------------------------------------
    // getUser
    // -------------------------------------------------------------------------

    @Test
    void getUser_delegatesToSecurity() {
        when(mockSecurity.getUser()).thenReturn("testUser");
        assertEquals("testUser", PlaintextSecurityHolder.getUser());
        verify(mockSecurity).getUser();
    }

    // -------------------------------------------------------------------------
    // getAuthentication
    // -------------------------------------------------------------------------

    @Test
    void getAuthentication_delegatesToSecurity() {
        when(mockSecurity.getAuthentication()).thenReturn(mockAuthentication);
        assertSame(mockAuthentication, PlaintextSecurityHolder.getAuthentication());
        verify(mockSecurity).getAuthentication();
    }

    // -------------------------------------------------------------------------
    // getMandatForUser
    // -------------------------------------------------------------------------

    @Test
    void getMandatForUser_delegatesToSecurity() {
        when(mockSecurity.getMandatForUser(42L)).thenReturn("userMandat");
        assertEquals("userMandat", PlaintextSecurityHolder.getMandatForUser(42L));
        verify(mockSecurity).getMandatForUser(42L);
    }
}
