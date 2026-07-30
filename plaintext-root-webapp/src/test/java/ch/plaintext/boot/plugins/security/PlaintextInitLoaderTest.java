/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import ch.plaintext.settings.RootUserToggleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for PlaintextInitLoader - initial user creation on startup.
 */
@ExtendWith(MockitoExtension.class)
class PlaintextInitLoaderTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    @InjectMocks
    private PlaintextInitLoader initLoader;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void createRootUserDelayed_shouldCreateRootUser_whenNotExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());

        MyUserEntity rootUser = captor.getValue();
        assertEquals("root@root.root", rootUser.getUsername());
        assertTrue(rootUser.getRoles().contains("root"));
        assertTrue(rootUser.getRoles().contains("admin"));
        assertTrue(rootUser.getRoles().contains("user"));
        // Karte 306: KEIN statisches "root"-Passwort mehr.
        assertFalse(encoder.matches("root", rootUser.getPassword()),
                "Root-User darf nicht mehr mit dem statischen Passwort 'root' anlegbar sein");
    }

    /**
     * Karte 306: Der Root-Bootstrap-User bekommt ein zufaelliges Einmal-Initialpasswort (nicht
     * "root") und das Flag mustChangePassword=true, das beim ersten Login den Wechsel erzwingt.
     */
    @Test
    void createRootUserDelayed_shouldUseRandomPasswordAndForceChange() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());
        MyUserEntity rootUser = captor.getValue();

        assertTrue(rootUser.isMustChangePassword(),
                "Root-User muss beim ersten Login zum Passwortwechsel gezwungen werden");
        assertFalse(encoder.matches("root", rootUser.getPassword()));
        assertNotNull(rootUser.getPassword());
        assertFalse(rootUser.getPassword().isBlank(), "Es muss ein (gehashtes) Initialpasswort gesetzt sein");
    }

    @Test
    void createRootUserDelayed_shouldNotCreateRootUser_whenAlreadyExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        MyUserEntity existing = new MyUserEntity();
        existing.setUsername("root@root.root");
        when(userRepository.findByUsername("root@root.root")).thenReturn(existing);

        initLoader.createRootUserDelayed();

        verify(userRepository, never()).save(any());
    }

    @Test
    void createRootUserDelayed_shouldSkip_whenRootUserDisabled() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(false);

        initLoader.createRootUserDelayed();

        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void onRootUserToggle_shouldCreateRootUser_whenEnabled() {
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.onRootUserToggle(new RootUserToggleEvent(this, true));

        verify(userRepository).save(any(MyUserEntity.class));
    }

    @Test
    void onRootUserToggle_shouldDeleteRootUser_whenDisabled() {
        MyUserEntity existing = new MyUserEntity();
        existing.setUsername("root@root.root");
        when(userRepository.findByUsername("root@root.root")).thenReturn(existing);

        initLoader.onRootUserToggle(new RootUserToggleEvent(this, false));

        verify(userRepository).delete(existing);
    }

    @Test
    void createRootUserDelayed_shouldSetMandatToDefault() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(i -> i.getArgument(0));

        initLoader.createRootUserDelayed();

        ArgumentCaptor<MyUserEntity> captor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(captor.capture());

        MyUserEntity rootUser = captor.getValue();
        assertEquals("default", rootUser.getMandat());
    }
}
