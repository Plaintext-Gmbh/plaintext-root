/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Card 855, step 1: <b>proves</b> that OpenBao speaks the Vault KV v2 API the way
 * {@link HashiCorpVaultBackend} uses it — instead of assuming it.
 *
 * <p><b>Why against the real class and not via {@code curl}.</b> A {@code curl} only shows that
 * OpenBao answers HTTP. The question of this card is a different one: does <i>our</i> client cope
 * with it — with its paths ({@code /v1/<mount>/data/<name>}), its header
 * ({@code X-Vault-Token}) and its expectation about the response shape? That is why the unchanged
 * production code runs here; the only thing mocked is where the configuration comes from
 * (otherwise it would come encrypted out of the database).</p>
 *
 * <p><b>What this test does NOT answer:</b> the operational questions of the card. The instance runs
 * in dev mode and is therefore permanently unsealed — the unseal question (step 2) is left out here,
 * not solved. Anyone concluding from this green test that operations are settled makes exactly the
 * mistake the card warns about.</p>
 *
 * <p>Doubly guarded against the CI ({@code @Tag} + environment variable), because it needs a running
 * instance. Local run:</p>
 * <pre>
 * docker run -d --rm --name openbao855 -p 8200:8200 \
 *   -e BAO_DEV_ROOT_TOKEN_ID=test-root-855 -e BAO_DEV_LISTEN_ADDRESS=0.0.0.0:8200 \
 *   openbao/openbao:latest server -dev
 *
 * export OPENBAO_LIVE=true
 * export OPENBAO_URL=http://127.0.0.1:8200
 * export OPENBAO_TOKEN=test-root-855
 * mvn -pl plaintext-admin-secrets test \
 *     -Dtest=OpenBaoKompatibilitaetLiveTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Tag("live-openbao")
@EnabledIfEnvironmentVariable(named = "OPENBAO_LIVE", matches = "true")
class OpenBaoKompatibilitaetLiveTest {

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * Builds the real backend, but with a configuration from the environment instead of from the
     * encrypted database row. {@code config()} is private and reads via the repository and
     * {@link SecretCrypto} — both are mocked here so that they return our test JSON.
     */
    private static HashiCorpVaultBackend backendMitTestConfig() {
        String json = "{\"url\":\"" + env("OPENBAO_URL", "http://127.0.0.1:8200")
                + "\",\"token\":\"" + env("OPENBAO_TOKEN", "test-root-855")
                + "\",\"mount\":\"" + env("OPENBAO_MOUNT", "secret") + "\"}";

        SecretBackendConfig cfg = new SecretBackendConfig();
        cfg.setBackendType(SecretBackendType.HASHICORP);
        cfg.setConfigEncrypted("egal-wird-gemockt");

        SecretBackendConfigRepository repo = Mockito.mock(SecretBackendConfigRepository.class);
        Mockito.when(repo.findFirstByMandatAndAktivAndDeleted(any(), anyBoolean(), anyBoolean()))
                .thenReturn(Optional.of(cfg));

        SecretCrypto crypto = Mockito.mock(SecretCrypto.class);
        Mockito.when(crypto.decrypt(anyString())).thenReturn(json);

        return new HashiCorpVaultBackend(repo, crypto);
    }

    @Test
    @DisplayName("OpenBao beantwortet Health, Schreiben und Lesen so, wie der Vault-Client es erwartet")
    void schreibenUndLesenMitGegenprobe() {
        // config() reads the tenant from the static holder; in the test no context is
        // set up, therefore one is provided here.
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
        sec.when(PlaintextSecurityHolder::getMandat).thenReturn("test");
        HashiCorpVaultBackend backend = backendMitTestConfig();

        // --- Health: the client queries /v1/auth/token/lookup-self ---
        assertThat(backend.isAvailable())
                .as("Config muss gelesen werden, sonst prueft der Test nichts")
                .isTrue();
        SecretHealth health = backend.health();
        assertThat(health.ok())
                .as("health() sagt: %s", health.detail())
                .isTrue();

        // --- Write path ---
        String name = "karte855-probe-" + UUID.randomUUID().toString().substring(0, 8);
        String wert = "wert-" + UUID.randomUUID();
        String notiz = "Kompatibilitaetsprobe Karte 855";
        backend.set(name, wert, notiz);

        // --- Read path, cross-check of the write ---
        assertThat(backend.readValue(name))
                .as("zurueckgelesener Wert muss dem geschriebenen entsprechen")
                .isEqualTo(wert);
        assertThat(backend.comment(name))
                .as("die Notiz wird in demselben Eintrag neben dem Wert gefuehrt")
                .isEqualTo(notiz);

        // --- NEGATIVE CONTROL: without it the test would also be green if readValue always returned
        //     the same value or silently swallowed errors. An unknown key MUST arrive as
        //     "not found", not as a success with an empty value.
        assertThat(backend.readValue("gibt-es-nicht-" + UUID.randomUUID()))
                .as("unbekannter Schluessel darf keinen Wert liefern")
                .isNull();
        }
    }

    @Test
    @DisplayName("Ein zweites set() ueberschreibt — KV v2 legt eine neue Version an, der Client liest die neueste")
    void ueberschreibenLiefertDenNeuenWert() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
        sec.when(PlaintextSecurityHolder::getMandat).thenReturn("test");
        HashiCorpVaultBackend backend = backendMitTestConfig();
        String name = "karte855-version-" + UUID.randomUUID().toString().substring(0, 8);

        backend.set(name, "erst", "v1");
        backend.set(name, "dann", "v2");

        // KV v2 versions entries: the client must get the current version, not the first one.
        assertThat(backend.readValue(name)).isEqualTo("dann");
        assertThat(backend.comment(name)).isEqualTo("v2");
        }
    }
}
