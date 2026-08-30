/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * LIVE verification of the ROTATION (write direction) against our own Vaultwarden instance — runs ONLY locally.
 *
 * <p>Guarded against CI twice over: {@code @Tag("live-vault")} AND
 * {@code @EnabledIfEnvironmentVariable(VAULT_LIVE=true)}. In CI the environment
 * variable is not set, so the test is skipped automatically.</p>
 *
 * <p><b>Safety:</b> this test creates NOTHING in the vault and rotates NO
 * production item. It only ever writes when a dedicated item named
 * {@code VaultRotationSelfTest} exists. If it is missing, the write path is NOT verified
 * live (the test passes with a note) — the read flow is verified separately anyway.</p>
 *
 * <p>Local run (credentials exclusively from the env, never in the repo):</p>
 * <pre>
 * export VAULT_LIVE=true
 * export PLAINTEXT_VAULT_EMAIL=&lt;service-account-email&gt;
 * export PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * mvn -pl plaintext-root-common test \
 *     -Dtest=VaultwardenRotationLiveVerificationTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * <p>The test sets the password of the self-test item to a random value and reads
 * it back via {@link VaultwardenSecretService#getPassword(String)} (== what was set).
 * NO plaintext secrets are printed, only JA/NEIN.</p>
 */
@Tag("live-vault")
@EnabledIfEnvironmentVariable(named = "VAULT_LIVE", matches = "true")
class VaultwardenRotationLiveVerificationTest {

    private static final String SELF_TEST_ITEM = "VaultRotationSelfTest";

    @Test
    void rotatesSelfTestItemAndReadsBack() {
        String email = env("PLAINTEXT_VAULT_EMAIL");
        String masterPw = env("PLAINTEXT_VAULT_MASTER_PASSWORD");

        VaultwardenProperties props = new VaultwardenProperties();
        props.setEnabled(true);
        props.setUrl(env("PLAINTEXT_VAULT_URL"));
        props.setEmail(email);
        props.setMasterPassword(masterPw);

        VaultwardenSecretService service =
                new VaultwardenSecretService(props, new VaultwardenClient(props, "live-verify-test"));

        // Write ONLY when the dedicated self-test item already exists.
        Optional<VaultwardenItem> selfTest = service.getSecret(SELF_TEST_ITEM);
        if (selfTest.isEmpty()) {
            System.out.println("=== LIVE-VAULT ROTATION ===");
            System.out.println("Self-Test-Item '" + SELF_TEST_ITEM
                    + "' nicht vorhanden -> Rotation-Write NICHT live geprueft (nichts angelegt).");
            return; // deliberately not an error: do not create anything in the real vault
        }

        String newPassword = randomToken();
        boolean rotated = service.rotatePassword(SELF_TEST_ITEM, newPassword);
        Optional<String> readBack = service.getPassword(SELF_TEST_ITEM);
        boolean match = readBack.map(newPassword::equals).orElse(false);

        System.out.println("=== LIVE-VAULT ROTATION ===");
        System.out.println("item             : " + SELF_TEST_ITEM);
        System.out.println("rotate returned  : " + rotated);
        System.out.println("read-back present: " + readBack.isPresent());
        System.out.println("ROTATION MATCH   : " + (match ? "JA" : "NEIN"));

        assertThat(rotated).as("rotatePassword lieferte true").isTrue();
        assertThat(match).as("zurueckgelesenes Passwort == gesetzter Zufallswert").isTrue();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return "rot-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env-Variable " + name + " fehlt (nur fuer lokalen Live-Test)");
        }
        return v;
    }
}
