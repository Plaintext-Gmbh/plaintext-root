/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.AnforderungApiSettings;
import ch.plaintext.anforderungen.repository.AnforderungApiSettingsRepository;
import ch.plaintext.anforderungen.security.ApiTokenHasher;
import ch.plaintext.anforderungen.service.ClaudeAutomationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1449 S-12: the token of the Claude automation is no longer shown from the database and no
 * new cleartext is stored — only the hash; a new token is visible once, right after generating.
 */
class AnforderungSettingsBackingBeanTest {

    private final AnforderungApiSettingsRepository repo = mock(AnforderungApiSettingsRepository.class);
    private final ClaudeAutomationService service = mock(ClaudeAutomationService.class);
    private final PlaintextSecurity security = mock(PlaintextSecurity.class);
    private final AnforderungSettingsBackingBean bean = new AnforderungSettingsBackingBean();

    private AnforderungApiSettings zeile;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bean, "apiSettingsRepository", repo);
        ReflectionTestUtils.setField(bean, "claudeAutomationService", service);
        ReflectionTestUtils.setField(bean, "security", security);
        zeile = new AnforderungApiSettings();
        zeile.setId(5L);
        zeile.setMandat("demo");
        zeile.setClaudeAutomationEnabled(true);
        when(security.getMandat()).thenReturn("demo");
        when(repo.findById(5L)).thenReturn(Optional.of(zeile));
        when(repo.findByMandat("demo")).thenReturn(Optional.of(zeile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(service.getAllPrompts()).thenReturn(List.of());
    }

    @Test
    void gespeicherterKlartextWirdNichtAngezeigt() {
        zeile.setApiToken("alt-klartext");

        bean.loadSettings(5L);

        assertThat(bean.getApiToken()).isNull();
        assertThat(bean.isTokenVorhanden()).isTrue();
    }

    @Test
    void neuerTokenWirdNurAlsHashGespeichert() {
        when(service.generateApiToken()).thenReturn("neu-123");
        bean.loadSettings(5L);

        bean.generateToken();
        bean.save();

        assertThat(zeile.getApiToken()).isNull();
        assertThat(zeile.getApiTokenHash()).isEqualTo(ApiTokenHasher.sha256Hex("neu-123"));
        assertThat(bean.getApiToken()).isEqualTo("neu-123"); // shown once, until the page is reloaded
    }

    @Test
    void speichernOhneNeuenTokenLaesstDenHashStehen() {
        zeile.setApiTokenHash(ApiTokenHasher.sha256Hex("bestand"));
        bean.loadSettings(5L);
        bean.setClaudeAutomationEnabled(false);

        bean.save();

        assertThat(zeile.getApiTokenHash()).isEqualTo(ApiTokenHasher.sha256Hex("bestand"));
        assertThat(zeile.getClaudeAutomationEnabled()).isFalse();
    }
}
