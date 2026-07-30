/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.repository.RollenzuteilungRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RollenzuteilungServiceTest {

    @Mock
    private RollenzuteilungRepository repository;

    @Mock
    private PlaintextSecurity security;

    private RollenzuteilungService service;

    @BeforeEach
    void setUp() {
        service = new RollenzuteilungService(repository, security);
    }

    @Test
    void getAllRollenzuteilungen_delegatesToRepository() {
        List<Rollenzuteilung> expected = List.of(new Rollenzuteilung());
        when(repository.findByMandat("mandat1")).thenReturn(expected);

        List<Rollenzuteilung> result = service.getAllRollenzuteilungen("mandat1");
        assertEquals(expected, result);
        verify(repository).findByMandat("mandat1");
    }

    @Test
    void getRollenzuteilungenForUser_delegatesToRepository() {
        List<Rollenzuteilung> expected = List.of(new Rollenzuteilung());
        when(repository.findByUsernameAndMandat("user1", "mandat1")).thenReturn(expected);

        List<Rollenzuteilung> result = service.getRollenzuteilungenForUser("user1", "mandat1");
        assertEquals(expected, result);
    }

    @Test
    void getActiveRolesForUser_delegatesToRepository() {
        List<String> expected = List.of("ROLE_USER", "ROLE_ADMIN");
        when(repository.findActiveRolesByUsernameAndMandat("user1", "mandat1")).thenReturn(expected);

        List<String> result = service.getActiveRolesForUser("user1", "mandat1");
        assertEquals(expected, result);
    }

    @Test
    void getAllUsers_delegatesToRepository() {
        List<String> expected = List.of("user1", "user2");
        when(repository.findAllUsernamesByMandat("mandat1")).thenReturn(expected);

        List<String> result = service.getAllUsers("mandat1");
        assertEquals(expected, result);
    }

    @Test
    void save_delegatesToRepository() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setUsername("user1");
        when(repository.save(rz)).thenReturn(rz);

        Rollenzuteilung result = service.save(rz);
        assertSame(rz, result);
        verify(repository).save(rz);
    }

    @Test
    void delete_delegatesToRepository() {
        service.delete(1L);
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteByUsernameAndMandatAndRole_delegatesToRepository() {
        service.deleteByUsernameAndMandatAndRole("user1", "mandat1", "ROLE_ADMIN");
        verify(repository).deleteByUsernameAndMandatAndRoleName("user1", "mandat1", "ROLE_ADMIN");
    }

    @Test
    void findByUsernameAndMandatAndRole_delegatesToRepository() {
        Rollenzuteilung rz = new Rollenzuteilung();
        when(repository.findByUsernameAndMandatAndRoleName("user1", "mandat1", "ROLE_ADMIN"))
                .thenReturn(Optional.of(rz));

        Optional<Rollenzuteilung> result = service.findByUsernameAndMandatAndRole("user1", "mandat1", "ROLE_ADMIN");
        assertTrue(result.isPresent());
        assertSame(rz, result.get());
    }

    @Test
    void findByUsernameAndMandatAndRole_returnsEmpty_whenNotFound() {
        when(repository.findByUsernameAndMandatAndRoleName("user1", "mandat1", "ROLE_ROOT"))
                .thenReturn(Optional.empty());

        Optional<Rollenzuteilung> result = service.findByUsernameAndMandatAndRole("user1", "mandat1", "ROLE_ROOT");
        assertTrue(result.isEmpty());
    }

    @Test
    void getAllForCurrentMandat_usesSecurityMandat() {
        when(security.getMandat()).thenReturn("current-mandat");
        List<Rollenzuteilung> expected = List.of(new Rollenzuteilung());
        when(repository.findByMandat("current-mandat")).thenReturn(expected);

        List<Rollenzuteilung> result = service.getAllForCurrentMandat();
        assertEquals(expected, result);
        verify(security).getMandat();
    }

    @Test
    void getAllRollenzuteilungenForCurrentUser_rootUser_returnsAll() {
        when(security.ifGranted("ROLE_ROOT")).thenReturn(true);
        List<Rollenzuteilung> allEntries = List.of(new Rollenzuteilung(), new Rollenzuteilung());
        when(repository.findAll()).thenReturn(allEntries);

        List<Rollenzuteilung> result = service.getAllRollenzuteilungenForCurrentUser();
        assertEquals(2, result.size());
        verify(repository).findAll();
    }

    @Test
    void getAllRollenzuteilungenForCurrentUser_nonRootUser_returnsCurrentMandat() {
        when(security.ifGranted("ROLE_ROOT")).thenReturn(false);
        when(security.getMandat()).thenReturn("my-mandat");

        List<Rollenzuteilung> mandatEntries = List.of(new Rollenzuteilung());
        when(repository.findByMandat("my-mandat")).thenReturn(mandatEntries);

        List<Rollenzuteilung> result = service.getAllRollenzuteilungenForCurrentUser();
        assertEquals(1, result.size());
        verify(repository).findByMandat("my-mandat");
        verify(repository, never()).findAll();
    }
}
