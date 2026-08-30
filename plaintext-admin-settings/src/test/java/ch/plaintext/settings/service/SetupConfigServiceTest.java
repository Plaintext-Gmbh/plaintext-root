/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.settings.entity.SetupConfig;
import ch.plaintext.settings.repository.SetupConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupConfigServiceTest {

    @Mock
    private SetupConfigRepository repository;

    @InjectMocks
    private SetupConfigService service;

    @Test
    void isTotpEnabledAnywhereTrueWhenAnyConfigHasFlag() {
        SetupConfig config = new SetupConfig();
        config.setMandat("default");
        config.setTotpEnabled(true);
        when(repository.findFirstByTotpEnabledTrue()).thenReturn(Optional.of(config));

        assertThat(service.isTotpEnabledAnywhere()).isTrue();
    }

    @Test
    void isTotpEnabledAnywhereFalseWhenNoConfigHasFlag() {
        when(repository.findFirstByTotpEnabledTrue()).thenReturn(Optional.empty());

        assertThat(service.isTotpEnabledAnywhere()).isFalse();
    }

    // Card 306: the root bootstrap default for fresh installations is OFF.
    @Test
    void isRootUserEnabledFalseWhenNoConfigExists() {
        when(repository.findByMandat("default")).thenReturn(Optional.empty());

        assertThat(service.isRootUserEnabled("default")).isFalse();
    }

    @Test
    void isRootUserEnabledReflectsPersistedValueForExistingInstall() {
        SetupConfig config = new SetupConfig();
        config.setMandat("default");
        config.setRootUserEnabled(true); // existing installation deliberately has the root user active
        when(repository.findByMandat("default")).thenReturn(Optional.of(config));

        assertThat(service.isRootUserEnabled("default")).isTrue();
    }

    // Card 627: the direction of the default is the core of it — without a configuration, recording
    // happens. A reversed default would silently switch it off for every tenant without a
    // SETUP_CONFIG row, and nobody would notice, because nothing fails.
    @Test
    void isSessionTrackingEnabledTrueWhenNoConfigExists() {
        when(repository.findByMandat("default")).thenReturn(Optional.empty());

        assertThat(service.isSessionTrackingEnabled("default")).isTrue();
    }

    @Test
    void isSessionTrackingEnabledFalseWhenSwitchedOff() {
        SetupConfig config = new SetupConfig();
        config.setMandat("default");
        config.setSessionTrackingEnabled(false);
        when(repository.findByMandat("default")).thenReturn(Optional.of(config));

        assertThat(service.isSessionTrackingEnabled("default")).isFalse();
    }

    @Test
    void neueSetupConfigZeichnetAuf() {
        // The entity default decides for freshly created configurations (getOrCreate).
        assertThat(new SetupConfig().isSessionTrackingEnabled()).isTrue();
    }
}
