/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 388: resolution of a managed secret at runtime via {@link SecretResolver}.
 *
 * <p>Background: the {@code vault:} prefix in properties is resolved at startup and reads
 * Vaultwarden exclusively. For the {@code LOCAL_DB} backend that cannot be retrofitted — at startup
 * there is neither a database nor tenants, and secrets are tenant-bound. Hence this
 * request-context resolution.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecretService: resolve()")
class SecretServiceResolveTest {

    @Mock private SecretEntryRepository entryRepo;
    @Mock private SecretBackendConfigRepository configRepo;
    @Mock private SecretCrypto crypto;
    @Mock private VaultwardenSecretBackend vaultwarden;
    @Mock private HashiCorpVaultBackend hashicorp;
    @Mock private PasswordGenerator generator;

    @InjectMocks private SecretService service;

    private SecretEntry eintrag(String name, SecretBackendType backend) {
        SecretEntry e = new SecretEntry();
        e.setName(name);
        e.setMandat("plaintext");
        e.setBackendType(backend);
        e.setDeleted(false);
        return e;
    }

    private MockedStatic<PlaintextSecurityHolder> mandat() {
        MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class);
        sec.when(PlaintextSecurityHolder::getMandat).thenReturn("plaintext");
        return sec;
    }

    @Test
    void localDb_wirdEntschluesselt() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            SecretEntry e = eintrag("zeiterfassung.jira-password", SecretBackendType.LOCAL_DB);
            e.setWertEncrypted("chiffrat");
            when(entryRepo.findByMandatAndName("plaintext", "zeiterfassung.jira-password"))
                    .thenReturn(Optional.of(e));
            when(crypto.decrypt("chiffrat")).thenReturn("geheim");

            assertThat(service.resolve("zeiterfassung.jira-password")).contains("geheim");
        }
    }

    @Test
    void vaultwarden_wirdUeberDasBackendGelesen() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            when(entryRepo.findByMandatAndName("plaintext", "app.sciforma"))
                    .thenReturn(Optional.of(eintrag("app.sciforma", SecretBackendType.VAULTWARDEN)));
            when(vaultwarden.readValue("app.sciforma")).thenReturn("aus-dem-tresor");

            assertThat(service.resolve("app.sciforma")).contains("aus-dem-tresor");
        }
    }

    /**
     * The actual reason why {@code resolve} evaluates the backend type of the <b>entry</b> and not
     * {@code activeBackend()}: the latter is only the default for newly created secrets. After a
     * backend switch without migration the values still reside where they were created. If the
     * active backend were asked, the resolution would silently return nothing here — and the caller
     * would fall back to a stale property value without anyone noticing.
     */
    @Test
    void backendDesEintragsEntscheidet_nichtDasAktive() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            SecretBackendConfig aktiv = new SecretBackendConfig();
            aktiv.setBackendType(SecretBackendType.LOCAL_DB);
            when(configRepo.findFirstByMandatAndAktivAndDeleted("plaintext", true, false))
                    .thenReturn(Optional.of(aktiv));
            when(entryRepo.findByMandatAndName("plaintext", "app.sciforma"))
                    .thenReturn(Optional.of(eintrag("app.sciforma", SecretBackendType.VAULTWARDEN)));
            when(vaultwarden.readValue("app.sciforma")).thenReturn("aus-dem-tresor");

            assertThat(service.resolve("app.sciforma")).contains("aus-dem-tresor");
            verify(crypto, never()).decrypt(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void geloeschtesSecret_liefertNichts() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            SecretEntry e = eintrag("alt", SecretBackendType.LOCAL_DB);
            e.setWertEncrypted("chiffrat");
            e.setDeleted(true);
            when(entryRepo.findByMandatAndName("plaintext", "alt")).thenReturn(Optional.of(e));

            assertThat(service.resolve("alt")).isEmpty();
        }
    }

    @Test
    void unbekanntesSecret_liefertNichts() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            when(entryRepo.findByMandatAndName("plaintext", "gibtsnicht")).thenReturn(Optional.empty());

            assertThat(service.resolve("gibtsnicht")).isEmpty();
        }
    }

    /**
     * An unreachable vault must not drag the caller down with it: the download of a script should
     * then fall back to the configured property instead of ending with an error page.
     */
    @Test
    void backendFehler_wirdZuLeeremErgebnis() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            when(entryRepo.findByMandatAndName("plaintext", "app.sciforma"))
                    .thenReturn(Optional.of(eintrag("app.sciforma", SecretBackendType.VAULTWARDEN)));
            when(vaultwarden.readValue("app.sciforma")).thenThrow(new IllegalStateException("Tresor zu"));

            assertThat(service.resolve("app.sciforma")).isEmpty();
        }
    }

    /** An empty value is as good as none — otherwise it would override the caller's fallback. */
    @Test
    void leererWert_giltAlsNichtVorhanden() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            SecretEntry e = eintrag("leer", SecretBackendType.LOCAL_DB);
            e.setWertEncrypted("chiffrat");
            when(entryRepo.findByMandatAndName("plaintext", "leer")).thenReturn(Optional.of(e));
            when(crypto.decrypt("chiffrat")).thenReturn("");

            assertThat(service.resolve("leer")).isEmpty();
        }
    }

    @Test
    void nullUndLeererName_liefernNichts() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mandat()) {
            assertThat(service.resolve(null)).isEmpty();
            assertThat(service.resolve("   ")).isEmpty();
            verify(entryRepo, never()).findByMandatAndName(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }
}
