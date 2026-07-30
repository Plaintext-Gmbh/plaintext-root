/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Extended tests for PlaintextInitLoader - initial user creation.
 */
@ExtendWith(MockitoExtension.class)
class PlaintextInitLoaderTest2 {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    @InjectMocks
    private PlaintextInitLoader initLoader;

    @Test
    void createRootUserDelayed_shouldCreateRootUser_whenNotExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("root@root.root")).thenReturn(null);
        when(userRepository.save(any(MyUserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        initLoader.createRootUserDelayed();

        verify(userRepository).save(argThat(user ->
                "root@root.root".equals(user.getUsername()) &&
                user.getRoles().contains("root") &&
                user.getRoles().contains("admin") &&
                user.getRoles().contains("user") &&
                "default".equals(user.getMandat())
        ));
    }

    @Test
    void createRootUserDelayed_shouldNotCreateRoot_whenAlreadyExists() {
        when(setupConfigService.isRootUserEnabled("default")).thenReturn(true);
        MyUserEntity existing = new MyUserEntity();
        existing.setUsername("root@root.root");
        when(userRepository.findByUsername("root@root.root")).thenReturn(existing);

        initLoader.createRootUserDelayed();

        verify(userRepository, never()).save(any());
    }
}
